package spirelink.util;

/**
 * Last line of defence for the render thread: logs at most once a second, remembers the message for
 * hints.last_error, writes the stack trace to spirelink-debug.log, and never rethrows. A thrown
 * exception here kills CardCrawlGame.update and the window vanishes without a trace.
 */
public final class CrashGuard {

    private static final long MIN_INTERVAL_MS = 1000L;
    private static volatile long lastLogAt;
    private static volatile String lastError;
    private static volatile long lastErrorAt;
    /** Number of action calls so far; lets hints say whether last_error belongs to the latest act. */
    private static volatile long actionSeq;
    private static volatile long lastErrorSeq;
    /** Non-null once the game's own update/render loop has thrown (crash screen path). */
    private static volatile String gameCrash;

    private CrashGuard() {
    }

    public static void report(String where, Throwable t) {
        lastError = where + ": " + t;
        lastErrorAt = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        if (now - lastLogAt < MIN_INTERVAL_MS) {
            return;
        }
        lastLogAt = now;
        Diag.warn("mod 内部异常（已拦下，未抛进游戏主循环） " + where + ": " + t);
        try {
            // Same file as ModLog (data dir), rotated so a crash loop cannot fill the disk.
            java.io.File logFile = DataPaths.debugLogFile(
                    DataPaths.resolveDataDir(CrashGuard.class));
            DataPaths.rotateIfNeeded(logFile);
            java.io.PrintWriter writer = new java.io.PrintWriter(
                    new java.io.FileWriter(logFile, true));
            writer.println(new java.util.Date() + " [" + where + "] " + t);
            t.printStackTrace(writer);
            writer.close();
        } catch (Throwable ignored) {
            // logging must never fail the caller
        }
    }

    /** Records the last rejected action code so {@code hints.last_error} can explain a refusal. */
    public static void noteActionError(String code, String type) {
        lastError = (type == null ? "act" : type) + " → " + code;
        lastErrorAt = System.currentTimeMillis();
        lastErrorSeq = actionSeq;
    }

    /** Called once per {@code act} (success or failure) so stale errors can be recognised. */
    public static long nextActionSeq() {
        actionSeq++;
        return actionSeq;
    }

    public static long actionSeq() {
        return actionSeq;
    }

    public static long lastErrorSeq() {
        return lastErrorSeq;
    }

    /** The game's own update/render loop threw: the window may be stuck on a crash screen. */
    public static void noteGameCrash(Throwable t) {
        gameCrash = String.valueOf(t);
        lastError = "RENDER_CRASHED: " + t;
        lastErrorAt = System.currentTimeMillis();
        lastErrorSeq = actionSeq;
    }

    public static String gameCrash() {
        return gameCrash;
    }

    public static String lastError() {
        return lastError;
    }

    public static long lastErrorAt() {
        return lastErrorAt;
    }

    /** Installs handlers on the current (render) thread and as the default, best effort. */
    public static void installHandlers() {
        try {
            Thread.currentThread().setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable t) {
                    report("render-thread(" + thread.getName() + ")", t);
                }
            });
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable t) {
                    report("thread(" + thread.getName() + ")", t);
                }
            });
        } catch (Throwable ignored) {
            // handler installation is best effort
        }
    }
}
