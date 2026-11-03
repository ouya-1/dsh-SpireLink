package spirelink.actions;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * C7-03: {@code confirm_grid} on a card-select grid.
 *
 * <p>Reported: hard-sending {@code confirm_grid} on a purge grid — where nothing is selected yet, so
 * the planner does not advertise it — answered {@code NOT_READY} ("还没选牌，先 choose_grid_cards").
 * {@code NOT_READY} tells the caller "the game is busy, poll and retry", which is the wrong advice
 * for an action {@code legal_actions} never contained; every unadvertised action must answer
 * {@code ILLEGAL_ACTION}.</p>
 *
 * <p>The rule is now one pure function used by both sides, built from vanilla's own confirm branch
 * ({@code javap -c GridCardSelectScreen}): the button exists while the screen shows it
 * ({@code confirmScreenUp} for upgrade/transform/purge, {@code isJustForConfirming} for the "just
 * confirm" flavour), a multi-select grid accepts it once {@code numCards} are picked, and a grid
 * that already holds selections may always be committed.</p>
 */
public class GridConfirmPolicyTest {

    /** The reported purge-grid case: nothing selected, button hidden → not offered, not NOT_READY. */
    @Test
    public void nothingSelectedMeansNoConfirmAtAll() {
        assertFalse(GridConfirmPolicy.confirmAvailable(false, 1, 0, false, false));
        // a grid that wants an unknown number of cards behaves the same way
        assertFalse(GridConfirmPolicy.confirmAvailable(false, 0, 0, false, false));
        assertFalse(GridConfirmPolicy.confirmAvailable(false, -1, 0, false, false));
    }

    @Test
    public void aLiveConfirmButtonIsEnough() {
        assertTrue("原版把确认按钮亮出来就等于可以确认",
                GridConfirmPolicy.confirmAvailable(true, 1, 0, false, false));
    }

    @Test
    public void enoughCardsSelectedIsEnough() {
        assertTrue(GridConfirmPolicy.confirmAvailable(false, 2, 2, false, false));
        assertTrue(GridConfirmPolicy.confirmAvailable(false, 1, 1, false, false));
        // "one short" is *not* a refusal: any pending pick may be committed on a multi-select grid,
        // which is the selected > 0 term asserted in aPendingPickCanAlwaysBeCommitted.
        assertTrue(GridConfirmPolicy.confirmAvailable(false, 2, 1, false, false));
    }

    @Test
    public void aPendingPickCanAlwaysBeCommitted() {
        assertTrue("已经选了牌就能确认（多选网格的部分选择）",
                GridConfirmPolicy.confirmAvailable(false, 3, 1, false, false));
    }

    @Test
    public void justForConfirmingAndUpgradePreviewBothConfirm() {
        assertTrue(GridConfirmPolicy.confirmAvailable(false, 0, 0, true, false));
        assertTrue(GridConfirmPolicy.confirmAvailable(false, 0, 0, false, true));
    }

    @Test
    public void theRejectionIsAboutLegalityNotReadiness() {
        String message = GridConfirmPolicy.nothingToConfirmMessage();
        assertNotNull(message);
        assertTrue("要指向真正能用的动作: " + message, message.contains(ActionTypes.CHOOSE_GRID_CARDS));
        assertTrue("要把「未广告 = ILLEGAL」说清楚: " + message,
                message.contains(ErrorCodes.ILLEGAL_ACTION));
        assertFalse("不能再建议「稍后重试」: " + message, message.contains("稍后重试"));
    }
}
