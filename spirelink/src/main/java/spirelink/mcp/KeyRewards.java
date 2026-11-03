package spirelink.mcp;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Issue #119 · C10-04: the three Spire keys, fabricated the way the real game fabricates them.
 *
 * <p>现场（QA 第 10 轮）：{@code dev_relic{id:"SapphireKey"}} 回答 {@code RELIC_NOT_FOUND} ——
 * 钥匙在原版里根本不是遗物（{@code com.megacrit.cardcrawl.relics} 里没有 RubyKey / SapphireKey
 * 这些类），于是「钥匙宝箱」造不出来，#116b（领钥匙静默吞掉遗物）也就没法真机验。</p>
 *
 * <p>原版真形态（{@code javap} 核过 {@code lib/desktop-1.0.jar}）：</p>
 * <ul>
 *   <li>{@code AbstractChest.open()} 先把<b>遗物行</b>放进 {@code room.rewards}，再调
 *       {@code AbstractRoom.addSapphireKey(rewards.get(size-1))}；</li>
 *   <li>{@code addSapphireKey(link)} 里是 {@code new RewardItem(link, SAPPHIRE_KEY)} 再
 *       {@code rewards.add(...)}（**追加**在遗物行后面，没有下标 0），而那个构造器把
 *       {@code key.relicLink = link} 和 {@code link.relicLink = key} 互相挂上。</li>
 * </ul>
 *
 * <p>老实现只造了钥匙行，{@code relicLink} 指向一条<b>不在</b>奖励表里的假遗物行 —— 屏幕上只有
 * 钥匙，没有「同屏遗物 + 钥匙」，正是 #116b 无法复现的原因。这里两步都做：真遗物行进表 + 钥匙行挂
 * 在它前面，和 {@code AbstractChest.open()} 一模一样。</p>
 *
 * <p>注：原版 {@code AbstractChest.open(boolean)} 还要 {@code Settings.isFinalActAvailable &&
 * !Settings.hasSapphireKey} 才加这把钥匙。dev 工具故意不查这两个门槛 —— 它就是要在任何一幕把钥匙
 * 宝箱造出来给 QA 看。</p>
 *
 * <p>Ruby Key 是特例：原版**没有** Ruby 奖励行（{@code RewardItem.RewardType} 只有 EMERALD_KEY /
 * SAPPHIRE_KEY），它来自营火 {@code Recall} 选项，只落在 {@code Settings.hasRubyKey} 上。所以
 * {@code RubyKey} 直接置位并如实说明没有奖励行，不假装造一个原版不存在的钥匙行。</p>
 */
public final class KeyRewards {

    /** The three keys a run can collect. */
    enum Key {
        RUBY("RUBY_KEY", "Ruby Key"),
        EMERALD("EMERALD_KEY", "Emerald Key"),
        SAPPHIRE("SAPPHIRE_KEY", "Sapphire Key");

        /** Same spelling {@code RewardItem.RewardType} uses (RUBY_KEY is the Settings-only one). */
        final String code;
        /** Player-facing name, the same string the reward row shows. */
        final String label;

        Key(String code, String label) {
            this.code = code;
            this.label = label;
        }

        /** @return the vanilla reward type, or {@code null} for the Ruby Key (no reward row exists) */
        RewardItem.RewardType rewardType() {
            if (this == EMERALD) {
                return RewardItem.RewardType.EMERALD_KEY;
            }
            return this == SAPPHIRE ? RewardItem.RewardType.SAPPHIRE_KEY : null;
        }
    }

    private KeyRewards() {
    }

    /**
     * Maps a caller supplied id onto a key, or {@code null} when it is not a key at all.
     *
     * <p>Deliberately forgiving: QA wrote "Ruby/Blue/Sapphire Key", the game writes "Emerald Key" and
     * the code writes "SapphireKey". Case, spaces, underscores and hyphens are ignored, and the colour
     * aliases (red/green/blue) are accepted because that is how a human names them. Pure, so the whole
     * table is unit tested without a game ({@code KeyRewardsTest}).</p>
     */
    static Key keyOf(String rawId) {
        if (rawId == null) {
            return null;
        }
        String id = rawId.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
        if (id.isEmpty()) {
            return null;
        }
        // Trailing "key" is optional: "RubyKey" and "Ruby" mean the same thing.
        if (id.endsWith("key")) {
            id = id.substring(0, id.length() - 3);
        }
        if ("ruby".equals(id) || "red".equals(id)) {
            return Key.RUBY;
        }
        if ("emerald".equals(id) || "green".equals(id)) {
            return Key.EMERALD;
        }
        if ("sapphire".equals(id) || "blue".equals(id)) {
            return Key.SAPPHIRE;
        }
        return null;
    }

