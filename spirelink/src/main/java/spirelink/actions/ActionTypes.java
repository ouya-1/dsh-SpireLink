package spirelink.actions;

/** Action {@code type} names (design 8.1 + P1 opening chain). */
public final class ActionTypes {

    // main menu family
    public static final String OPEN_MODE_SELECT = "open_mode_select";
    public static final String CONTINUE_RUN = "continue_run";
    public static final String ABANDON_RUN = "abandon_run";
    public static final String CHOOSE_MODE = "choose_mode";
    public static final String CHOOSE_CHARACTER = "choose_character";
    public static final String EMBARK = "embark";
    /**
     * Sets the ascension level on the character-select screen ({@code level=0} means "ascension off").
     * Vanilla only exposes this through the little left/right arrows; the value is applied to
     * {@code AbstractDungeon.ascensionLevel} by {@code CharacterSelectScreen}'s own confirm handler,
     * so the agent has to be able to set it before {@code embark}.
     */
    public static final String CHOOSE_ASCENSION = "choose_ascension";
    /**
     * In-run "Abandon Run" (the one behind the pause menu). Deletes the save and goes back to the
     * main menu, so the agent can walk away from a run it judges lost instead of feeding it.
     */
    public static final String ABANDON_MID_RUN = "abandon_mid_run";
    public static final String BACK = "back";
    public static final String DISMISS_TIP = "dismiss_tip";
    /** Main-menu "abandon this run?" confirm popup (yes/no). */
    public static final String CONFIRM_ABANDON = "confirm_abandon";
    public static final String CANCEL_ABANDON = "cancel_abandon";

    // run
    public static final String CHOOSE_NEOW_REWARD = "choose_neow_reward";
    public static final String PROCEED = "proceed";
    public static final String CHOOSE_MAP_NODE = "choose_map_node";

    // combat (P2)
    public static final String PLAY_CARD = "play_card";
    public static final String END_TURN = "end_turn";
    public static final String USE_POTION = "use_potion";
    public static final String DISCARD_POTION = "discard_potion";
    public static final String OPEN_CHEST = "open_chest";
    public static final String TAKE_CARD = "take_card";
    public static final String SKIP_CARD = "skip_card";
    public static final String CLAIM_RELIC = "claim_relic";
    public static final String SKIP_RELIC = "skip_relic";
    /** Internal (never advertised): re-checks a claim and falls back to a direct pickup. */
    public static final String VERIFY_CLAIM_RELIC = "verify_claim_relic";
    /** Internal: re-triggers "back to main menu" if the win/death screen did not leave. */
    public static final String VERIFY_RUN_OVER = "verify_run_over";
    /** Internal: post-checks a dev-created fight after BattleStartEffect has run. */
    public static final String VERIFY_ENCOUNTER = "verify_encounter";
    public static final String REST = "rest";
    public static final String SMITH = "smith";
    public static final String LIFT = "lift";
    public static final String TOKE = "toke";
    public static final String BUY = "buy";
    public static final String LEAVE = "leave";
    public static final String CHOOSE_OPTION = "choose_option";
    public static final String EVENT_CONTINUE = "event_continue";
    public static final String CHOOSE_GRID_CARDS = "choose_grid_cards";
    public static final String CONFIRM_GRID = "confirm_grid";
    public static final String CANCEL_GRID = "cancel_grid";
    /** GremlinMatchGame: flip the card at {@code index}. */
    public static final String CHOOSE_MATCH_CARD = "choose_match_card";
    /** ShopRoom: open the merchant screen (vanilla needs a click on the merchant for this). */
    public static final String OPEN_SHOP = "open_shop";
    /** Shop purge service: opens the purge grid; vanilla takes the gold on confirm. */
    public static final String PURGE_CARD = "purge_card";
    public static final String TAKE_REWARD = "take_reward";
    public static final String CHOOSE_HAND_CARDS = "choose_hand_cards";
    public static final String CONFIRM_HAND_SELECT = "confirm_hand_select";
    public static final String CANCEL_HAND_SELECT = "cancel_hand_select";

    /**
     * Every type the executor knows (B2-12).
     *
     * <p>An unknown type used to fall through the dispatch chain into the catch-all and come back as
     * {@code ILLEGAL_ACTION}, even though the docs/samples promise {@code UNKNOWN_ACTION_TYPE}. The
     * gate now answers that code first — but only for a type that is really not in this set, so a
     * known type in the wrong ui keeps its {@code ILLEGAL_ACTION}.</p>
     */
    public static final java.util.Set<String> ALL = java.util.Collections.unmodifiableSet(
            new java.util.LinkedHashSet<String>(java.util.Arrays.asList(
                    OPEN_MODE_SELECT, CONTINUE_RUN, ABANDON_RUN, CHOOSE_MODE, CHOOSE_CHARACTER,
                    EMBARK, CHOOSE_ASCENSION, ABANDON_MID_RUN, BACK, DISMISS_TIP, CONFIRM_ABANDON,
                    CANCEL_ABANDON,
                    CHOOSE_NEOW_REWARD, PROCEED, CHOOSE_MAP_NODE,
                    PLAY_CARD, END_TURN, USE_POTION, DISCARD_POTION, OPEN_CHEST, TAKE_CARD,
                    SKIP_CARD, CLAIM_RELIC, SKIP_RELIC, VERIFY_CLAIM_RELIC, VERIFY_RUN_OVER,
                    VERIFY_ENCOUNTER, REST, SMITH, LIFT, TOKE, BUY, LEAVE, CHOOSE_OPTION,
                    EVENT_CONTINUE, CHOOSE_GRID_CARDS, CONFIRM_GRID, CANCEL_GRID,
                    CHOOSE_MATCH_CARD, OPEN_SHOP, PURGE_CARD, TAKE_REWARD, CHOOSE_HAND_CARDS,
                    CONFIRM_HAND_SELECT, CANCEL_HAND_SELECT)));

    /** True when {@code type} is an action this mod implements. */
    public static boolean isKnown(String type) {
        return type != null && ALL.contains(type);
    }

    private ActionTypes() {
    }
}
