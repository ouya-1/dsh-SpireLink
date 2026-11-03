package spirelink.actions;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * C7-01: {@code skip_card} on the card-reward screen.
 *
 * <p>The report: {@code act{proceed}} on {@code card_reward} ran (never advertised) and the log
 * showed {@code WARN skip_card: 屏幕没关（skippedCards 无效），已手动 closeCurrentScreen()} plus a
 * {@code 兜底 takeReward()} WARN. Checked against the shipped jar with {@code javap -c}:</p>
 * <ul>
 *   <li>{@code CardRewardScreen.skippedCards()} is only {@code recordMetrics("SKIP")} — it closes
 *       nothing, so the old "call it and hope" implementation always fell through;</li>
 *   <li>the native skip is {@code SkipCardButton.update()}: {@code skippedCards()} +
 *       {@code AbstractDungeon.closeCurrentScreen()};</li>
 *   <li>the button only exists on skippable flavours: {@code open()} shows it,
 *       {@code draftOpen()} / {@code chooseOneOpen()} / {@code discoveryOpen()} hide it
 *       ({@code skippable = false}), while {@code codexOpen()} / {@code carveRealityOpen()} /
 *       {@code foreignInfluenceOpen()} show it again — so the button's own visibility, not the stale
 *       private {@code skippable} flag, is the predicate both sides now share.</li>
 * </ul>
 */
public class CardRewardPolicyTest {

    @Test
    public void aVisibleSkipButtonMeansSkipIsAdvertisedAndExecutable() {
        assertTrue(CardRewardPolicy.skipAvailable(true, Boolean.TRUE));
    }

    /** draft / choose-one / discovery: vanilla hides the button, so there is nothing to skip. */
    @Test
    public void aHiddenSkipButtonMeansNoSkipAtAll() {
        assertFalse(CardRewardPolicy.skipAvailable(true, Boolean.FALSE));
    }

    /** No card list on the screen → nothing to skip (the planner returns early for this case too). */
    @Test
    public void noRewardGroupMeansNoSkip() {
        assertFalse(CardRewardPolicy.skipAvailable(false, Boolean.TRUE));
        assertFalse(CardRewardPolicy.skipAvailable(false, null));
    }

    /**
     * Fail open: an unreadable button must not take the exit away. The old planner advertised skip
     * unconditionally for exactly this reason — a card reward with no exit deadlocked a run.
     */
    @Test
    public void anUnreadableButtonKeepsTheOldAlwaysOfferBehaviour() {
        assertTrue(CardRewardPolicy.skipAvailable(true, null));
    }

    @Test
    public void theRejectionNamesTheOnlyRealWayOut() {
        String message = CardRewardPolicy.notSkippableMessage();
        assertNotNull(message);
        assertTrue(message.contains(ActionTypes.TAKE_CARD));
        assertTrue("要说清为什么不能 skip: " + message, message.contains("跳过"));
    }

    // ---------------------------------------------------------------- C7-04 range text

    @Test
    public void rangeTextFollowsTakeRewardsShape() {
        assertEquals("合法范围 0..2（共 3 张）", CardRewardPolicy.rangeText(3));
        assertEquals("合法范围 0..0（共 1 张）", CardRewardPolicy.rangeText(1));
        assertEquals("这一屏没有可拿的牌（合法范围为空）", CardRewardPolicy.rangeText(0));
    }

    @Test
    public void cardsTextListsEveryCardThatCanBeNamed() {
        assertEquals("0=打击(Strike), 1=防御(Defend)",
                CardRewardPolicy.cardsText(Arrays.asList("0=打击(Strike)", "1=防御(Defend)")));
        assertEquals("（这一屏没有可拿的牌）", CardRewardPolicy.cardsText(Collections.<String>emptyList()));
        assertEquals("（这一屏没有可拿的牌）", CardRewardPolicy.cardsText(null));
    }
}
