package spirelink.actions;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #96: {@code cancel_grid} was advertised on Transmogrifier's transform grid, which is a
 * confirm-only grid in vanilla. Cancelling closed the screen and the event settled as "transformed"
 * with the deck untouched (19 cards in, 19 cards out, option gone).
 *
 * <p>The rule is vanilla's own: {@code GridCardSelectScreen.open(...)} shows the CancelButton only
 * when {@code canCancel} is set and hides it otherwise. Confirmed against the shipped jar with
 * {@code javap -c}: Transmogrifier {@code open(deck,2,tip,false,true,false,false)},
 * Designer upgrade/purge {@code canCancel=false}, campfire smith {@code canCancel=true}.</p>
 */
public class GridCancelPolicyTest {

    @Test
    public void aGridWithACancelButtonCanAlwaysBeCancelled() {
        assertTrue(GridCancelPolicy.cancelAllowed(true, true, false));
        assertTrue(GridCancelPolicy.cancelAllowed(true, false, true));
        assertTrue(GridCancelPolicy.cancelAllowed(true, false, false));
        assertTrue(GridCancelPolicy.cancelAllowed(true, true, true));
    }

    /** The actual bug: confirm-only grid with pickable cards must not offer a cancel. */
    @Test
    public void aConfirmOnlyGridWithPickableCardsHasNoCancel() {
        assertFalse("Transmogrifier：能选牌、没有 CancelButton → 不能广告 cancel_grid",
                GridCancelPolicy.cancelAllowed(false, true, false));
        assertFalse("能选牌也能确认 → 还是不给 cancel",
                GridCancelPolicy.cancelAllowed(false, true, true));
    }

    /** Picked everything: the way forward is confirm, not a cancel that would lose the pick. */
    @Test
    public void aConfirmOnlyGridWithSomethingToConfirmHasNoCancel() {
        assertFalse(GridCancelPolicy.cancelAllowed(false, false, true));
    }

    /** Only the pathological "nothing clickable at all" screen keeps the escape hatch (I4). */
    @Test
    public void theEmptyConfirmOnlyGridKeepsALastResortCancel() {
        assertTrue(GridCancelPolicy.cancelAllowed(false, false, false));
    }

    @Test
    public void theRejectionExplainsWhatToDoInstead() {
        String message = GridCancelPolicy.confirmOnlyMessage();
        assertNotNull(message);
        assertTrue(message.contains(ActionTypes.CHOOSE_GRID_CARDS));
        assertTrue(message.contains(ActionTypes.CONFIRM_GRID));
        assertTrue("要说清后果，别人才知道为什么不能 cancel: " + message,
                message.contains("事件"));
    }
}
