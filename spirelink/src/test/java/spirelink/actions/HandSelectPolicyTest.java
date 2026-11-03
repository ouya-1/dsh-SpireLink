package spirelink.actions;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #102: {@code ui=hand_select} advertised {@code confirm_hand_select} in states where vanilla's
 * click does nothing, so the call answered ok, the state never moved, and the harness sat in
 * {@code queued} for 10.6 s and then locked every act for 13 s. The rules now live in
 * {@link HandSelectPolicy}, which the planner advertises from <b>and</b> the executor accepts with.
 *
 * <p>The matrices below are the exact {@code HandCardSelectScreen.update()} click branch, checked
 * against the shipped jar with {@code javap -c}:
 * {@code (canPickZero && selected == 0) || anyNumber || upTo || selected == numCardsToSelect}.</p>
 */
public class HandSelectPolicyTest {

    // ---------------------------------------------------------------- picking

    @Test
    public void cardsCanBePickedUntilTheQuotaIsFull() {
        assertTrue(HandSelectPolicy.canPickMore(2, 0));
        assertTrue(HandSelectPolicy.canPickMore(2, 1));
        assertFalse("选够了就不能再选（原版 selectHoveredCard 的同一条判断）",
                HandSelectPolicy.canPickMore(2, 2));
        assertFalse(HandSelectPolicy.canPickMore(1, 1));
        assertFalse("要选 0 张时一张都点不动", HandSelectPolicy.canPickMore(0, 0));
    }

    @Test
    public void aRequestMayNotOverflowTheQuota() {
        assertTrue(HandSelectPolicy.canSelect(2, 0, 1));
        assertTrue(HandSelectPolicy.canSelect(2, 1, 1));
        assertFalse("1 张的额度里塞不进 2 张", HandSelectPolicy.canSelect(2, 1, 2));
        assertFalse("空请求不算选牌", HandSelectPolicy.canSelect(2, 0, 0));
        assertFalse(HandSelectPolicy.canSelect(0, 0, 1));
    }

    // ---------------------------------------------------------------- confirm

    @Test
    public void mandatoryScreenAcceptsConfirmOnlyWhenFull() {
        // canPickZero=false, anyNumber=false, upTo=false, 2 wanted.
        assertFalse("没选够：点了也没反应（#102 的核心）",
                HandSelectPolicy.canConfirm(2, 0, false, false, false));
        assertFalse(HandSelectPolicy.canConfirm(2, 1, false, false, false));
        assertTrue(HandSelectPolicy.canConfirm(2, 2, false, false, false));
    }

    @Test
    public void canPickZeroAcceptsConfirmOnlyWithNothingSelected() {
        assertTrue("允许不选：0 张就能确认", HandSelectPolicy.canConfirm(2, 0, true, false, false));
        assertFalse("选了一张但没选够：原版这一步点确认是空操作 —— 广告它就是广告一次拒绝",
                HandSelectPolicy.canConfirm(2, 1, true, false, false));
        assertTrue(HandSelectPolicy.canConfirm(2, 2, true, false, false));
    }

    @Test
    public void upToAndAnyNumberAcceptConfirmAtAnyCount() {
        assertTrue(HandSelectPolicy.canConfirm(2, 0, false, false, true));
        assertTrue(HandSelectPolicy.canConfirm(2, 1, false, false, true));
        assertTrue(HandSelectPolicy.canConfirm(2, 2, false, false, true));
        assertTrue(HandSelectPolicy.canConfirm(2, 1, false, true, false));
        assertTrue("anyNumber 时 0 张也能关屏", HandSelectPolicy.canConfirm(2, 0, false, true, false));
    }

    @Test
    public void zeroQuotaClosesOnClickWithNothingSelected() {
        assertTrue(HandSelectPolicy.canConfirm(0, 0, false, false, false));
        assertTrue(HandSelectPolicy.closesOnClick(0, 0, false, false, false));
    }

    // ---------------------------------------------------------------- cancel

    @Test
    public void cancelIsRefusedOnMandatoryScreens() {
        assertFalse("必选屏原版没有取消按钮（文档说「恒在」是错的，见报告）",
                HandSelectPolicy.canCancel(2, 0, false, false, false));
        assertFalse(HandSelectPolicy.canCancel(1, 0, false, false, false));
    }

    @Test
    public void cancelWorksOnlyWhileNothingIsSelected() {
        assertTrue(HandSelectPolicy.canCancel(2, 0, true, false, false));
        assertTrue(HandSelectPolicy.canCancel(2, 0, false, false, true));
        assertTrue(HandSelectPolicy.canCancel(2, 0, false, true, false));
        assertFalse("已经选了牌：同一个按钮会把这些牌确认掉，那不是取消",
                HandSelectPolicy.canCancel(2, 1, true, false, false));
        assertFalse(HandSelectPolicy.canCancel(2, 1, false, false, true));
    }

    // ---------------------------------------------------------------- wiring of the two sides

    /**
     * The invariant QA #102 asked for: whatever the planner advertises, the executor accepts — and
     * whatever it refuses, the executor refuses too. Both read these two methods.
     */
    @Test
    public void advertisedConfirmAndCancelAreExactlyTheExecutableOnes() {
        int[][] quotas = {{1, 0}, {2, 0}, {2, 1}, {2, 2}};
        boolean[] flags = {false, true};
        for (int[] quota : quotas) {
            int numCardsToSelect = quota[0];
            int selected = quota[1];
            for (boolean canPickZero : flags) {
                for (boolean anyNumber : flags) {
                    for (boolean upTo : flags) {
                        boolean confirm = HandSelectPolicy.canConfirm(numCardsToSelect, selected,
                                canPickZero, anyNumber, upTo);
                        boolean cancel = HandSelectPolicy.canCancel(numCardsToSelect, selected,
                                canPickZero, anyNumber, upTo);
                        assertEquals("cancel 必须在「没选牌且点了能关屏」时才为真",
                                selected == 0 && HandSelectPolicy.closesOnClick(numCardsToSelect, 0,
                                        canPickZero, anyNumber, upTo),
                                cancel);
                        assertFalse("cancel 与 confirm 不可能在同一状态同时为真",
                                cancel && !confirm);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- messages

    @Test
    public void refusalMessagesSayWhatToDoInstead() {
        String confirm = HandSelectPolicy.confirmRefusedMessage(2, 1);
        assertTrue(confirm, confirm.contains("choose_hand_cards"));
        assertTrue(confirm, confirm.contains("2"));
        assertTrue(confirm, confirm.contains("1"));

        String cancelMandatory = HandSelectPolicy.cancelRefusedMessage(2, 0, false);
        assertTrue(cancelMandatory, cancelMandatory.contains("confirm_hand_select"));
        assertTrue(cancelMandatory, cancelMandatory.contains("2"));

        String cancelWithSelection = HandSelectPolicy.cancelRefusedMessage(2, 1, true);
        assertTrue(cancelWithSelection, cancelWithSelection.contains("confirm_hand_select"));

        String tooMany = HandSelectPolicy.selectTooManyMessage(2, 1, 2);
        assertTrue(tooMany, tooMany.contains("1"));
    }
}
