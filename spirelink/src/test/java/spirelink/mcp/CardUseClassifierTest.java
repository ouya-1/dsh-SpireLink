package spirelink.mcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** dev_use_card 的三态分类：played / blocked_by_game_rule / metadata_only。 */
public class CardUseClassifierTest {

    @Test
    public void normalCardThatReturnsIsPlayed() {
        assertEquals(CardUseClassifier.PLAYED, CardUseClassifier.classify(1, false));
        assertTrue(CardUseClassifier.ok(CardUseClassifier.PLAYED));
        String reason = CardUseClassifier.reason(CardUseClassifier.PLAYED, 1, null);
        assertTrue(reason, reason.contains("cost=1"));
        assertTrue(reason, reason.contains("正常返回"));
    }

    @Test
    public void xCostCardIsStillPlayable() {
        // -1 是 X 费，能打出；只有 -2 才是规则上打不出去。
        assertEquals(CardUseClassifier.PLAYED, CardUseClassifier.classify(-1, false));
        assertFalse(CardUseClassifier.unplayableByRule(-1));
        assertEquals(CardUseClassifier.PLAYED, CardUseClassifier.classify(0, false));
    }

    @Test
    public void costMinusTwoIsBlockedByGameRule() {
        assertEquals(CardUseClassifier.BLOCKED, CardUseClassifier.classify(-2, false));
        // 即使 use() 抛异常，规则拦截仍然是主因：分类不变、ok 仍为 true。
        assertEquals(CardUseClassifier.BLOCKED, CardUseClassifier.classify(-2, true));
        assertTrue(CardUseClassifier.ok(CardUseClassifier.BLOCKED));
        String reason = CardUseClassifier.reason(CardUseClassifier.BLOCKED, -2, null);
        assertTrue(reason, reason.contains("cost=-2"));
        assertTrue(reason, reason.contains("诅咒/状态牌"));
    }

    @Test
    public void blockedReasonAlsoCarriesTheExceptionSummary() {
        String reason = CardUseClassifier.reason(CardUseClassifier.BLOCKED, -2,
                new IllegalStateException("boom"));
        assertTrue(reason, reason.contains("IllegalStateException"));
        assertTrue(reason, reason.contains("boom"));
        assertTrue(reason, reason.contains("诅咒/状态牌"));
    }

    @Test
    public void exceptionOnAPlayableCardIsMetadataOnly() {
        IllegalStateException boom = new IllegalStateException("boom");
        assertEquals(CardUseClassifier.METADATA, CardUseClassifier.classify(1, true));
        assertFalse(CardUseClassifier.ok(CardUseClassifier.METADATA));
        String reason = CardUseClassifier.reason(CardUseClassifier.METADATA, 1, boom);
        // 类名 + 消息 + 栈首帧，缺一不可。
        assertTrue(reason, reason.contains("IllegalStateException"));
        assertTrue(reason, reason.contains("boom"));
        assertTrue(reason, reason.contains("CardUseClassifierTest"));
        assertTrue(reason, reason.contains("工具上下文限制"));
    }

    // ------------------------------------------------- C6-02: 结果分类之外再说清"有没有效果"

    /**
     * QA 看到的问题：{@code result:played} 而格挡 0→0、powers 空、怪物血量不动。
     * 分类回答"算不算通过"，effect 回答"到底改了什么"，两者不许含糊成一句话。
     */
    @Test
    public void effectSaysWhetherThePlayActuallyQueuedAnything() {
        assertEquals(CardUseClassifier.EFFECT_QUEUED, CardUseClassifier.effect(3, false));
        assertEquals(CardUseClassifier.EFFECT_NONE, CardUseClassifier.effect(0, false));
        assertEquals("读不到动作数时不能冒充 queued",
                CardUseClassifier.EFFECT_NONE, CardUseClassifier.effect(-1, false));
        assertEquals("use() 抛异常就是 dropped，哪怕之前排了几条",
                CardUseClassifier.EFFECT_DROPPED, CardUseClassifier.effect(2, true));
    }

    /** 每种 effect 都要能独立解释后果，不能让调用方自己猜。 */
    @Test
    public void effectNotesExplainTheConsequence() {
        String queued = CardUseClassifier.effectNote(CardUseClassifier.EFFECT_QUEUED, 3, 0);
        assertTrue(queued, queued.contains("3 条动作"));
        assertTrue("要说明走的是原版出牌路径: " + queued, queued.contains("player.useCard"));
        assertTrue("要提醒下一拍才生效: " + queued, queued.contains("下一拍"));

        String none = CardUseClassifier.effectNote(CardUseClassifier.EFFECT_NONE, 0, 0);
        assertTrue(none, none.contains("没有排出任何动作"));

        String dropped = CardUseClassifier.effectNote(CardUseClassifier.EFFECT_DROPPED, 2, 5);
        assertTrue(dropped, dropped.contains("2 条动作已丢弃"));
        assertTrue("清空了几条也要写: " + dropped, dropped.contains("清空 5 条"));
    }

    @Test
    public void describeSurvivesANullMessage() {
        assertEquals("NullPointerException", CardUseClassifier.describe(new NullPointerException()));
        assertEquals("", CardUseClassifier.describe(null));
        assertEquals("", CardUseClassifier.firstFrame(null));
    }
}
