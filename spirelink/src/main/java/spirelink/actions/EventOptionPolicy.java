package spirelink.actions;

/**
 * May this event dialog option be clicked? (issue #95)
 *
 * <p>Vanilla marks an option the player cannot take with {@code LargeDialogOptionButton.isDisabled}
 * (a locked option renders in the disabled colour and its click handler is skipped). ForgottenAltar
 * is the reported case: "【锁定】 需要：金神像" is disabled until the player owns Golden Idol.</p>
 *
 * <p>Ignoring the flag was expensive: the mod advertised {@code choose_option{index:0}} and executed
 * it, so the locked option really ran and swapped Burning Blood for Bloody Idol — an irreversible
 * change to the run (and a second attempt even handed out a Circlet). Planner and executor both
 * consult this predicate, so "advertised" and "executable" can never disagree.</p>
 */
public final class EventOptionPolicy {

    private EventOptionPolicy() {
    }

    /**
     * @param disabled  {@code LargeDialogOptionButton.isDisabled}
     * @param hasHitbox the button has a hitbox (a button without one cannot be clicked either)
     */
    public static boolean selectable(boolean disabled, boolean hasHitbox) {
        return !disabled && hasHitbox;
    }

    /** Rejection message for a hand-written client that sends a locked option anyway. */
    public static String lockedMessage(String msg) {
        String text = msg == null || msg.isEmpty() ? "" : "：" + msg;
        return "该选项已被锁定（不满足条件），不能执行" + text;
    }
}
