package spirelink.actions;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #74: {@code ui=hand_select} used to advertise a single {@code confirm_hand_select}, so an
 * agent had no way to pick a card and no way out. The three states (nothing picked / partly picked /
 * full) are pinned down here through the pure core, no game object involved.
 *
 * <p>Issue #102 then found the other half of the same screen broken the other way: confirm was
 * advertised in states where the vanilla click does nothing (10.6 s {@code queued} + a 13 s lock),
 * and cancel was advertised where the click would confirm the picked cards. Both sides now read
 * {@link HandSelectPolicy}; the cases below are that policy applied to the planner.</p>
 *
 * <p>Issue #57 keeps the third half honest: proceed must never be among these actions.</p>
 */
public class ActionPlannerHandSelectTest {

    // ---------------------------------------------------------------- helpers

    private static ActionPlanner.HandChoice card(String uuid, String label, boolean selected) {
        return new ActionPlanner.HandChoice(uuid, label, selected);
    }

    private static List<ActionPlanner.HandChoice> hand(ActionPlanner.HandChoice... cards) {
        return Arrays.asList(cards);
    }

    private static int count(List<ActionSpec> specs, String type) {
        int n = 0;
        for (ActionSpec spec : specs) {
            if (type.equals(spec.type)) {
                n++;
            }
        }
        return n;
    }

    private static ActionSpec first(List<ActionSpec> specs, String type) {
        for (ActionSpec spec : specs) {
            if (type.equals(spec.type)) {
                return spec;
            }
        }
        return null;
    }

    /** Nothing picked yet, three cards in hand, the game wants one. */
    @Test
    public void nothingPickedOffersEveryCardAndNoWaysOut() {
        List<ActionSpec> out = new java.util.ArrayList<ActionSpec>();
        ActionPlanner.handSelectActions(out, hand(
                card("u1", "打击", false), card("u2", "防御", false), card("u3", "痛击", false)),
                1, 0, false, false, false, "选择一张牌消耗");

        assertEquals("三张可选牌各一条 choose", 3, count(out, ActionTypes.CHOOSE_HAND_CARDS));
        assertEquals("还没选够，不能确认", 0, count(out, ActionTypes.CONFIRM_HAND_SELECT));
        // #102: fixed count + nothing picked -> the vanilla click does nothing, so neither does ours.
        assertEquals(0, count(out, ActionTypes.CANCEL_HAND_SELECT));
        assertEquals("不能出现 proceed（issue #57）", 0, count(out, ActionTypes.PROCEED));

        ActionSpec pick = first(out, ActionTypes.CHOOSE_HAND_CARDS);
        assertNotNull(pick);
        assertTrue("标签要带卡名: " + pick.label, pick.label.contains("打击"));
        assertTrue("标签要带原因: " + pick.label, pick.label.contains("选择一张牌消耗"));
        assertEquals("u1", pick.payload.getAsJsonArray("uuids").get(0).getAsString());
        assertEquals("a1", pick.id);
    }

    /** One card already marked: it disappears from the choosable list. */
    @Test
    public void partlyPickedOffersOnlyTheRest() {
        List<ActionSpec> out = new java.util.ArrayList<ActionSpec>();
        ActionPlanner.handSelectActions(out, hand(
                card("u1", "打击", true), card("u2", "防御", false)), 2, 1, false, false, false, "");

        assertEquals("已选中的牌不再给 choose", 1, count(out, ActionTypes.CHOOSE_HAND_CARDS));
        assertEquals("u2", first(out, ActionTypes.CHOOSE_HAND_CARDS)
                .payload.getAsJsonArray("uuids").get(0).getAsString());
        assertEquals("还没选够 2 张，不能确认", 0, count(out, ActionTypes.CONFIRM_HAND_SELECT));
        assertEquals("固定张数没得取消", 0, count(out, ActionTypes.CANCEL_HAND_SELECT));
    }

    /**
     * The #102 regression: a {@code canPickZero} screen with a <b>partial</b> selection. The planner
     * used to advertise confirm here (because {@code canPickZero} was true), the executor clicked,
     * vanilla ignored the click, and the harness waited for a state change that could never come.
     */
    @Test
    public void partialPickOnACanPickZeroScreenAdvertisesNeitherConfirmNorCancel() {
        List<ActionSpec> out = new java.util.ArrayList<ActionSpec>();
        ActionPlanner.handSelectActions(out, hand(
                card("u1", "打击", true), card("u2", "防御", false)), 2, 1, true, false, false, "");

        assertEquals("还能选的那张才是唯一真动作", 1, count(out, ActionTypes.CHOOSE_HAND_CARDS));
        assertEquals("这一格原版点确认是空操作（#102）", 0,
                count(out, ActionTypes.CONFIRM_HAND_SELECT));
        assertEquals("已经选了牌，取消会把它确认掉", 0, count(out, ActionTypes.CANCEL_HAND_SELECT));
    }

