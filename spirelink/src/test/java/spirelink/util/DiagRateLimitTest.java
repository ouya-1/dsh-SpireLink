package spirelink.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #100（QA 第 6 轮）：地图 {@code reachable=[]} 那一拍日志刷屏。
 *
 * <p>现场：9 秒 1051 行 / 612 KB。根因不是「每次读状态」，而是**每帧**：{@code receivePostUpdate}
 * → {@code StateBuilder.tickOnMainThread()} → {@code computeSignature()} → {@code
 * ActionPlanner.actionsFor()} → {@code map()}，planner 每次都打两条带 {@code mapDump()} 的 WARN
 * （~580 B/条，60 fps ≈ 120 行/秒）。
 *
 * <p>这一层的修法是 sink 限流：{@link Diag#warn} 对**同一条消息原文**开一个 10 秒窗口，窗口内只打
 * 第一遍，其余只计数；窗口结束时补一行汇总（条数 + 原文）。所以「没丢信息」仍可验证，只是被折叠。
 *
 * <p>钉住的三件事：
 * <ol>
 *   <li>判定是纯函数（{@link Diag#dueAgain}）：现在几点、上次何时、窗口内第几次 → 打不打；</li>
 *   <li>汇总文案是纯函数（{@link Diag#suppressedSummary}）：条数与原文都必须在；</li>
 *   <li>接线只用注入的时钟走一遍真实 {@code ModLog}（{@link TemporaryFolder} + 调试日志），
 *       顺带钉住「不同消息不互相影响」「窗口滚动后会再打一次」「info 完全不过限流」。</li>
 * </ol>
 *
 * <p>ERROR/FATAL 不经过 {@link Diag}（{@code ModLog.error} / log4j 直写），所以不可能被这里吞掉；
 * 本测试只需钉住 WARN 与 INFO 的边界。
 */
public class DiagRateLimitTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** The exact line a stuck map frame repeats; the length is why the limit matters. */
    private static final String STUCK_MAP =
            "地图规划为空（不做兜底猜测）: map_rows=15 curr=(3,14) curr_taken=true "
                    + "[(0,14) taken=false room=true conn=false winged=false] "
                    + "[(1,14) taken=false room=true conn=false winged=false]";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File dataDir;
    private FakeClock clock;

    /** Test seam: a clock the test walks by hand, so a 10 s window costs no seconds. */
    private static final class FakeClock implements Diag.Clock {
        private long at;

        FakeClock(long start) {
            this.at = start;
        }

        @Override
        public long now() {
            return at;
        }

        void advance(long millis) {
            at += millis;
        }
    }

    @Before
    public void setUp() throws Exception {
        dataDir = folder.newFolder("spirelink-data");
        Diag.resetForTests();
        clock = new FakeClock(1_000_000L);
        Diag.setClockForTests(clock);
        // debug file on: every line Diag hands to the ModLog lands in spirelink-debug.log, so the
        // assertion is on what a human would actually read in the log.
        Diag.setLog(new ModLog(dataDir, true));
    }

    @After
    public void tearDown() {
        Diag.setLog(null);
        Diag.resetForTests();
        Diag.setClockForTests(null);
    }

    private List<String> lines() throws Exception {
        File file = DataPaths.debugLogFile(dataDir);
        if (!file.isFile()) {
            return new ArrayList<String>();
        }
        return new ArrayList<String>(Files.readAllLines(file.toPath(), UTF8));
    }

    private List<String> warnings() throws Exception {
        List<String> out = new ArrayList<String>();
        for (String line : lines()) {
            if (line.contains(" WARN ")) {
                out.add(line);
            }
        }
        return out;
    }

    private int warningsContaining(String needle) throws Exception {
        int count = 0;
        for (String line : warnings()) {
            if (line.contains(needle)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ 纯函数：打不打

    /** 同一窗口内只打第一遍；窗口到点才打第二遍；时钟往回跳时宁可多打（不许吞）。 */
    @Test
    public void pureDecisionIsFirstInWindowThenNextWindow() {
        long window = Diag.WARN_WINDOW_MS;

        assertTrue("没有窗口（第 0 次）= 打",
                Diag.dueAgain(5_000L, 0L, 0L, window));
        assertFalse("刚打完的那一拍不算又要打", Diag.dueAgain(5_000L, 5_000L, 1L, window));
        assertFalse("窗口内第二次 = 只计数", Diag.dueAgain(5_001L, 5_000L, 1L, window));
        assertFalse("窗口最后 1ms 仍算窗口内", Diag.dueAgain(14_999L, 5_000L, 1L, window));
        assertTrue("窗口整 10 秒到点 = 再打一次", Diag.dueAgain(15_000L, 5_000L, 1L, window));
        assertTrue("过了窗口也打", Diag.dueAgain(60_000L, 5_000L, 1L, window));
        assertTrue("时钟往回跳（NTP 校时）不许吞",
                Diag.dueAgain(4_000L, 5_000L, 1L, window));
    }

    /** 汇总文案必须能看出「丢了几条」和「丢的是哪条」——这就是「没丢信息」的可验证形式。 */
    @Test
    public void summaryCarriesTheCountTheWindowAndTheOriginalText() {
        String summary = Diag.suppressedSummary(37L, 10_000L, STUCK_MAP);

        assertTrue(summary, summary.contains("10 秒"));
        assertTrue(summary, summary.contains("37"));
        assertTrue(summary, summary.contains("已抑制"));
        assertTrue("原文必须在（否则等于丢信息）: " + summary, summary.contains(STUCK_MAP));
    }

    // ------------------------------------------------------------------ 接线：真实 ModLog

    /** 一串重复只留第一行；到窗口结束才换成「一行汇总 + 一行原文」。 */
    @Test
    public void repeatsCollapseToOneLinePerWindow() throws Exception {
        Diag.warn(STUCK_MAP);
        assertEquals("第一遍必须原样出现", 1, warningsContaining("地图规划为空"));

        // 60 fps × 9 秒：现场那 1051 行。
        for (int i = 0; i < 540; i++) {
            clock.advance(16L);
            Diag.warn(STUCK_MAP);
        }
        assertEquals("窗口内只打第一遍", 1, warningsContaining("地图规划为空"));

        clock.advance(Diag.WARN_WINDOW_MS);
        Diag.warn(STUCK_MAP);

        List<String> warnings = warnings();
        assertEquals("窗口结束 = 汇总 + 新窗口的第一遍", 3, warnings.size());
        assertTrue("汇总行要报出抑制条数: " + warnings.get(1),
                warnings.get(1).contains("又出现 540 次，已抑制"));
        assertTrue("原文仍在汇总行里: " + warnings.get(1), warnings.get(1).contains(STUCK_MAP));
        assertTrue("窗口滚动后必须再打一次原文", warnings.get(2).contains(STUCK_MAP));
        assertFalse("新窗口的第一遍不是汇总", warnings.get(2).contains("已抑制"));
    }

    /** 不同消息各自一个窗口：一条刷屏不许把另一条的正常告警吃掉。 */
    @Test
    public void differentMessagesDoNotSuppressEachOther() throws Exception {
        Diag.warn("警告 A");
        Diag.warn("警告 B");
        assertEquals(2, warnings().size());

        for (int i = 0; i < 20; i++) {
            clock.advance(10L);
            Diag.warn("警告 A");
        }

        assertEquals("B 只有一条，且不受 A 的窗口影响", 1, warningsContaining("警告 B"));
        assertEquals("A 仍是第一遍那一条", 1, warningsContaining("警告 A"));
        assertEquals(2, warnings().size());
    }

    /** 窗口结束后即使这条消息再也不出现，下一次任何 WARN 也要把它的汇总补出来。 */
    @Test
    public void closedWindowIsReportedByTheNextUnrelatedWarning() throws Exception {
        Diag.warn("会刷屏的那条");
        for (int i = 0; i < 5; i++) {
            clock.advance(100L);
            Diag.warn("会刷屏的那条");
        }

        clock.advance(Diag.WARN_WINDOW_MS);
        Diag.warn("另一条完全不同的话");

        List<String> warnings = warnings();
        assertEquals(3, warnings.size());
        assertTrue("先补旧窗口的汇总: " + warnings.get(1), warnings.get(1).contains("又出现 5 次，已抑制"));
        assertTrue(warnings.get(1).contains("会刷屏的那条"));
        assertTrue("然后是这条新消息本身: " + warnings.get(2), warnings.get(2).contains("另一条完全不同的话"));
    }

    /** 连续两个窗口：第二个窗口没有重复，就不该凭空多出汇总行。 */
    @Test
    public void secondWindowWithoutRepeatsLeavesNoSummary() throws Exception {
        Diag.warn(STUCK_MAP);
        clock.advance(Diag.WARN_WINDOW_MS);
        Diag.warn(STUCK_MAP);
        clock.advance(Diag.WARN_WINDOW_MS);
        Diag.warn(STUCK_MAP);

        List<String> warnings = warnings();
        assertEquals("三个窗口各打一遍，没有任何抑制", 3, warnings.size());
        for (String line : warnings) {
            assertFalse("没有重复就不许有汇总: " + line, line.contains("已抑制"));
        }
    }

    /** info 完全不过限流：同一句话 5 次就是 5 行（限流只针对 WARN）。 */
    @Test
    public void infoIsNeverThrottled() throws Exception {
        for (int i = 0; i < 5; i++) {
            Diag.info("同一句 INFO");
        }
        int infoLines = 0;
        for (String line : lines()) {
            if (line.contains(" INFO ") && line.contains("同一句 INFO")) {
                infoLines++;
            }
        }
        assertEquals("INFO 不许被限流", 5, infoLines);

        Diag.warn("同一句 INFO");
        assertEquals("WARN 的第一遍照打（和 INFO 不同通道）", 1, warningsContaining("同一句 INFO"));
    }

    /** 64 条不同消息的上限：老窗口被挤掉时也要把计数补出来，不许悄悄丢。 */
    @Test
    public void trackedMessageLimitKeepsTheOldestSummary() throws Exception {
        Diag.warn("挤掉我 #0");
        clock.advance(10L);
        Diag.warn("挤掉我 #0");
        for (int i = 1; i <= Diag.MAX_TRACKED_MESSAGES; i++) {
            clock.advance(1L);
            Diag.warn("填充消息 " + i);
        }

        boolean reported = false;
        for (String line : warnings()) {
            if (line.contains("挤掉我 #0") && line.contains("已抑制")) {
                reported = true;
            }
        }
        assertTrue("被上限挤掉的窗口也要留下汇总", reported);
    }
}
