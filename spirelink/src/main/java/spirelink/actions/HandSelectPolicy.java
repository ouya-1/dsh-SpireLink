package spirelink.actions;

/**
 * Pure rules for {@code ui=hand_select} (issue #102): the <b>one</b> place that decides what
 * vanilla's {@code HandCardSelectScreen} would accept right now.
 *
 * <p>QA #102 found the two halves disagreeing: {@code legal_actions} advertised {@code confirm}
 * only in some states, while the executor accepted it always — clicking the screen's single button
 * even when vanilla's {@code update()} ignores that click. Nothing happened, the state signature
 * never moved, and the harness answered {@code queued} after 10.6 s and then refused every
 * following action for 13 s ({@code ACTION_IN_FLIGHT}).</p>
 *
 * <p>Checked against the shipped jar with {@code javap -c HandCardSelectScreen}:</p>
 * <ul>
 *   <li>{@code selectHoveredCard()} only runs when {@code numCardsToSelect > selectedCards.size()}
 *       — that is {@link #canPickMore};</li>
 *   <li>the confirm button's click branch closes the screen exactly when
 *       {@code (canPickZero && selected == 0) || anyNumber || upTo || selected == numCardsToSelect}
 *       — that is {@link #closesOnClick}. Every other click is a silent no-op.</li>
 * </ul>
 *
 * <p>{@code anyNumber} is a private field of the screen (there is no getter); the executor and the
 * planner read it through {@code spirelink.util.FieldKit} and hand it in here, so both sides see the
 * same value. A failed read is {@code false}, which is the conservative answer (fewer advertised
 * actions, never an advertised rejection).</p>
 *
 * <p><b>cancel</b> is implemented as "click the same button while nothing is selected": vanilla has
 * no second button on this screen. So a cancel can only ever work when a click with zero selected
 * closes it — {@link #canCancel}. Advertising it anywhere else (which is what the doc's "恒在"
 * promises today) would advertise a rejection, exactly the invariant this batch is repairing. With
 * cards already selected the same click would <i>apply</i> them, i.e. it is a confirm.</p>
 */
public final class HandSelectPolicy {

    private HandSelectPolicy() {
    }

    /**
     * Can one more card still be picked? Vanilla's {@code selectHoveredCard()} refuses unless
     * {@code numCardsToSelect > selectedCards.size()}.
     */
    public static boolean canPickMore(int numCardsToSelect, int selectedCount) {
        return numCardsToSelect > selectedCount;
    }

    /**
     * Can {@code wanted} more cards be selected on top of what is already picked?
     *
     * @param wanted how many <b>new</b> cards the request names (already selected ones do not count)
     */
    public static boolean canSelect(int numCardsToSelect, int selectedCount, int wanted) {
        return wanted > 0 && selectedCount + wanted <= numCardsToSelect;
    }

    /**
     * Would a click on the screen's confirm button close the screen? This is the exact
     * {@code HandCardSelectScreen.update()} condition, so it doubles as "is confirm executable".
     */
    public static boolean closesOnClick(int numCardsToSelect, int selectedCount, boolean canPickZero,
            boolean anyNumber, boolean upTo) {
        if (canPickZero && selectedCount == 0) {
            return true;
        }
        if (anyNumber || upTo) {
            return true;
        }
        return selectedCount == numCardsToSelect;
    }

    /** {@code confirm_hand_select} is accepted exactly when the click really closes the screen. */
    public static boolean canConfirm(int numCardsToSelect, int selectedCount, boolean canPickZero,
            boolean anyNumber, boolean upTo) {
        return closesOnClick(numCardsToSelect, selectedCount, canPickZero, anyNumber, upTo);
    }

    /**
     * {@code cancel_hand_select} is the same click with nothing selected. Accepting it while cards
     * are picked would silently confirm them instead of cancelling.
     */
    public static boolean canCancel(int numCardsToSelect, int selectedCount, boolean canPickZero,
            boolean anyNumber, boolean upTo) {
        return selectedCount == 0
                && closesOnClick(numCardsToSelect, 0, canPickZero, anyNumber, upTo);
    }

    /** Why {@code confirm_hand_select} was refused, and what to do instead. */
    public static String confirmRefusedMessage(int numCardsToSelect, int selectedCount) {
        return "现在点确认不会关掉这个选牌界面（要选够 " + numCardsToSelect + " 张，已选 "
                + selectedCount + " 张）：先用 choose_hand_cards 把牌选够";
    }

    /** Why {@code cancel_hand_select} was refused, and what to do instead. */
    public static String cancelRefusedMessage(int numCardsToSelect, int selectedCount,
            boolean canPickZero) {
        if (selectedCount > 0) {
            return "已经选了 " + selectedCount + " 张牌，这时候取消等于把它们确认掉（原版只有一个按钮）："
                    + "继续 choose_hand_cards 选到 " + numCardsToSelect + " 张，再用 confirm_hand_select";
        }
        return "这个选择必须选够 " + numCardsToSelect + " 张，原版没有取消按钮（canPickZero="
                + canPickZero + "）：选够后用 confirm_hand_select";
    }

    /** Why {@code choose_hand_cards} was refused (too many cards in one request). */
    public static String selectTooManyMessage(int numCardsToSelect, int selectedCount, int wanted) {
        return "这次请求要选 " + wanted + " 张，但只剩 " + Math.max(0, numCardsToSelect - selectedCount)
                + " 张可选（总共 " + numCardsToSelect + " 张，已选 " + selectedCount + " 张）";
    }
}