    /** Full selection: the card list closes and confirm is the way forward. */
    @Test
    public void fullSelectionOffersConfirmInsteadOfCancel() {
        List<ActionSpec> out = new java.util.ArrayList<ActionSpec>();
        ActionPlanner.handSelectActions(out, hand(
                card("u1", "打击", true), card("u2", "防御", true)), 2, 2, false, false, false, "");

        assertEquals(0, count(out, ActionTypes.CHOOSE_HAND_CARDS));
        assertEquals(1, count(out, ActionTypes.CONFIRM_HAND_SELECT));
        assertEquals("固定张数已选满：取消不给，免得广告出来又被拒", 0,
                count(out, ActionTypes.CANCEL_HAND_SELECT));
    }

    /**
     * Issue #74's screenshot (1 wanted, 1 already picked) must never be an actionless screen.
     *
     * <p>The I4 requirement is "at least one executable action", and confirm is the one that
     * executes.</p>
     */
    @Test
    public void theExactScreenshotFromIssue74StillHasALegalMove() {
        List<ActionSpec> out = new java.util.ArrayList<ActionSpec>();
        ActionPlanner.handSelectActions(out, hand(card("u1", "打击", true)), 1, 1, false, false,
                false, "");

        assertEquals("屏还开着，不能一个动作都不给", true, !out.isEmpty());
        assertNotNull("confirm 是这里唯一合法的前进方向", first(out, ActionTypes.CONFIRM_HAND_SELECT));
        assertEquals("取消在这一格必然被拒", 0, count(out, ActionTypes.CANCEL_HAND_SELECT));
    }

    /** "Up to N" picks: confirm and cancel are both available while nothing is selected. */
    @Test
    public void upToSelectionCanConfirmRightAway() {
        List<ActionSpec> out = new java.util.ArrayList<ActionSpec>();
        ActionPlanner.handSelectActions(out, hand(card("u1", "打击", false)), 2, 0, false, false,
                true, "");

        assertEquals(1, count(out, ActionTypes.CHOOSE_HAND_CARDS));
        assertEquals(1, count(out, ActionTypes.CONFIRM_HAND_SELECT));
        assertEquals(1, count(out, ActionTypes.CANCEL_HAND_SELECT));
    }

    /** With a selection the same click applies it, so only confirm is honest. */
    @Test
    public void upToSelectionDropsCancelOnceSomethingIsPicked() {
        List<ActionSpec> out = new java.util.ArrayList<ActionSpec>();
        ActionPlanner.handSelectActions(out, hand(card("u1", "打击", true)), 2, 1, false, false,
                true, "");

        assertEquals(1, count(out, ActionTypes.CONFIRM_HAND_SELECT));
        assertEquals("cancel 会把已选的牌一起结算", 0, count(out, ActionTypes.CANCEL_HAND_SELECT));
    }

    /** canPickZero (e.g. "you may exhaust a card"): confirm without picking anything. */
    @Test
    public void canPickZeroOffersConfirmWithoutPicking() {
        List<ActionSpec> out = new java.util.ArrayList<ActionSpec>();
        ActionPlanner.handSelectActions(out, hand(card("u1", "打击", false)), 1, 0, true, false,
                false, "");

        assertEquals(1, count(out, ActionTypes.CONFIRM_HAND_SELECT));
        assertEquals(1, count(out, ActionTypes.CANCEL_HAND_SELECT));
    }

    /** A defensive shape: no hand at all still yields a way out instead of an empty list. */
    @Test
    public void emptyHandStillOffersTheFallbackCancel() {
        List<ActionSpec> out = new java.util.ArrayList<ActionSpec>();
        ActionPlanner.handSelectActions(out, hand(), 1, 0, false, false, false, "");

        assertEquals(0, count(out, ActionTypes.CHOOSE_HAND_CARDS));
        assertEquals(1, count(out, ActionTypes.CANCEL_HAND_SELECT));
    }

    /** Action ids stay 1..N and the whole list is executable by a naive client. */
    @Test
    public void idsAreSequential() {
        List<ActionSpec> out = new java.util.ArrayList<ActionSpec>();
        ActionPlanner.handSelectActions(out, hand(
                card("u1", "打击", false), card("u2", "防御", false)), 2, 0, true, false, false, "");
        for (int i = 0; i < out.size(); i++) {
            assertEquals("a" + (i + 1), out.get(i).id);
        }
    }

    /** Guards against an accidental regression to "proceed is the universal escape". */
    @Test
    public void handSelectNeverAdvertisesProceed() {
        List<ActionSpec> out = new java.util.ArrayList<ActionSpec>();
        ActionPlanner.handSelectActions(out, hand(card("u1", "打击", false)), 1, 0, true, false,
                true, "");
        for (ActionSpec spec : out) {
            if (ActionTypes.PROCEED.equals(spec.type)) {
                fail("hand_select 不该出现 proceed");
            }
        }
    }

}
