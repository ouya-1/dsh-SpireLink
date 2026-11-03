package spirelink.actions;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * C8-01 / C8-02: one "which values can I name?" wording for every index-addressed action.
 *
 * <p>Before this, {@code claim_relic} and {@code buy} answered a bare
 * {@code "遗物序号越界: 9"} / {@code "卡牌序号越界: 9"} (no range, no candidates), and a
 * {@code take_reward} with a non-integer index carried no range at all — while an out-of-range
 * integer on the same action carried it. The shapes those two actions already had are the contract
 * here, so the new call sites cannot drift into their own wording.</p>
 */
public class IndexListTextTest {

    @Test
    public void rangeFollowsTheShapeTakeRewardHas() {
        assertEquals("合法范围 0..2（共 3 条）", IndexListText.range(3, "条", "空"));
        assertEquals("合法范围 0..0（共 1 条）", IndexListText.range(1, "条", "空"));
    }

    /** The unit is the only thing that changes: 张 cards, 个 relics, 瓶 potions. */
    @Test
    public void everyUnitKeepsTheSameShape() {
        assertEquals("合法范围 0..2（共 3 张）", IndexListText.range(3, "张", "空"));
        assertEquals("合法范围 0..3（共 4 个）", IndexListText.range(4, "个", "空"));
        assertEquals("合法范围 0..1（共 2 瓶）", IndexListText.range(2, "瓶", "空"));
        // #113: event dialog options count as 项.
        assertEquals("合法范围 0..2（共 3 项）", IndexListText.range(3, "项", "空"));
    }

    @Test
    public void anEmptyScreenOwnsItsSentence() {
        assertEquals("这一屏没有可选遗物（合法范围为空）",
                IndexListText.range(0, "个", "这一屏没有可选遗物（合法范围为空）"));
        assertEquals("这一屏没有可选遗物（合法范围为空）",
                IndexListText.range(-1, "个", "这一屏没有可选遗物（合法范围为空）"));
    }

    @Test
    public void listJoinsTheCandidates() {
        assertEquals("0=金币(25 金), 1=遗物(小盒子)",
                IndexListText.list(Arrays.asList("0=金币(25 金)", "1=遗物(小盒子)"), "空"));
        assertEquals("0=a", IndexListText.list(Arrays.asList("0=a", null, ""), "空"));
        assertEquals("空", IndexListText.list(Collections.<String>emptyList(), "空"));
        assertEquals("空", IndexListText.list(null, "空"));
    }

    @Test
    public void entryNumbersEveryCandidateLikeThePayloadDoes() {
        assertEquals("0=打击(Strike)", IndexListText.entry(0, "打击", "Strike"));
        assertEquals("3=小盒子(Tiny Chest)", IndexListText.entry(3, "小盒子", "Tiny Chest"));
        assertEquals("1=打击", IndexListText.entry(1, "打击", null));
        assertEquals("1=打击", IndexListText.entry(1, "打击", ""));
        assertEquals("1=", IndexListText.entry(1, null, null));
        // #113: candidates with no machine id (event options, minigame cards) use the 2-arg form.
        assertEquals("2=香蕉 回复 333 生命", IndexListText.entry(2, "香蕉 回复 333 生命"));
        assertEquals("2=", IndexListText.entry(2, null));
    }

    /** The shared wording must be exactly what take_reward / take_card answered before (no drift). */
    @Test
    public void thePerActionTextsDelegateHere() {
        assertEquals(IndexListText.range(3, "条", "这一屏没有奖励（合法范围为空）"),
                TakeRewardRequest.rangeText(3));
        assertEquals(IndexListText.range(3, "张", "这一屏没有可拿的牌（合法范围为空）"),
                CardRewardPolicy.rangeText(3));
        assertTrue(TakeRewardRequest.rangeText(0).contains("没有奖励"));
        assertTrue(CardRewardPolicy.rangeText(0).contains("没有可拿的牌"));
        assertFalse(CardRewardPolicy.rangeText(2).isEmpty());
    }
}