package spirelink.ui;

import com.google.gson.JsonObject;

/**
 * Turns machine action names into the one sentence a human would say, for the in-game HUD.
 *
 * <p>Rules: Chinese only, no ids or version numbers, at most ~28 characters per line.
 */
public final class HudPhrases {

    private HudPhrases() {
    }

    /** Line shown after an action really ran. */
    public static String done(String type, JsonObject args) {
        if (type == null) {
            return "动了一下";
        }
        switch (type) {
            case "play_card":
                return clip("打出手牌《" + cardName(args) + "》");
            case "end_turn":
                return "结束这一回合";
            case "use_potion":
                return clip("喝下药水《" + potionName(args) + "》");
            case "discard_potion":
                return "扔掉了那瓶药水";
            case "choose_map_node": {
                String row = text(args, "row");
                String room = text(args, "room");
                String column = text(args, "column_label");
                if (!row.isEmpty() && !room.isEmpty()) {
                    return clip("走向 第" + row + "行 · " + room
                            + (column.isEmpty() ? "" : "（" + column + "）"));
                }
                return "往前走一层";
            }
            case "proceed":
                return "继续前进";
            case "choose_option":
                return "选了个选项";
            case "take_reward":
                return "拿走奖励";
            case "take_card":
                return "收下这张牌";
            case "skip_card":
                return "不拿这张牌";
            case "claim_relic":
                return "收下遗物";
            case "open_chest":
                return "打开宝箱";
            case "open_shop":
                return "看看商店";
            case "buy":
                return "买了件东西";
            case "purge_card":
                return "删掉一张牌";
            case "rest":
                return "歇一会儿，回点血";
            case "smith":
                return "打磨一张牌";
            case "confirm_grid":
                return "就选这些";
            case "cancel_grid":
                return "再想想，重选";
            case "choose_hand_cards":
                return "挑了几张牌";
            case "confirm_hand_select":
                return "确认选择";
            case "cancel_hand_select":
                return "算了，不选了";
            default:
                return "调整了下动作";
        }
    }

    /**
     * Line for "the agent looked at the board" (get_state): say what it saw, not which version it
     * read. Falls back to a plain sentence when the payload has no combat block.
     */
    public static String looked(JsonObject body) {
        try {
            JsonObject player = body != null && body.has("player") && !body.get("player").isJsonNull()
                    ? body.getAsJsonObject("player") : null;
            int hp = player != null && player.has("hp") && !player.get("hp").isJsonNull()
                    ? player.get("hp").getAsInt() : -1;
            int maxHp = player != null && player.has("max_hp") && !player.get("max_hp").isJsonNull()
                    ? player.get("max_hp").getAsInt() : -1;
            int enemies = -1;
            JsonObject combat = body != null && body.has("combat") && !body.get("combat").isJsonNull()
                    ? body.getAsJsonObject("combat") : null;
            if (combat != null && combat.has("enemies") && combat.get("enemies").isJsonArray()) {
                enemies = combat.getAsJsonArray("enemies").size();
            }
            if (enemies >= 0 && hp >= 0) {
                return clip("扫了眼场面：" + enemies + " 只怪，我 " + hp + "/" + maxHp);
            }
            if (hp >= 0) {
                return clip("扫了眼场面：我 " + hp + "/" + maxHp);
            }
        } catch (Throwable ignored) {
            // HUD only
        }
        return "扫了眼场面";
    }

    /** Line for "the agent read the action list" (legal_actions). */
    public static String checked() {
        return "琢磨了下还能出什么牌";
    }

    /** Best-effort card name from a payload uuid; empty when unknown. */
    private static String cardName(JsonObject args) {
        String uuid = text(args, "uuid");
        if (uuid.isEmpty()) {
            return "卡牌";
        }
        try {
            com.megacrit.cardcrawl.characters.AbstractPlayer player =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
            if (player == null) {
                return "卡牌";
            }
            java.util.List<com.megacrit.cardcrawl.cards.AbstractCard> all =
                    new java.util.ArrayList<com.megacrit.cardcrawl.cards.AbstractCard>();
            if (player.hand != null) {
                all.addAll(player.hand.group);
            }
            if (player.drawPile != null) {
                all.addAll(player.drawPile.group);
            }
            if (player.discardPile != null) {
                all.addAll(player.discardPile.group);
            }
            if (player.masterDeck != null) {
                all.addAll(player.masterDeck.group);
            }
            for (com.megacrit.cardcrawl.cards.AbstractCard card : all) {
                if (card != null && uuid.equals(String.valueOf(card.uuid)) && card.name != null) {
                    return card.name;
                }
            }
        } catch (Throwable ignored) {
            // HUD only
        }
        return "卡牌";
    }

    private static String potionName(JsonObject args) {
        try {
            com.megacrit.cardcrawl.characters.AbstractPlayer player =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
            JsonObject payload = args;
            int slot = payload != null && payload.has("slot") && !payload.get("slot").isJsonNull()
                    ? payload.get("slot").getAsInt()
                    : payload != null && payload.has("index") && !payload.get("index").isJsonNull()
                            ? payload.get("index").getAsInt() : -1;
            if (player != null && slot >= 0 && player.potions != null
                    && slot < player.potions.size() && player.potions.get(slot) != null) {
                return player.potions.get(slot).name;
            }
        } catch (Throwable ignored) {
            // HUD only
        }
        return "药水";
    }

    private static String text(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return "";
        }
        try {
            return args.get(key).getAsString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String clip(String line) {
        int max = 28;
        return line.length() <= max ? line : line.substring(0, max - 1) + "…";
    }
}
