package spirelink.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issue #56: the {@code ACTION_IN_FLIGHT} guard must release on the wall clock, not on the next
 * caller.
 *
 * <p>Real-machine evidence: {@code 03:29:45.731 WARN ACTION_IN_FLIGHT 墙钟超时解除（22008ms，
 * type=choose_map_node，version=42）} while {@link ActionInFlight#TIMEOUT_MS} is 3000. The guard was
 * only ever released <em>inside</em> {@link ActionInFlight#blocked(int)}, and both callers reached
 * that call only after their own main-thread request had returned - with a wedged main thread each
 * such call first burns its 10 s queue timeout, so the 3 s release landed at 10-22 s.
 *
 * <p>These tests pin the new rule: a real deadline plus a daemon watchdog, so the guard expires even
 * if nobody ever asks again, and {@code blocked()} keeps the same decision as a fallback.
 */
public class ActionInFlightTest {

    @Before
    public void setUp() {
        ActionInFlight.resetForTest();
    }

    @After
    public void tearDown() {
        ActionInFlight.resetForTest();
    }

    /** 版本推进 = 动作落地：立即解除，不用等墙钟。 */
    @Test
    public void versionAdvanceReleasesImmediately() {
        ActionInFlight.mark(42, "choose_map_node");
        assertEquals(42, ActionInFlight.blocked(42));
        assertEquals("版本推进必须立刻解除", -1, ActionInFlight.blocked(43));
        assertEquals(-1, ActionInFlight.blocked(42));
        assertEquals(0L, ActionInFlight.elapsedMs());
    }

    /**
     * 关键回归：没有任何调用者时，看门狗在墙钟到点自己解除。
     *
     * <p>旧实现里 {@code elapsedMs()} 会一直涨（日志里的 22008ms 就是这么来的）；新实现里后台
     * 线程清掉守卫，{@code elapsedMs()} 回到 0——而这条测试**一次都没有**调用过 blocked()。
     */
    @Test
    public void watchdogReleasesWithoutAnyCaller() throws Exception {
        ActionInFlight.setTimeoutForTest(120L);
        ActionInFlight.mark(42, "choose_map_node");
        assertEquals(42, ActionInFlight.blocked(42));

        // 等墙钟（3 倍余量）。注意不能用 elapsedMs()==0 做轮询条件：刚 mark 完的那一毫秒也是 0，
        // 会把「还没到点」误判成「已解除」。这段时间里一次 blocked() 都不调用。
        Thread.sleep(400L);
        assertEquals("墙钟到点必须由看门狗解除（无需任何调用者）", 0L, ActionInFlight.elapsedMs());
        assertEquals("解除之后不许再拦", -1, ActionInFlight.blocked(42));
    }

    /** 兜底路径：即便看门狗被拖延，blocked() 自己的墙钟判据也不会再拦。 */
    @Test
    public void lazyCheckAlsoReleasesAtTheDeadline() throws Exception {
        ActionInFlight.setTimeoutForTest(40L);
        ActionInFlight.mark(7, "end_turn");
        Thread.sleep(90L);
        assertEquals(-1, ActionInFlight.blocked(7));
    }

    /** {@code retry_after_ms} 的来源：剩余时间在 (0, TIMEOUT] 之间，解除后归零。 */
    @Test
    public void retryAfterTracksTheWallClock() throws Exception {
        ActionInFlight.setTimeoutForTest(400L);
        ActionInFlight.mark(3, "proceed");
        long remaining = ActionInFlight.retryAfterMs();
        assertTrue("剩余应 <= 400ms，实际 " + remaining, remaining > 0L && remaining <= 400L);
        Thread.sleep(120L);
        assertTrue("墙钟必须真的在走", ActionInFlight.retryAfterMs() < remaining);
        ActionInFlight.clear();
        assertEquals(0L, ActionInFlight.retryAfterMs());
    }

    /** 生产值是 3 秒，且不会被测试钩子改坏（{@code McpContractTest} 从另一侧也钉了一遍）。 */
    @Test
    public void productionTimeoutIsThreeSeconds() {
        assertEquals(3000L, ActionInFlight.TIMEOUT_MS);
        ActionInFlight.mark(1, "proceed");
        assertTrue(ActionInFlight.retryAfterMs() <= ActionInFlight.TIMEOUT_MS);
        assertEquals(1, ActionInFlight.blocked(1));
        ActionInFlight.clear();
        assertEquals(-1, ActionInFlight.blocked(1));
    }

    /** 再次 mark 会重置墙钟（同一个动作再排队一次不该继承上一次的剩余时间）。 */
    @Test
    public void remarkRestartsTheWallClock() throws Exception {
        ActionInFlight.setTimeoutForTest(150L);
        ActionInFlight.mark(5, "proceed");
        Thread.sleep(100L);
        ActionInFlight.mark(5, "proceed");
        assertEquals("重新排队必须重新计时", 5, ActionInFlight.blocked(5));
        assertTrue(ActionInFlight.retryAfterMs() > 50L);
    }
}
