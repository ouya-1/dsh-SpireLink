package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

import java.util.Collections;
import java.util.List;

/**
 * Issue #120 · C11-02: the number in the reply has to be the number on the screen.
 *
 * <p>现场（QA 第 11 轮）：{@code dev_relic{id:"SapphireKey"}} 回包 {@code rewards:2}，紧接着同拍
 * {@code get_state.rewards.count} 是 <b>3</b> —— 多出来的一条是 CARD「将一张卡牌加入你的牌组中」，
 * 看起来像 dev 工具多塞了一行。</p>
 *
 * <p>它不是 dev 工具塞的，也不是房间奖励表里的东西。原版
 * {@code CombatRewardScreen.setupItemReward()}（javap 核过）先
 * {@code this.rewards = new ArrayList<>(room.rewards)}，然后<b>只在普通房间</b>（不是
 * TreasureRoom / RestRoom / 带 {@code noCardsInRewards} 的事件）往<b>屏幕</b>列表末尾追加一条
 * {@code new RewardItem()}（CARD 行）。所以 Room 列表和屏幕列表本来就可以差一条，而
 * {@code take_reward{index}} 索引的、{@code get_state.rewards.items} 列的都是<b>屏幕</b>列表。</p>
 *
 * <p>于是「回包里的 rewards」和「key_index / relic_index」一律按屏幕列表算，
 * {@code get_state} 与 dev 回包、以及 agent 下一条 {@code take_reward} 用的下标从此是同一套数；
 * 房间列表的条数改名 {@code room_rewards} 单独报（它对 agent 没用，排错时有用）。</p>
 */
final class RewardScreen {

    private RewardScreen() {
    }

    /** True while the combat reward screen is the current screen. */
    static boolean open() {
        return AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD
                && AbstractDungeon.combatRewardScreen != null
                && AbstractDungeon.combatRewardScreen.rewards != null;
    }

    /**
     * The rows the agent can index right now.
     *
     * <p>The screen's own copy when it is up (that is what {@code take_reward} and
     * {@code get_state.rewards.items} use), else the room's list - the same fallback the state
     * builder has, so a reply is still useful in the instant before the screen opens.</p>
     */
    static List<RewardItem> rows() {
        if (open()) {
            return AbstractDungeon.combatRewardScreen.rewards;
        }
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || room.rewards == null) {
            return Collections.emptyList();
        }
        return room.rewards;
    }

    /** Position of {@code row} in {@link #rows()} by identity, or -1 when it is not on screen. */
    static int indexOf(RewardItem row) {
        if (row == null) {
            return -1;
        }
        List<RewardItem> rows = rows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) == row) {
                return i;
            }
        }
        return -1;
    }

    /** Rows in the room's own list: what the constructor filled, before vanilla adds its card row. */
    static int roomRows() {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        return room == null || room.rewards == null ? 0 : room.rewards.size();
    }

    /**
     * The screen list as the agent sees it: {@code [{index,type,text}]}.
     *
     * <p>Small on purpose - {@code get_state.rewards.items} carries the gold/potion/relic details.
     * This exists so one dev reply is enough to know which row is which type, instead of "领了才知道
     * 那条是 CARD"（同一轮的 C11-06）。</p>
     */
    static JsonArray describeRows() {
        JsonArray out = new JsonArray();
        List<RewardItem> rows = rows();
        for (int i = 0; i < rows.size(); i++) {
            RewardItem row = rows.get(i);
            if (row == null) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("index", i);
            item.addProperty("type", row.type == null ? "UNKNOWN" : row.type.name());
            item.addProperty("text", row.text == null ? "" : row.text);
            out.add(item);
        }
        return out;
    }
}
