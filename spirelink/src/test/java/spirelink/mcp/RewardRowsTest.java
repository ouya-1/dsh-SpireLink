package spirelink.mcp;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #120 · C11-02：{@code dev_relic{SapphireKey}} 回 {@code rewards:2}，同拍 {@code get_state}
 * 的奖励屏却是 {@code count:3}（多一条 CARD 行）。
 *
 * <p>多出来的那条不是 dev 工具塞的：原版 {@code CombatRewardScreen.setupItemReward()}（javap 核过）
 * 先 {@code this.rewards = new ArrayList<>(room.rewards)}，再在<b>普通房间</b>往**屏幕**列表追加一条
 * {@code new RewardItem()}（CARD 行），它永远不进 {@code room.rewards}。而 {@code take_reward{index}}
 * 和 {@code get_state.rewards.items} 用的都是屏幕列表。</p>
 *
 * <p>所以回包数字改成「屏幕上能点的行数」({@code RewardScreen.rows()})，房间列表另报
 * {@code room_rewards}，{@code key_index}/{@code relic_index} 也按屏幕算 —— 从此与
 * {@code get_state} 一致，可以直接拿去发 {@code take_reward}。</p>
 */
public class RewardRowsTest {

    private static String source(String relative) throws IOException {
        return DevEventActLockTest.stripComments(DevEventActLockTest.read(relative));
    }

    @Test
    public void theKeyReplyCountsScreenRowsAndSaysSo() throws IOException {
        String code = source("src/main/java/spirelink/mcp/KeyRewards.java");
        assertTrue("rewards 必须是屏幕行数: " + code,
                code.contains("out.addProperty(\"rewards\", RewardScreen.rows().size())"));
        assertTrue("房间列表单独报，便于排错: " + code,
                code.contains("out.addProperty(\"room_rewards\", RewardScreen.roomRows())"));
        assertTrue("下标要按屏幕列表算（take_reward 用的就是它）: " + code,
                code.contains("RewardScreen.indexOf(keyRow)"));
        assertTrue(code, code.contains("RewardScreen.indexOf(relicRow)"));
        assertTrue("顺便把每行的类型列出来，别再「领了才知道是 CARD」: " + code,
                code.contains("out.add(\"rows\", RewardScreen.describeRows())"));
        assertFalse("老的错误口径（房间条数当 rewards）不许回来",
                code.contains("out.addProperty(\"rewards\", room.rewards.size())"));
    }

    @Test
    public void devRewardReportsTheSameNumbers() throws IOException {
        String code = source("src/main/java/spirelink/mcp/DevRewardTool.java");
        assertTrue(code, code.contains("out.addProperty(\"rewards\", RewardScreen.rows().size())"));
        assertTrue(code, code.contains("out.addProperty(\"room_rewards\", RewardScreen.roomRows())"));
        assertTrue(code, code.contains("out.add(\"rows\", RewardScreen.describeRows())"));
        assertFalse("两个 dev 工具不许各算一套",
                code.contains("out.addProperty(\"rewards\", room.rewards == null ? 0 : room.rewards.size())"));
    }

    /** 多出来的那条 CARD 行的来源要写在注释里，否则下一个人还会以为是 dev 工具多塞的。 */
    @Test
    public void theReasoningIsWrittenDown() throws IOException {
        // 这里要看原文（含注释）：要钉的正是那段解释，剥掉注释就什么都没了。
        String doc = DevEventActLockTest.read("src/main/java/spirelink/mcp/RewardScreen.java");
        assertTrue("要写清多出来的行来自原版 setupItemReward", doc.contains("setupItemReward"));
        assertTrue(doc, doc.contains("TreasureRoom"));
        assertTrue(doc, doc.contains("CARD"));
        String code = source("src/main/java/spirelink/mcp/RewardScreen.java");
        assertFalse("这个类只读不写：不许往奖励表里加/删行", code.contains("room.rewards.add("));
        assertFalse(code, code.contains("room.rewards.remove("));
        assertFalse(code, code.contains(".rewards.clear("));
    }

    @Test
    public void missingRowIsMinusOne() {
        assertEquals("没有行就是 -1，不许猜 0", -1, RewardScreen.indexOf(null));
    }
}
