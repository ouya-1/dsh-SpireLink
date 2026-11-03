package spirelink.state;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #119 · C10-05: {@code hints.last_error_fresh} 在 {@code queued} 之后能连续十几秒是 true。
 *
 * <p>老规则只有一条：{@code last_error_seq == act_seq}。两个漏洞：</p>
 * <ol>
 *   <li>排队期间每次 {@code get_state} 都会记一条<b>读状态</b>的 NOT_READY（
 *       {@code get_state → NOT_READY}），读工具不推进 {@code act_seq}，所以这条读错误永远长得像
 *       「刚才那次 act 的错」；</li>
 *   <li>排队的那条动作真的落地之后，世界已经往前走了（{@code state_version} 变了），但
 *       {@code act_seq} 没变，旧错误继续被当成新鲜的。</li>
 * </ol>
 *
 * <p>所以新规则三条同时成立才算 fresh：序号相等 + 是动作的错误（不是读）+ 从此世界没再动过。
 * 全部是纯函数，时间戳由调用方注入（{@code StateBuilder#lastDecisionChangeAt()} 就是「世界动了」
 * 的时刻），这里把整张表钉死。</p>
 */
public class ErrorFreshnessTest {

    private static final long T0 = 1_000_000L;

    @Test
    public void anActErrorFromTheLatestActIsFresh() {
        assertTrue(ErrorFreshness.isFreshFor(7L, 7L, "proceed → ILLEGAL_ACTION", T0 + 50L, T0));
    }

    @Test
    public void anOlderActErrorIsHistory() {
        assertFalse("序号对不上就是陈年旧账",
                ErrorFreshness.isFreshFor(6L, 7L, "proceed → ILLEGAL_ACTION", T0 + 50L, T0));
    }

    /** 读状态失败不是动作的错误：它解释的是「这次读为什么没内容」。 */
    @Test
    public void aReadRefusalIsNeverAnActError() {
        assertFalse(ErrorFreshness.isFreshFor(7L, 7L, "get_state → NOT_READY", T0 + 50L, T0));
        assertFalse(ErrorFreshness.isFreshFor(7L, 7L, "legal_actions → NOT_READY", T0 + 50L, T0));
        assertFalse(ErrorFreshness.isFreshFor(7L, 7L, "get_state → GAME_DEAD", T0 + 50L, T0));
    }

    @Test
    public void readRefusalsAreRecognisedByTheirSourceNotTheirCode() {
        assertTrue(ErrorFreshness.isReadRefusal("get_state → NOT_READY"));
        assertTrue(ErrorFreshness.isReadRefusal("legal_actions → INTERNAL"));
        assertFalse(ErrorFreshness.isReadRefusal("proceed → ILLEGAL_ACTION"));
        assertFalse(ErrorFreshness.isReadRefusal("act → BAD_REQUEST"));
        // CrashGuard.report 的形状（"where: throwable"）不是一次读拒绝。
        assertFalse(ErrorFreshness.isReadRefusal("refreshMemory: java.lang.NullPointerException"));
        assertFalse(ErrorFreshness.isReadRefusal("get_state"));
        assertFalse(ErrorFreshness.isReadRefusal(null));
    }

    /** 动作落地 / 界面推进之后，旧错误就是历史 —— 这正是 queued 那次「十几秒还是 true」。 */
    @Test
    public void theWorldMovingSinceTheErrorMakesItHistory() {
        assertFalse("世界在错误之后动过",
                ErrorFreshness.isFreshFor(7L, 7L, "proceed → ILLEGAL_ACTION", T0 + 50L, T0 + 200L));
        assertTrue("错误与世界同毫秒算新的（<= 而不是 <）",
                ErrorFreshness.isFreshFor(7L, 7L, "proceed → ILLEGAL_ACTION", T0, T0));
    }

    @Test
    public void noErrorAtAllIsNotFresh() {
        assertFalse(ErrorFreshness.isFreshFor(0L, 0L, null, 0L, 0L));
        assertFalse(ErrorFreshness.isFreshFor(0L, 0L, "", 0L, 0L));
    }

    @Test
    public void theStateLayerExposesWhenTheWorldLastMoved() {
        // 时钟来源：StateBuilder 每次 state_version++ 都会写这个时刻；没有 tick 过时是 0。
        assertTrue(StateBuilder.lastDecisionChangeAt() >= 0L);
    }
}
