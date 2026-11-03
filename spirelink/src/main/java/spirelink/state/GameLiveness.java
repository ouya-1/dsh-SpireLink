package spirelink.state;

/**
 * Issue #69: "the game is already dead" as a decision, not as a guess.
 *
 * <p>The MCP server runs on its own thread and keeps answering long after the game's own
 * update/render loop has stopped, so every waiting state looks identical from the outside: BUSY for
 * a slow animation, BUSY <em>forever</em> for a dead process. That is what made the agent poll until
 * its token budget ran out (#55 / #69).
 *
 * <p>Two facts are enough, and both already exist in {@code spirelink.util}:
 * <ul>
 *   <li>{@link spirelink.util.CrashGuard#gameCrash()} - the game's own loop threw. Vanilla calls
 *       {@code Gdx.app.exit()} right after, so the window is gone even though we still answer.
 *       {@code render_crashed} is this fact, verbatim, and it is sticky: "the loop has crashed".</li>
 *   <li>{@link spirelink.util.RuntimeProbe#frames()} - the frame counter. A frozen counter means no
 *       frame is being rendered any more.</li>
 * </ul>
 *
 * <p><b>The rule:</b> a crash alone is not death (vanilla catches exceptions in some paths and the
 * loop keeps going - reporting that as "restart the game" would be a lie), and a frozen frame
 * counter alone is not death either (paused window, minimised window, a long GC, a modal dialog).
 * Only {@code crash && stalled >= threshold} is terminal.
 *
 * <p>Deliberately pure and clock-injected: every branch below is unit-tested without sleeping.
 */
public final class GameLiveness {

    /** How long the frame counter must stay frozen before a captured crash counts as death. */
    public static final long STALL_MS = 5000L;

    /** The one sentence a terminal response must carry: restarting is the only way out. */
    public static final String DEAD_HINT =
            "本局已崩溃（游戏主线程已停止响应，重启前不会恢复），请人类重启游戏后再继续。";

    /** Reason codes for {@link Verdict#reason}; kept short so they can travel in meta. */
    public static final String REASON_OK = "ok";
    public static final String REASON_STALLED_LOOP_ALIVE = "stalled_no_crash";
    public static final String REASON_CRASHED_LOOP_ALIVE = "render_crashed_but_looping";
    public static final String REASON_DEAD = "render_crashed_and_stalled";

    /** Everything a verdict needs from the outside world; injected so tests can fake time. */
    public interface Clock {
        /** Wall clock in ms, monotonic enough for a stall measurement. */
        long nowMs();

        /** {@link spirelink.util.RuntimeProbe#frames()}: frames rendered so far. */
        long frames();

        /** Milliseconds since the last frame tick, used to seed the very first sample. */
        long msSinceFrame();

        /** {@link spirelink.util.CrashGuard#gameCrash()}; null while the loop never threw. */
        String crash();
    }

    /** One immutable answer: "is this game instance dead, and why do we think so". */
    public static final class Verdict {
        /** The game's own loop threw at some point. Sticky; the documented meaning of render_crashed. */
        public final boolean renderCrashed;
        /** Terminal: crashed *and* the frame loop has stopped advancing. */
        public final boolean dead;
        /** How long the frame counter has been frozen, in ms. */
        public final long stalledMs;
        /** Crash marker as recorded by CrashGuard, or null. */
        public final String crash;
        /** One of the REASON_* constants. */
        public final String reason;

        public Verdict(boolean renderCrashed, boolean dead, long stalledMs, String crash, String reason) {
            this.renderCrashed = renderCrashed;
            this.dead = dead;
            this.stalledMs = stalledMs;
            this.crash = crash;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return "Verdict{renderCrashed=" + renderCrashed + ", dead=" + dead
                    + ", stalledMs=" + stalledMs + ", reason=" + reason + "}";
        }
    }

    private GameLiveness() {
    }

    // ------------------------------------------------------------------ the rule (pure)

    /** {@code render_crashed}: has the game's own loop thrown? Sticky by definition. */
    public static boolean renderCrashed(String crash) {
        return crash != null;
    }

