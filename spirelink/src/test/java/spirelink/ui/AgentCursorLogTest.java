package spirelink.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The draw log is the only evidence a human has that the fish went where the pointer said, so it
 * has to stay readable: QA saw 257 lines in five idle minutes because a 1 px wobble flipped the
 * rounding every other frame. These tests pin the debounce rule that replaced it.
 */
public class AgentCursorLogTest {

    /** Idle wobble: the sprite trembles ~1 px every frame - that must never reach the log. */
    @Test
    public void onePixelWobbleIsNotWorthALine() {
        assertEquals(AgentCursor.LOG_NEVER, AgentCursor.drawLogDecision(false, 1.2f, 16L));
        assertEquals(AgentCursor.LOG_NEVER, AgentCursor.drawLogDecision(false, 0f, 5000L));
    }

    /** Exactly the threshold is still "did not really move" (the rule is strictly greater). */
    @Test
    public void thresholdBoundaryIsQuiet() {
        assertEquals(AgentCursor.LOG_NEVER,
                AgentCursor.drawLogDecision(false, AgentCursor.LOG_MOVE_PX, 16L));
        assertEquals(AgentCursor.LOG_SUPPRESSED,
                AgentCursor.drawLogDecision(false, AgentCursor.LOG_MOVE_PX + 0.1f, 16L));
    }

    /** A glide moves fast: one line per second, the skipped frames are counted instead. */
    @Test
    public void fastGlideIsRateLimitedAndCounted() {
        assertEquals(AgentCursor.LOG_SUPPRESSED, AgentCursor.drawLogDecision(false, 120f, 16L));
        assertEquals(AgentCursor.LOG_SUPPRESSED,
                AgentCursor.drawLogDecision(false, 120f, AgentCursor.LOG_MIN_INTERVAL_MS - 1L));
        assertEquals(AgentCursor.LOG_YES,
                AgentCursor.drawLogDecision(false, 120f, AgentCursor.LOG_MIN_INTERVAL_MS));
    }

    /** Screen / face / flight / alpha changes are the interesting events: never held back. */
    @Test
    public void stateChangesAlwaysWrite() {
        assertEquals(AgentCursor.LOG_YES, AgentCursor.drawLogDecision(true, 0f, 0L));
        assertEquals(AgentCursor.LOG_YES, AgentCursor.drawLogDecision(true, 0.5f, 16L));
    }

    /** Bad coordinates must not turn into a NaN distance that starts logging every frame. */
    @Test
    public void notANumberMovementStaysQuiet() {
        assertEquals(AgentCursor.LOG_NEVER,
                AgentCursor.drawLogDecision(false, Float.NaN, 5000L));
    }
}
