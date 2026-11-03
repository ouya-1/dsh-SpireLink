package spirelink.actions;

/**
 * Can this card-select grid be cancelled? (issue #96)
 *
 * <p>Vanilla {@code GridCardSelectScreen.open(group, numCards, tipMsg, forUpgrade, forTransform,
 * canCancel, forPurge)} ends with:</p>
 * <pre>
 *   if ((forUpgrade || forTransform || forPurge || previousScreen == SHOP) &amp;&amp; canCancel) {
 *       AbstractDungeon.overlayMenu.cancelButton.show(TEXT[1]);
 *   }
 *   if (!canCancel) {
 *       AbstractDungeon.overlayMenu.cancelButton.hide();
 *   }
 * </pre>
 * <p>so {@code canCancel} is the single flag that decides whether the grid has a cancel button at
 * all. Checked against the shipped jar with {@code javap -c}:</p>
 * <ul>
 *   <li>Transmogrifier's transform grid: {@code open(deck, 2, tip, false, true, false, false)}
 *       → {@code canCancel = false}: a confirm-only grid, no CancelButton.</li>
 *   <li>Designer's upgrade/purge grids: {@code canCancel = false} as well.</li>
 *   <li>Campfire smith: {@code open(deck, 1, tip, true, false, true, false)} → {@code canCancel =
 *       true}, which is why #90's campfire cancel keeps working.</li>
 * </ul>
 *
 * <p>The bug this class prevents: advertising {@code cancel_grid} on a confirm-only grid. Our cancel
 * closes the screen with {@code closeCurrentScreen()}, and the event that opened the grid takes a
 * closed screen as "the player finished the selection" — Transmogrifier settled without transforming
 * a single card (deck 19 → 19, option gone, body text rewritten).</p>
 */
public final class GridCancelPolicy {

    private GridCancelPolicy() {
    }

    /**
     * @param canCancel     {@code GridCardSelectScreen.canCancel}
     * @param anySelectable there is still a card the player may pick
     * @param canConfirm    the confirm button would accept a click right now
     * @return true when a cancel may be advertised (and accepted)
     */
    public static boolean cancelAllowed(boolean canCancel, boolean anySelectable, boolean canConfirm) {
        if (canCancel) {
            return true;
        }
        // Confirm-only grid. The single exception is the pathological "nothing is clickable at all"
        // screen: there a cancel is the last way out of the ui, and a business error beats a frozen
        // agent. Everywhere else the confirm/choose actions are the way forward.
        return !anySelectable && !canConfirm;
    }

    /** Why a cancel was refused, for the rejection message (issue #96). */
    public static String confirmOnlyMessage() {
        return "这个选牌网格是确认式的（原版没有取消按钮）：请 choose_grid_cards / confirm_grid"
                + "（cancel 会被事件当成「已完成」结算）";
    }
}
