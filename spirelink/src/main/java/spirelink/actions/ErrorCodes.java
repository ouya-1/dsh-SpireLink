package spirelink.actions;

/**
 * Machine-readable {@code act} error codes (design 5, P1 + P2 tables). The agent is expected to
 * branch on these, never on the human message.
 */
public final class ErrorCodes {

    /** The request carried an old {@code state_version}; the payload is attached with fresh actions. */
    public static final String STALE_VERSION = "STALE_VERSION";
    /** Game is not in a state where an action may run (animation, enemy turn, main thread busy). */
    public static final String NOT_READY = "NOT_READY";
    /** The action type exists but is not offered in the current {@code ui}. */
    public static final String ILLEGAL_ACTION = "ILLEGAL_ACTION";
    /** Payload shape is wrong (missing/ill-typed field). */
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String UNKNOWN_ACTION_TYPE = "UNKNOWN_ACTION_TYPE";

    // ---- P2: combat
    public static final String CARD_NOT_IN_HAND = "CARD_NOT_IN_HAND";
    public static final String CARD_NOT_PLAYABLE = "CARD_NOT_PLAYABLE";
    public static final String NOT_ENOUGH_ENERGY = "NOT_ENOUGH_ENERGY";
    public static final String TARGET_REQUIRED = "TARGET_REQUIRED";
    public static final String TARGET_INVALID = "TARGET_INVALID";
    public static final String POTION_SLOT_EMPTY = "POTION_SLOT_EMPTY";
    /** Buying a potion with a full belt. */
    public static final String POTION_SLOT_FULL = "POTION_SLOT_FULL";
    public static final String POTION_UNUSABLE = "POTION_UNUSABLE";
    public static final String POTION_UNDISCARDABLE = "POTION_UNDISCARDABLE";
    public static final String HAND_SELECT_OPEN = "HAND_SELECT_OPEN";

    private ErrorCodes() {
    }
}
