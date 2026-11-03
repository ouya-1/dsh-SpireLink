package spirelink.actions;

import org.junit.Test;
import spirelink.state.UiNames;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * #50: a human clicking the top bar「牌组/弃牌堆/消耗堆/抽牌堆」opened a view screen whose
 * {@code ui} is {@code tutorial_tip}, so the agent's only legal action was {@code dismiss_tip} — and
 * the executor rejected every one of them because {@code AbstractDungeon.ftue == null}. The agent
 * was locked out until the human closed the view by hand.
 *
 * <p>The decision is a pure function ({@link ActionExecutor#decideDismiss(boolean, boolean)}) so it
 * can be checked here without a running game: no FTUE tip + an open screen must degrade to
 * {@code closeCurrentScreen()}, only a real tip may click the GotItButton, and an empty screen must
 * be refused instead of answering ok while nothing changed.
 */
public class DismissTipTest {

    /**
     * The four view screens {@code UiNames} maps to {@code ui=tutorial_tip} although they are not
     * FTUE tips. Enumerated so the bug is pinned to the real screen names, not to a boolean.
     */
    private static final String[] VIEW_SCREENS = {
            "MASTER_DECK_VIEW", // 牌组
            "DISCARD_VIEW",     // 弃牌堆
            "EXHAUST_VIEW",     // 消耗堆
            "GAME_DECK_VIEW",   // 抽牌堆
    };

    /** Same meaning as the executor's {@code screenUp}: a real screen is up ({@code NONE}/null: none). */
    private static boolean screenUp(String screenName) {
        return screenName != null && !"NONE".equals(screenName);
    }

    @Test
    public void viewScreensStillShareTheTutorialTipUiEntry() {
        // The lock-up is rooted in this mapping: if it ever changes, #50 has to be re-read.
        for (String screen : VIEW_SCREENS) {
            assertEquals(screen, "tutorial_tip", UiNames.ofScreen(screen));
        }
        assertEquals("tutorial_tip", UiNames.ofScreen("FTUE"));
        assertNull(UiNames.ofScreen("NONE"));
    }

    @Test
    public void ftueNullWithEachViewScreenClosesTheScreen() {
        for (String screen : VIEW_SCREENS) {
            assertTrue(screen, screenUp(screen));
            assertEquals("#50 " + screen + " 必须退化为 closeCurrentScreen()",
                    ActionExecutor.DismissStep.CLOSE_SCREEN,
                    ActionExecutor.decideDismiss(false, screenUp(screen)));
        }
    }

    @Test
    public void ftueNullWithoutAScreenIsRejected() {
        assertEquals(ActionExecutor.DismissStep.REJECT,
                ActionExecutor.decideDismiss(false, screenUp("NONE")));
        assertEquals(ActionExecutor.DismissStep.REJECT,
                ActionExecutor.decideDismiss(false, screenUp(null)));
    }

    @Test
    public void realFtueTipKeepsClickingGotIt() {
        assertEquals(ActionExecutor.DismissStep.GOT_IT,
                ActionExecutor.decideDismiss(true, screenUp("FTUE")));
        // The tip object wins even while the screen field lags a frame behind.
        assertEquals(ActionExecutor.DismissStep.GOT_IT,
                ActionExecutor.decideDismiss(true, false));
    }

    /**
     * Regression for the lock-up itself: the old code only looked at {@code ftue}, so
     * {@code decideDismiss(false, true)} was REJECT and the only offered action could never succeed.
     */
    @Test
    public void dismissTipIsNoLongerAlwaysRejectedForAViewScreen() {
        for (String screen : VIEW_SCREENS) {
            assertFalse(screen + " 仍然会被拒",
                    ActionExecutor.DismissStep.REJECT
                            == ActionExecutor.decideDismiss(false, screenUp(screen)));
        }
    }
}
