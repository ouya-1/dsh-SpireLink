package spirelink.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issues #55 / #69: "this run is over" must be a decision, not a guess.
 *
 * <p>Every case here is a fake clock: no {@code Thread.sleep}, no game, no wall-clock flakiness.
 * The three states the issue asks for are covered explicitly - no crash, crash with a live loop,
 * crash with a frozen loop - plus the recovery case (the loop came back, so it is NOT a death).
 */
public class GameLivenessTest {

    private static final String CRASH = "java.lang.ArrayIndexOutOfBoundsException: -1";

    /** Fake clock: time, frame counter and crash marker can all be moved by hand. */
    private static final class FakeClock implements GameLiveness.Clock {
        long now = 1_000_000L;
        long frames = 100L;
        long msSinceFrame = 0L;
        String crash = null;

        @Override
        public long nowMs() {
            return now;
        }

        @Override
        public long frames() {
            return frames;
        }

        @Override
        public long msSinceFrame() {
            return msSinceFrame;
        }

        @Override
        public String crash() {
            return crash;
        }

        /** A rendered frame: the counter moves and "ms since frame" resets. */
        void render() {
            frames++;
            msSinceFrame = 0L;
        }

        /** What a dead loop looks like: time passes, nothing is drawn. */
        void silentlyAdvance(long ms) {
            now += ms;
            msSinceFrame += ms;
        }
    }

    // ---------------------------------------------------------------- the pure rule

    @Test
    public void noCrashIsNeverDead() {
        GameLiveness.Verdict verdict = GameLiveness.decide(null, 60_000L, GameLiveness.STALL_MS);
        assertFalse("没崩溃就不能判死（最小化窗口/长 GC/暂停都不能误判）", verdict.dead);
        assertFalse("render_crashed 是 CrashGuard 的事实，不是推断", verdict.renderCrashed);
        assertEquals(GameLiveness.REASON_STALLED_LOOP_ALIVE, verdict.reason);
    }

    @Test
    public void crashWithoutStallIsNotDeadYet() {
        // The loop threw, but the frame counter is still moving: vanilla caught it somewhere and the
        // window is alive. Reporting "restart the game" here would be a lie.
        GameLiveness.Verdict verdict = GameLiveness.decide(CRASH, 10L, GameLiveness.STALL_MS);
        assertTrue("崩溃标记必须如实反映（#55）", verdict.renderCrashed);
        assertFalse("帧还在推进，不能判死", verdict.dead);
        assertEquals(GameLiveness.REASON_CRASHED_LOOP_ALIVE, verdict.reason);
    }

    @Test
    public void crashPlusFrozenFramesIsDead() {
        GameLiveness.Verdict verdict = GameLiveness.decide(CRASH, GameLiveness.STALL_MS, GameLiveness.STALL_MS);
        assertTrue(verdict.renderCrashed);
        assertTrue("崩溃 + 帧循环停滞 = 死（#69 的判定）", verdict.dead);
        assertEquals(GameLiveness.REASON_DEAD, verdict.reason);
        assertEquals(GameLiveness.STALL_MS, verdict.stalledMs);
        assertEquals(CRASH, verdict.crash);
    }

    @Test
    public void stallThresholdIsInclusive() {
        assertFalse("4999ms 还差一点", GameLiveness.decide(CRASH, 4_999L, 5_000L).dead);
        assertTrue("正好 5000ms 就是死", GameLiveness.decide(CRASH, 5_000L, 5_000L).dead);
    }

    @Test
    public void negativeStallIsClampedToZero() {
        GameLiveness.Verdict verdict = GameLiveness.decide(CRASH, -12_345L, 5_000L);
        assertEquals("时钟倒退不能算出负停滞", 0L, verdict.stalledMs);
        assertFalse(verdict.dead);
    }

    @Test
    public void zeroThresholdWithCrashIsDead() {
        // Guards the no-argument trap: a caller passing stallMs=0 must not mean "always dead" for a
        // healthy game, only for one that already crashed.
        assertTrue(GameLiveness.decide(CRASH, 0L, 0L).dead);
        assertFalse(GameLiveness.decide(null, 0L, 0L).dead);
    }

    // ---------------------------------------------------------------- the tracker

    @Test
    public void trackerNeverDiesWhileFramesAdvance() {
        FakeClock clock = new FakeClock();
        clock.crash = CRASH;
        GameLiveness.Tracker tracker = new GameLiveness.Tracker(clock, GameLiveness.STALL_MS);
        for (int i = 0; i < 20; i++) {
            clock.render();
            clock.silentlyAdvance(16L); // ~60 fps
            assertFalse("帧一直推进就绝不能判死", tracker.sample().dead);
        }
    }

