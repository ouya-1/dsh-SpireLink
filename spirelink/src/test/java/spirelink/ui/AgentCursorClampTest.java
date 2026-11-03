package spirelink.ui;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issue #53: the fish was drawn up-right of the point the agent touched, so a target near an edge
 * hung the sprite half outside the window (hand cards sit at the bottom of the screen;
 * {@code choose_hand_cards} pointed at 234,-7 and the lower part of the fish was scissored away).
 *
 * <p>The rule that fixes it is pure geometry, so it is pinned here instead of in a screenshot: the
 * sprite centre is clamped into the window minus half the sprite plus the edge margin, and a target
 * that already fits is not moved by even a pixel.</p>
 *
 * <p>Numbers are the real ones: a 1920x1080 window and the 148 px fish at scale 1, i.e.
 * {@code half = 148/2 + 8 = 82}.</p>
 */
public class AgentCursorClampTest {

    private static final float W = 1920f;
    private static final float H = 1080f;
    /** Half the fish plus the 8 px edge margin at 1080p. */
    private static final float HALF = 82f;
    private static final float EPS = 1.0e-4f;

    /** A point with room on every side is left exactly where it was. */
    @Test
    public void pointInTheMiddleDoesNotMove() {
        assertArrayEquals(new float[]{960f, 540f},
                AgentCursor.clampToWindow(960f, 540f, W, H, HALF), EPS);
    }

    @Test
    public void leftEdgeIsPushedInside() {
        assertArrayEquals(new float[]{HALF, 540f},
                AgentCursor.clampToWindow(5f, 540f, W, H, HALF), EPS);
        // One pixel outside the safe area is already outside.
        assertArrayEquals(new float[]{HALF, 540f},
                AgentCursor.clampToWindow(HALF - 1f, 540f, W, H, HALF), EPS);
    }

    @Test
    public void rightEdgeIsPushedInside() {
        assertArrayEquals(new float[]{W - HALF, 540f},
                AgentCursor.clampToWindow(W + 300f, 540f, W, H, HALF), EPS);
        assertArrayEquals(new float[]{W - HALF, 540f},
                AgentCursor.clampToWindow(W - HALF + 0.5f, 540f, W, H, HALF), EPS);
    }

    /** The reported bug: a hand card's centre sits at y ≈ 0, so the fish centre lands below half. */
    @Test
    public void bottomEdgeIsPushedInside() {
        assertArrayEquals(new float[]{308f, HALF},
                AgentCursor.clampToWindow(308f, 37f, W, H, HALF), EPS);
        assertArrayEquals(new float[]{308f, HALF},
                AgentCursor.clampToWindow(308f, -400f, W, H, HALF), EPS);
    }

    @Test
    public void topEdgeIsPushedInside() {
        assertArrayEquals(new float[]{960f, H - HALF},
                AgentCursor.clampToWindow(960f, H + 60f, W, H, HALF), EPS);
    }

    /** Exactly on the safe line is inside: the sprite touches the margin and is not moved. */
    @Test
    public void exactlyOnTheSafeEdgeDoesNotMove() {
        assertArrayEquals(new float[]{HALF, H - HALF},
                AgentCursor.clampToWindow(HALF, H - HALF, W, H, HALF), EPS);
        assertArrayEquals(new float[]{W - HALF, HALF},
                AgentCursor.clampToWindow(W - HALF, HALF, W, H, HALF), EPS);
    }

    /** Both axes can be out at once (a corner); each is clamped independently. */
    @Test
    public void cornerIsPushedInsideOnBothAxes() {
        assertArrayEquals(new float[]{W - HALF, H - HALF},
                AgentCursor.clampToWindow(W + 50f, H + 50f, W, H, HALF), EPS);
        assertArrayEquals(new float[]{HALF, HALF},
                AgentCursor.clampToWindow(-50f, -50f, W, H, HALF), EPS);
    }

    /**
     * A window smaller than the fish cannot show it whole: centre it rather than leave it cropped in
     * a corner, so at least the middle of the sprite (its face) is what stays visible.
     */
    @Test
    public void windowSmallerThanTheFishCentresIt() {
        assertArrayEquals(new float[]{50f, 50f},
                AgentCursor.clampToWindow(0f, 0f, 100f, 100f, HALF), EPS);
        assertArrayEquals(new float[]{50f, 50f},
                AgentCursor.clampToWindow(500f, 500f, 100f, 100f, HALF), EPS);
    }

    /**
     * Settings.WIDTH/HEIGHT are 0 while the mod is still booting; a window with no size must leave
     * the fish alone instead of parking it somewhere odd.
     */
    @Test
    public void unmeasuredWindowDoesNotMove() {
        assertArrayEquals(new float[]{-7f, 1234f},
                AgentCursor.clampToWindow(-7f, 1234f, 0f, 0f, HALF), EPS);
        // Half-measured (width known, height not yet): only the known axis is clamped.
        assertArrayEquals(new float[]{HALF, 1234f},
                AgentCursor.clampToWindow(-7f, 1234f, W, 0f, HALF), EPS);
        assertArrayEquals(new float[]{-7f, 1234f},
                AgentCursor.clampToWindow(-7f, 1234f, Float.NaN, Float.NaN, HALF), EPS);
    }

    /** A nonsense half (zero or negative) degrades to "no margin", not to an inverted safe area. */
    @Test
    public void nonPositiveMarginIsTreatedAsNone() {
        assertEquals(0f, AgentCursor.clampAxis(-5f, W, 0f), EPS);
        assertEquals(0f, AgentCursor.clampAxis(-5f, W, -40f), EPS);
        assertEquals(W, AgentCursor.clampAxis(W + 5f, W, -40f), EPS);
        // A zero-size margin means "flush with the edge is fine".
        assertEquals(0f, AgentCursor.clampAxis(0f, W, 0f), EPS);
    }

    /** Whatever the input, the returned centre keeps a half-size sprite fully inside the window. */
    @Test
    public void clampedSpriteAlwaysFitsTheWindow() {
        for (float dx = -500f; dx <= W + 500f; dx += 37f) {
            for (float dy = -500f; dy <= H + 500f; dy += 41f) {
                float[] c = AgentCursor.clampToWindow(dx, dy, W, H, HALF);
                String at = " at " + dx + "," + dy;
                assertTrue("left" + at, c[0] - HALF >= -EPS);
                assertTrue("right" + at, c[0] + HALF <= W + EPS);
                assertTrue("bottom" + at, c[1] - HALF >= -EPS);
                assertTrue("top" + at, c[1] + HALF <= H + EPS);
            }
        }
    }
}
