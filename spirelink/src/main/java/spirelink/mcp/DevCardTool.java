package spirelink.mcp;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

/** Test-only: inject a named card into the hand or the master deck. */
public final class DevCardTool {

    static final String BASE = "com.megacrit.cardcrawl.cards.";
    // deprecated / tempCards / optionCards 里有 65 张 cards.json 收录的卡（DEPRECATED* / ChooseCalm /
    // ChooseWrath …）：CardLibrary 里没有它们，只能靠反射，所以这几个包必须在这里。
    static final String[] PKGS = {"red.", "green.", "blue.", "purple.", "colorless.",
            "curses.", "status.", "special.", "deprecated.", "tempCards.", "optionCards.", ""};

    /** 手牌上限（游戏里也是 10）；只有 force=true 时才允许硬塞第 11 张。 */
    private static final int HAND_LIMIT = 10;

    private DevCardTool() {
    }

    public static JsonObject add(String id, String to) {
        return add(id, to, false);
    }

    /**
     * @param force true = 强制加入：跳过手牌上限（10 张）检查，响应里原样回显；
     *              不传 force 时行为与原来一致（可获得性只作 {@code obtainable} 标记，从不拦）
     */
    public static JsonObject add(String id, String to, boolean force) {
        if (!spirelink.util.TestMode.enabled()) {
            return err("DEV_TOOL_DISABLED", null);
        }
        if (AbstractDungeon.player == null) {
            return err("NOT_READY", "还没有玩家（未进地牢）");
        }
        DevCardResolve.Hit resolved = DevCardResolve.find(id);
        if (resolved == null) {
            return err("CARD_NOT_FOUND", "找不到卡牌: " + id);
        }
        if (resolved.card == null) {
            // 类存在但不是卡牌（Soul / DamageInfo …），塞不进手牌或牌组。
            return err("CARD_NOT_FOUND", resolved.problem);
        }
        AbstractCard card;
        try {
            card = resolved.fresh();
        } catch (Throwable t) {
            return err("NOT_READY", "复制卡牌失败: " + t);
        }
        boolean hand = to == null || "hand".equalsIgnoreCase(to);
        if (hand) {
            if (AbstractDungeon.player.hand == null) {
                return err("NOT_READY", "当前没有手牌区（不在战斗）");
            }
            int size = AbstractDungeon.player.hand.size();
            if (!force && size >= HAND_LIMIT) {
                // 满了还加会让手牌渲染/选牌错位，默认拦住；长测要用 force 显式放行。
                return err("HAND_FULL", "手牌已满（" + size + " 张），要硬塞请 force:true");
            }
            AbstractDungeon.player.hand.addToTop(card);
        } else {
            AbstractDungeon.player.masterDeck.group.add(card);
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("id", id);
        out.addProperty("name", card.name);
        out.addProperty("uuid", String.valueOf(card.uuid));
        out.addProperty("to", hand ? "hand" : "deck");
        out.addProperty("force", force);
        // resolved_by: library（带空格的游戏 ID / ID 与类名相同的卡）或 reflection（类名 / 全限定名）。
        out.addProperty("resolved_by", resolved.via);
        // 可获得性只做标记、不拦：老脚本就是靠 dev_card 塞各种类名的卡，拦了会直接退化。
        // force 只影响上面的手牌上限检查（长测要塞第 11 张时用）。
        out.addProperty("obtainable", resolved.obtainable);
        return out;
    }

    static Class<?> find(String base, String id, Class<?> parent, String... pkgs) {
        if (id.indexOf('.') > 0) {
            try {
                Class<?> c = Class.forName(id);
                return parent.isAssignableFrom(c) ? c : null;
            } catch (Throwable t) {
                return null;
            }
        }
        for (String pkg : pkgs) {
            try {
                Class<?> c = Class.forName(base + pkg + id);
                if (parent.isAssignableFrom(c)) {
                    return c;
                }
            } catch (Throwable ignored) {
                // next package
            }
        }
        return null;
    }

    static JsonObject err(String code, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", code);
        if (message != null) {
            out.addProperty("message", message);
        }
        return out;
    }

}
