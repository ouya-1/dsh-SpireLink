package spirelink.util;

/**
 * Static hook onto the mod's {@link ModLog} so game-side helpers (planners, patches) can log
 * without threading a logger instance through every constructor. Set once by {@code SpireLink}.
 *
 * <p><b>WARN 限流（issue #100）。</b>同一条 WARN 原文在 {@link #WARN_WINDOW_MS} 内只打第一遍；其后
 * 的重复只计数，窗口结束时补一行汇总（含被抑制的条数与消息原文），所以「没丢信息」这条性质仍然成立，
 * 只是被折叠成「每 10 秒一行」。现场：地图 {@code reachable=[]} 那一拍，{@code get_state} 与帧 tick
 * 每帧都会向 planner 要一次动作表，planner 每次打两条带 {@code mapDump()} 的 WARN，9 秒写掉 1051 行 /
 * 612 KB。
 *
 * <p>限流只作用于 WARN：ERROR/FATAL 走 {@link ModLog#error} 或 log4j，永远不经过这里；
 * {@link #info(String)} 不打折也不计数。判定本身是纯函数（{@link #dueAgain}），窗口汇总文案也是
 * （{@link #suppressedSummary}），时钟可从测试注入（{@link #setClockForTests}）。
 */
public final class Diag {

    private static volatile ModLog log;

    private Diag() {
    }

    public static void setLog(ModLog modLog) {
        log = modLog;
    }

    public static void info(String message) {
        ModLog current = log;
        if (current != null) {
            current.info(message);
        }
    }

    /**
     * Writes a WARN line. Identical text repeats within {@link #WARN_WINDOW_MS} are counted, not
     * printed; one summary line reports them when the window closes (see the class comment).
     *
     * <p>A {@code null} message cannot be keyed on, so it is passed through unchanged (pre-#100
     * behaviour, and never worth inventing a rule for).
     */
    public static void warn(String message) {
        ModLog current = log;
        if (current == null) {
            return;
        }
        if (message == null) {
            current.warn(null);
            return;
        }
        for (String line : planWarn(message, clock.now())) {
            current.warn(line);
        }
    }

    // ---------------------------------------------------------------- WARN rate limit (#100)

    /** How long the first occurrence of one message keeps its repeats off the log. */
    static final long WARN_WINDOW_MS = 10_000L;

    /** Distinct messages tracked at once. This is a limiter, not a history: the oldest is dropped. */
    static final int MAX_TRACKED_MESSAGES = 64;

    /** Test seam: where "now" comes from. The game always uses the wall clock. */
    interface Clock {
        long now();
    }

    private static final class WallClock implements Clock {
        static final WallClock INSTANCE = new WallClock();

        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    }

    private static volatile Clock clock = WallClock.INSTANCE;

    /** Points the limiter at a fake clock; {@code null} restores the wall clock. */
    static void setClockForTests(Clock testClock) {
        clock = testClock == null ? WallClock.INSTANCE : testClock;
    }

    /**
     * Pure decision: may this message be printed again?
     *
     * @param now            当前时间（毫秒）
     * @param firstSeenAt    本条消息在当前窗口里第一次打印的时间（没有窗口时无意义）
     * @param printedInWindow 本条消息在当前窗口里已经打印过几次（0 = 还没有窗口）
     * @param windowMs       窗口长度
     * @return true = 打；false = 只计数
     */
    static boolean dueAgain(long now, long firstSeenAt, long printedInWindow, long windowMs) {
        if (printedInWindow <= 0L) {
            return true;                        // 窗口还没开：第一遍一定要打
        }
        if (now < firstSeenAt) {
            return true;                        // 时钟往回跳（NTP 校时）：宁可多打，不许吞
        }
        return now - firstSeenAt >= windowMs;   // 窗口滚过去了：再打一遍
    }

    /**
     * Pure text: the single line a closed window leaves behind. It names what was dropped and the
     * original message, so a reader can still see what kept repeating.
     */
    static String suppressedSummary(long suppressed, long windowMs, String message) {
        return "WARN 限流（issue #100）：同一条消息 " + (windowMs / 1000L) + " 秒内又出现 "
                + suppressed + " 次，已抑制；原文=" + message;
    }

    /** One message's open window: when it opened, how often it printed, how many repeats dropped. */
    private static final class Window {
        long firstAt;
        long printed;
        long suppressed;
    }

    private static final Object WINDOW_LOCK = new Object();
    private static final java.util.LinkedHashMap<String, Window> windows =
            new java.util.LinkedHashMap<String, Window>();

    /**
     * The lines {@code warn()} writes right now, and the state transition that goes with them.
     *
     * <p>Split out so the limiter is testable without a log file: {@code out} is usually one line
     * (this message) or two (the summary of a window that just closed, then this message), and an
     * empty list while the message is inside its window.
     *
     * @param now injected so a test can walk the clock instead of sleeping
     */
    static java.util.List<String> planWarn(String message, long now) {
        java.util.List<String> out = new java.util.ArrayList<String>(2);
        synchronized (WINDOW_LOCK) {
            closeExpiredWindows(now, out);
            Window window = windows.get(message);
            if (window != null && !dueAgain(now, window.firstAt, window.printed, WARN_WINDOW_MS)) {
                window.suppressed++;
                return out;
            }
            window = new Window();
            window.firstAt = now;
            window.printed = 1L;
            windows.put(message, window);
            trimOldest(out);
            out.add(message);
        }
        return out;
    }

    /** Closes (and reports) every window whose 10 s is up: nothing else may write the summary. */
    private static void closeExpiredWindows(long now, java.util.List<String> out) {
        java.util.Iterator<java.util.Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Window> entry = it.next();
            Window window = entry.getValue();
            if (!dueAgain(now, window.firstAt, window.printed, WARN_WINDOW_MS)) {
                continue;
            }
            it.remove();
            if (window.suppressed > 0L) {
                out.add(suppressedSummary(window.suppressed, WARN_WINDOW_MS, entry.getKey()));
            }
        }
    }

    /** Bounds memory: drop the oldest window, reporting it first so its count is not lost. */
    private static void trimOldest(java.util.List<String> out) {
        while (windows.size() > MAX_TRACKED_MESSAGES) {
            java.util.Iterator<java.util.Map.Entry<String, Window>> it = windows.entrySet().iterator();
            if (!it.hasNext()) {
                return;
            }
            java.util.Map.Entry<String, Window> oldest = it.next();
            it.remove();
            Window window = oldest.getValue();
            if (window.suppressed > 0L) {
                out.add(suppressedSummary(window.suppressed, WARN_WINDOW_MS, oldest.getKey()));
            }
        }
    }

    /** Test seam: forget every window (the clock is restored by {@link #setClockForTests}). */
    static void resetForTests() {
        synchronized (WINDOW_LOCK) {
            windows.clear();
        }
    }
}
