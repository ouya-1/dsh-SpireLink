package spirelink.actions;

/** Pending card-reward pick; the hover is forced inside AbstractCard.update by a patch. */
public final class RewardPick {

    public static final long TIMEOUT_MILLIS = 4000L;

    private static volatile String targetUuid;
    private static volatile long deadline;

    private RewardPick() {
    }

    public static synchronized void request(String uuid) {
        targetUuid = uuid;
        deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
    }

    public static boolean isPending() {
        if (targetUuid == null) {
            return false;
        }
        if (System.currentTimeMillis() > deadline) {
            clear();
            return false;
        }
        return true;
    }

    public static boolean matches(String uuid) {
        return isPending() && targetUuid.equals(uuid);
    }

    public static synchronized void clear() {
        targetUuid = null;
        deadline = 0L;
    }
}