    /** Human list for error messages: every id {@link #keyOf} accepts, shortest useful form. */
    static String acceptedIds() {
        return "RubyKey / EmeraldKey / SapphireKey（也认 Ruby/Red/Green/Blue/Sapphire）";
    }

    /**
     * Fabricates the key chest and opens the reward screen, so the agent sees exactly what a real
     * chest gives: the relic row and, in front of it, the key row that is linked to it.
     *
     * <p>Zero side effects on failure: rows added here are removed again and the screen is closed if
     * this call opened it. The Ruby Key sets {@code Settings.hasRubyKey} (that is its only storage)
     * and adds no row, because vanilla has none.</p>
     *
     * @return the response payload ({@code ok/key/name/rewards/...}), or an error payload
     */
    static JsonObject fabricate(Key key) {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || AbstractDungeon.player == null) {
            return error("NOT_READY", "还没有房间（未进地牢），钥匙宝箱要有奖励表才造得出来");
        }
        if (key == Key.RUBY) {
            // Vanilla: CampfireUI's Recall option only flips this flag; there is no Ruby reward row.
            com.megacrit.cardcrawl.core.Settings.hasRubyKey = true;
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("key", key.code);
            out.addProperty("name", key.label);
            out.addProperty("rewards", 0);
            out.addProperty("ruby_key", true);
            out.addProperty("message", "Ruby Key 在原版里不是奖励行（营火 Recall 直接置 "
                    + "Settings.hasRubyKey），已置位；钥匙宝箱只有 Emerald / Sapphire 两种");
            return out;
        }
        if (room.rewards == null) {
            room.rewards = new ArrayList<RewardItem>();
        }
        final int before = room.rewards.size();
        final boolean screenWasUp =
                AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD;
        try {
            // Step 1 of AbstractChest.open(): the relic row the chest would hand over.
            AbstractRelic relic = AbstractDungeon.returnRandomRelic(AbstractRelic.RelicTier.COMMON);
            if (relic == null) {
                return error("NOT_READY", "遗物池为空，钥匙奖励没有可挂的链接");
            }
            room.addRelicToRewards(relic);
            RewardItem relicRow = room.rewards.get(room.rewards.size() - 1);
            // Step 2 of AbstractChest.open(): AbstractRoom.addSapphireKey(relicRow) — the key row is
            // appended after the relic row (disassembled: AbstractRoom.addSapphireKey ends in
            // rewards.add(new RewardItem(link, SAPPHIRE_KEY)), no index), and the two rows link to
            // each other (the RewardItem ctor sets relicLink both ways).
            RewardItem keyRow = new RewardItem(relicRow, key.rewardType());
            room.rewards.add(keyRow);
            // Same screen the chest opens; without it the two rows are neither visible nor takeable.
            AbstractDungeon.combatRewardScreen.open();
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("key", key.code);
            out.addProperty("name", key.label);
            // Issue #120 · C11-02: the numbers are the SCREEN's, not the room list's - vanilla's
            // setupItemReward() appends its own CARD row to the screen copy (never to room.rewards),
            // so reporting the room count made this reply say 2 while get_state said 3. These indexes
            // are therefore directly usable as take_reward{index}.
            out.addProperty("rewards", RewardScreen.rows().size());
            out.addProperty("room_rewards", RewardScreen.roomRows());
            out.addProperty("key_index", RewardScreen.indexOf(keyRow));
            out.addProperty("relic_index", RewardScreen.indexOf(relicRow));
            out.add("rows", RewardScreen.describeRows());
            out.addProperty("relic", relic.name);
            out.addProperty("screen_opened", true);
            spirelink.util.Diag.info("dev key: 造出钥匙宝箱 key=" + key.code
                    + " relic=" + relic.relicId + " room_rewards=" + room.rewards.size()
                    + " screen_rows=" + RewardScreen.rows().size());
            return out;
        } catch (Throwable t) {
            rollback(room, before, screenWasUp);
            return error("NOT_READY", "造钥匙宝箱失败: " + t);
        }
    }

    /** Removes the rows this call added and puts the screen back. Never throws. */
    private static void rollback(AbstractRoom room, int before, boolean screenWasUp) {
        try {
            if (room != null && room.rewards != null) {
                while (room.rewards.size() > before) {
                    room.rewards.remove(room.rewards.size() - 1);
                }
            }
        } catch (Throwable ignored) {
            // best effort
        }
        if (screenWasUp) {
            return;
        }
        try {
            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD) {
                AbstractDungeon.closeCurrentScreen();
            }
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private static JsonObject error(String code, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", code);
        if (message != null) {
            out.addProperty("message", message);
        }
        return out;
    }
}