    @Test
    public void trackerDeclaresDeathAfterTheStallWindow() {
        FakeClock clock = new FakeClock();
        GameLiveness.Tracker tracker = new GameLiveness.Tracker(clock, GameLiveness.STALL_MS);
        clock.render();
        assertFalse(tracker.sample().dead);
        clock.crash = CRASH;          // the loop threw...
        clock.silentlyAdvance(3_000L); // ...and 3 s later nothing is drawn yet
        GameLiveness.Verdict early = tracker.sample();
        assertTrue(early.renderCrashed);
        assertFalse("不到 5 秒还在宽限期内", early.dead);
        clock.silentlyAdvance(2_100L); // now 5.1 s of silence
        GameLiveness.Verdict dead = tracker.sample();
        assertTrue("崩溃 + 超过 5 秒没有帧 = 死", dead.dead);
        assertNotNull(tracker.lastVerdict());
        assertTrue(tracker.lastVerdict().dead);
    }

    @Test
    public void trackerReportsRecoveryAsNotDead() {
        // "捕获后恢复" from the #55 acceptance list: the crash marker stays (sticky fact), the death
        // verdict must not.
        FakeClock clock = new FakeClock();
        clock.crash = CRASH;
        clock.silentlyAdvance(60_000L);
        GameLiveness.Tracker tracker = new GameLiveness.Tracker(clock, GameLiveness.STALL_MS);
        assertTrue(tracker.sample().dead);
        clock.render();               // the loop came back
        GameLiveness.Verdict recovered = tracker.sample();
        assertTrue("render_crashed 是历史事实，粘住", recovered.renderCrashed);
        assertFalse("循环活了就不再是终态", recovered.dead);
        assertEquals(0L, recovered.stalledMs);
    }

    @Test
    public void trackerSeesAStallThatStartedBeforeTheFirstSample() {
        // The MCP thread may ask for the first time minutes after the crash: seeding from
        // msSinceFrame() means one call is enough, not two.
        FakeClock clock = new FakeClock();
        clock.crash = CRASH;
        clock.frames = 45924L;
        clock.silentlyAdvance(120_000L);
        GameLiveness.Tracker tracker = new GameLiveness.Tracker(clock, GameLiveness.STALL_MS);
        GameLiveness.Verdict verdict = tracker.sample();
        assertTrue("第一次采样就要能判死", verdict.dead);
        assertEquals(120_000L, verdict.stalledMs);
    }

    @Test
    public void stallIsMeasuredFromTheLastFrameNotFromTheLastSample() {
        // The nasty ordering: a frame is rendered between two samples, and only then does the loop
        // die. Frame-delta alone would report 0 ms stalled and make the agent poll once more.
        FakeClock clock = new FakeClock();
        GameLiveness.Tracker tracker = new GameLiveness.Tracker(clock, GameLiveness.STALL_MS);
        assertFalse(tracker.sample().dead);
        clock.render();
        clock.crash = CRASH;
        clock.silentlyAdvance(6_000L);
        GameLiveness.Verdict verdict = tracker.sample();
        assertTrue("首轮就该判死（ms_since_frame 已经是 6 秒）", verdict.dead);
        assertTrue(verdict.stalledMs >= GameLiveness.STALL_MS);
    }

    @Test
    public void trackerDoesNotDieOnAFrozenWindowWithoutCrash() {
        // A minimised / paused window freezes the frame counter but the game is fine. This is the
        // "正常动画/暂停不能误判" half of #69.
        FakeClock clock = new FakeClock();
        GameLiveness.Tracker tracker = new GameLiveness.Tracker(clock, GameLiveness.STALL_MS);
        tracker.sample();
        clock.silentlyAdvance(30_000L);
        GameLiveness.Verdict verdict = tracker.sample();
        assertFalse(verdict.renderCrashed);
        assertFalse("没崩溃的停滞只是 hints.frames_stalled，不是死", verdict.dead);
        assertEquals(GameLiveness.REASON_STALLED_LOOP_ALIVE, verdict.reason);
    }

    @Test
    public void trackerStartsIdleWithoutAVerdict() {
        FakeClock clock = new FakeClock();
        GameLiveness.Tracker tracker = new GameLiveness.Tracker(clock, GameLiveness.STALL_MS);
        assertNull("没采样过就没有结论", tracker.lastVerdict());
    }

    // ---------------------------------------------------------------- production wiring

    @Test
    public void productionSamplerReadsCrashGuardAndRuntimeProbe() {
        // No mutation of the CrashGuard statics here on purpose: they are process-wide and a test
        // that sets them would poison every later test in the same JVM. Only the wiring is checked.
        GameLiveness.Verdict verdict = GameLiveness.sample();
        assertNotNull(verdict);
        assertEquals(spirelink.util.CrashGuard.gameCrash() != null, verdict.renderCrashed);
        assertFalse("测试进程里没有崩溃标记时，绝不能报死", verdict.dead);
        assertNotNull(GameLiveness.lastVerdict());
        assertEquals("阈值必须是 5 秒（#69 的 5 秒判据）", 5_000L, GameLiveness.STALL_MS);
    }
}
