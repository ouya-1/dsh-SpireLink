package spirelink.patches;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The grid request must be one-shot: set it, consume it once, never linger into later frames. */
public class GridCardSelectScreenPatchTest {

    @After
    public void tearDown() {
        GridCardSelectScreenPatch.clear();
    }

    @Test
    public void requestWithoutCardDoesNothing() {
        GridCardSelectScreenPatch.request(null);
        assertFalse(GridCardSelectScreenPatch.replaceHoverCard);
        assertNull(GridCardSelectScreenPatch.hoverCard);
    }

    @Test
    public void clearResetsPendingState() {
        GridCardSelectScreenPatch.replaceHoverCard = true;
        assertTrue(GridCardSelectScreenPatch.clear());
        assertFalse(GridCardSelectScreenPatch.replaceHoverCard);
        assertNull(GridCardSelectScreenPatch.hoverCard);
        assertFalse(GridCardSelectScreenPatch.clear());
    }
}
