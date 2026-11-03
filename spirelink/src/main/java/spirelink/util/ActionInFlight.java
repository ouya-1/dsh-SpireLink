package spirelink.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The one place that knows an action was answered {@code queued} and has not landed yet.
 *
 * <p>Two callers share it: the act gate refuses new actions, and the state builder reports
 * {@code ready:false} so the agent never sees "stable state" while an action is still in flight.
 * The timeout is wall clock on purpose: a screen that does not change the signature would keep a
 * version-based guard alive forever (that is how a single {@code skip_card} froze a whole run).
 *
 * <p><b>Issue #56: the release must not depend on somebody polling.</b> The guard used to expire
 * lazily - {@link #blocked(int)} only looked at the clock when a caller happened to reach it, and
 * the two callers that matter (get_state / legal_actions) reached it only <em>after</em> their own
 * main-thread call returned. When the main thread is wedged - which is exactly when an action stays
 * queued - every poll burns its whole 10 s queue timeout first, so the release landed 10-22 s late
 * and the log said {@code 已等22008ms} while {@link #TIMEOUT_MS} is 3000. The guard now carries a
 * real deadline plus a daemon watchdog that clears it at that deadline, so the wall clock is the
 * wall clock no matter what the callers do; {@link #blocked(int)} keeps the same check as a
 * belt-and-braces fallback (and still clears immediately when the state version moves on).
 */
public final class ActionInFlight {

    /** Wall-clock budget for one queued action before the guard gives up loudly. */
    public static final long TIMEOUT_MS = 3000L;

    private static final Object LOCK = new Object();

    private static volatile int version = -1;
    private static volatile long startedAt;
    private static volatile long deadlineAt;
    private static volatile String type = "";

    /** Overridable only from unit tests in this package; production always uses {@link #TIMEOUT_MS}. */
    private static volatile long timeoutMs = TIMEOUT_MS;
    private static volatile ScheduledExecutorService watchdog;
    private static volatile ScheduledFuture<?> expiryTask;

    private ActionInFlight() {
    }

    /**
     * Records that an action was answered {@code queued} on top of {@code stateVersion}.
     *
     * <p>Idempotent per queued action: the deadline restarts, and the watchdog fires
     * {@code TIMEOUT_MS} later even if nobody ever calls {@link #blocked(int)} again.
     */
    public static void mark(int stateVersion, String actionType) {
        long now = System.currentTimeMillis();
        long budget = Math.max(1L, timeoutMs);
        synchronized (LOCK) {
            version = stateVersion;
            startedAt = now;
            deadlineAt = now + budget;
            type = actionType == null ? "" : actionType;
            cancelTaskLocked();
            expiryTask = watchdog().schedule(new Runnable() {
                @Override
                public void run() {
                    expireIfStale(System.currentTimeMillis());
                }
            }, budget + 1L, TimeUnit.MILLISECONDS);
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            version = -1;
            type = "";
            cancelTaskLocked();
        }
    }

    public static String type() {
        return type;
    }

    public static long elapsedMs() {
        return version < 0 ? 0L : System.currentTimeMillis() - startedAt;
    }

    /** Milliseconds until the wall clock releases the guard; 0 when nothing is pending. */
    public static long retryAfterMs() {
        if (version < 0) {
            return 0L;
        }
        long remaining = deadlineAt - System.currentTimeMillis();
        return remaining > 0L ? remaining : 0L;
    }

    /**
     * The version the in-flight action started from, or -1 when nothing is pending (also clears the
     * guard once the state advanced past it or the wall-clock timeout expired).
     */
    public static int blocked(int currentVersion) {
        synchronized (LOCK) {
            int pending = version;
            if (pending < 0) {
                return -1;
            }
            if (currentVersion > pending) {
                // The action landed: the version moved past the one it was queued on.
                version = -1;
                cancelTaskLocked();
                return -1;
            }
            long now = System.currentTimeMillis();
            if (now < deadlineAt) {
                return pending;
            }
            releaseLocked(now);
            return -1;
        }
    }

    /**
     * Wall-clock release, callable from anywhere (the watchdog, tests, or a poll path that wants to
     * pump the guard without asking for a version).
     *
     * @return true when this call released a pending guard
     */
    public static boolean releaseIfExpired() {
        return expireIfStale(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ internals

    private static boolean expireIfStale(long now) {
        synchronized (LOCK) {
            if (version < 0 || now < deadlineAt) {
                return false;
            }
            releaseLocked(now);
            return true;
        }
    }

    /** Logs the release once and forgets the pending action. Caller holds {@link #LOCK}. */
    private static void releaseLocked(long now) {
        Diag.warn("ACTION_IN_FLIGHT 墙钟超时解除（已等 " + (now - startedAt) + "ms，type=" + type
                + "，version=" + version + "）");
        version = -1;
        cancelTaskLocked();
    }

    private static void cancelTaskLocked() {
        ScheduledFuture<?> task = expiryTask;
        expiryTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private static ScheduledExecutorService watchdog() {
        ScheduledExecutorService service = watchdog;
        if (service != null) {
            return service;
        }
        synchronized (LOCK) {
            if (watchdog == null) {
                watchdog = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "spirelink-inflight-watchdog");
                        // Daemon: the game must be able to exit without joining this thread.
                        thread.setDaemon(true);
                        return thread;
                    }
                });
            }
            return watchdog;
        }
    }

    // ------------------------------------------------------------------ test seams

    /** Test hook: shortens (or restores) the wall clock so a unit test need not wait 3 s. */
    static void setTimeoutForTest(long ms) {
        timeoutMs = ms <= 0L ? TIMEOUT_MS : ms;
    }

    /** Test hook: forgets any pending action and restores the production wall clock. */
    static void resetForTest() {
        synchronized (LOCK) {
            version = -1;
            type = "";
            cancelTaskLocked();
            timeoutMs = TIMEOUT_MS;
        }
    }
}