    /** Terminal: a captured crash plus a frame counter that has not moved for {@code stallMs}. */
    public static boolean isDead(String crash, long stalledMs, long stallMs) {
        return crash != null && stalledMs >= stallMs;
    }

    /**
     * The whole rule, with nothing hidden: no clock, no statics, no I/O.
     *
     * @param crash     crash marker from CrashGuard, null when the loop never threw
     * @param stalledMs how long the frame counter has been frozen
     * @param stallMs   threshold for "the loop is not coming back"
     */
    public static Verdict decide(String crash, long stalledMs, long stallMs) {
        long stalled = stalledMs < 0L ? 0L : stalledMs;
        long floor = stallMs < 0L ? 0L : stallMs;
        boolean crashed = renderCrashed(crash);
        boolean dead = isDead(crash, stalled, floor);
        String reason;
        if (dead) {
            reason = REASON_DEAD;
        } else if (crashed) {
            // Vanilla caught it and the loop survived: read-only, and act already refuses, but this
            // is NOT "this run is over".
            reason = REASON_CRASHED_LOOP_ALIVE;
        } else if (stalled >= floor) {
            reason = REASON_STALLED_LOOP_ALIVE;
        } else {
            reason = REASON_OK;
        }
        return new Verdict(crashed, dead, stalled, crash, reason);
    }

    // ------------------------------------------------------------------ sampling (clock-injected)

    /**
     * Turns successive clock readings into {@link Verdict}s. It only remembers the last frame count
     * and when it last changed, so the first sample is seeded from {@link Clock#msSinceFrame()} and
     * a stall that started before we asked is still detected on the very first call.
     *
     * <p>Thread safe: the HTTP threads and the game thread both ask.
     */
    public static final class Tracker {
        private final Clock clock;
        private final long stallMs;
        private boolean seeded;
        private long lastFrames;
        private long lastProgressAtMs;
        private Verdict last;

        public Tracker(Clock clock, long stallMs) {
            this.clock = clock;
            this.stallMs = stallMs;
        }

        /** Reads the clock, folds the reading into the stall estimate, returns the verdict. */
        public synchronized Verdict sample() {
            long now = clock.nowMs();
            long frames = clock.frames();
            if (!seeded) {
                seeded = true;
                lastFrames = frames;
                // A stall that already started before the first sample must not need a second call.
                long already = clock.msSinceFrame();
                lastProgressAtMs = now - (already < 0L ? 0L : already);
            } else if (frames != lastFrames) {
                lastFrames = frames;
                lastProgressAtMs = now;
            }
            long stalled = now - lastProgressAtMs;
            // The frame probe's own "ms since the last frame" is always true to the millisecond, even
            // when a frame happened between two of our samples. Taking the larger of the two means the
            // very first get_state after a crash can already answer "dead" instead of asking the
            // agent to poll one more time - which is exactly the loop #69 is about.
            long sinceFrame = clock.msSinceFrame();
            if (sinceFrame > stalled) {
                stalled = sinceFrame;
            }
            if (stalled < 0L) {
                stalled = 0L;
            }
            last = decide(clock.crash(), stalled, stallMs);
            return last;
        }

        /** The previous verdict without touching the clock (null before the first {@link #sample()}). */
        public synchronized Verdict lastVerdict() {
            return last;
        }
    }

    /** The production clock: real time, {@link spirelink.util.RuntimeProbe}, {@link spirelink.util.CrashGuard}. */
    private static final Clock RUNTIME_CLOCK = new Clock() {
        @Override
        public long nowMs() {
            return System.currentTimeMillis();
        }

        @Override
        public long frames() {
            return spirelink.util.RuntimeProbe.frames();
        }

        @Override
        public long msSinceFrame() {
            return spirelink.util.RuntimeProbe.msSinceFrame();
        }

        @Override
        public String crash() {
            return spirelink.util.CrashGuard.gameCrash();
        }
    };

    private static final Tracker LIVE = new Tracker(RUNTIME_CLOCK, STALL_MS);

    /** The one sampler the mod uses. Safe from any thread; reads statics only. */
    public static Verdict sample() {
        return LIVE.sample();
    }

    /** Last verdict without sampling again (may be null right after startup). */
    public static Verdict lastVerdict() {
        return LIVE.lastVerdict();
    }
}
