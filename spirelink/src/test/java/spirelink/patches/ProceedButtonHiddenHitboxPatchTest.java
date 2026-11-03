package spirelink.patches;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #44: a hidden overlay button must never look like a pointer target.
 *
 * <p>The whole point of parking the hitbox is that the pointer layer's own bounds rule then
 * rejects it. These tests pin that invariant at the three window sizes the game actually runs at,
 * and pin the opposite fact that made the bug possible: the stale centre {@code 1113,213} from the
 * issue report is <i>inside</i> a 720p window, so no bounds check could ever have caught it.</p>
 */
public class ProceedButtonHiddenHitboxPatchTest {

    private static final float PARKED = ProceedButtonHiddenHitboxPatch.PARKED;

    @Test
    public void parkedPointIsRejectedAt720p() {
        assertFalse(ProceedButtonHiddenHitboxPatch.insideWindow(PARKED, PARKED, 1280f, 720f));
    }

    @Test
    public void parkedPointIsRejectedAt1080pAnd4k() {
        assertFalse(ProceedButtonHiddenHitboxPatch.insideWindow(PARKED, PARKED, 1920f, 1080f));
        assertFalse(ProceedButtonHiddenHitboxPatch.insideWindow(PARKED, PARKED, 3840f, 2160f));
    }

    @Test
    public void staleHitboxFromTheIssueLooksPerfectlyUsable() {
        // 1113,213 is where the fish flew on the 720p map screen: a real point inside the window,
        // so the old "is it on screen?" guard could not save us - only the park can.
        assertTrue(ProceedButtonHiddenHitboxPatch.insideWindow(1113f, 213f, 1280f, 720f));
        assertFalse(ProceedButtonHiddenHitboxPatch.isParked(1113f, 213f));
        assertTrue(ProceedButtonHiddenHitboxPatch.isParked(PARKED, PARKED));
    }

    @Test
    public void realButtonPositionsAreStillUsable() {
        // A visible proceed button sits near the bottom right of the window; it must stay usable.
        assertTrue(ProceedButtonHiddenHitboxPatch.insideWindow(1150f, 90f, 1280f, 720f));
        assertTrue(ProceedButtonHiddenHitboxPatch.insideWindow(8f, 8f, 1280f, 720f));
    }

    @Test
    public void notANumberIsNeverAUsableTarget() {
        assertFalse(ProceedButtonHiddenHitboxPatch.insideWindow(Float.NaN, 100f, 1280f, 720f));
        assertFalse(ProceedButtonHiddenHitboxPatch.insideWindow(100f, Float.NaN, 1280f, 720f));
    }

    @Test
    public void parkingIsIdempotentAndRestorable() {
        // The park flag must survive repeated hidden frames without ever falling back to "usable".
        assertTrue(ProceedButtonHiddenHitboxPatch.isParked(PARKED - 5f, PARKED - 5f));
        assertFalse(ProceedButtonHiddenHitboxPatch.insideWindow(PARKED - 5f, PARKED - 5f, 1280f, 720f));
    }
}
