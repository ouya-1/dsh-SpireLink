package spirelink.actions;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import spirelink.state.UiNames;

/**
 * Single source of truth for <b>"which action types may run in which ui"</b>.
 *
 * <p>Two issues had the same root cause: the executor happily ran an action the planner never
 * advertised for the current screen.</p>
 * <ul>
 *   <li>issue #70 — {@code act{type:"proceed"}} on the main menu ran into
 *       {@code AbstractDungeon.getCurrRoom()} with no {@code currMapNode} and surfaced a
 *       {@code NullPointerException} as {@code INTERNAL};</li>
 *   <li>issue #57 — {@code act{type:"proceed"}} while {@code ui=hand_select} fell through to the
 *       generic "close the current screen" branch and silently tore the card-select screen down.</li>
 * </ul>
 *
 * <p>So the table here is the one place that decides. {@link ActionExecutor#execute} consults it
 * before it touches any game state, which makes every rejection a pure business error
 * ({@code ILLEGAL_ACTION} / {@code HAND_SELECT_OPEN}) instead of a half-applied action or an NPE.</p>
 *
 * <p>Deliberately pure: no game classes, no {@code AbstractDungeon} reads. The runtime facts are
 * handed in as strings through {@link Facts}, so the whole matrix is unit-testable with
 * {@code mvn test} (see {@code ActionLegalityTest}).</p>
 *
 * <p>The table is a <b>superset</b> of what {@link ActionPlanner} advertises: an entry means "the
 * executor has a branch that can carry this action out safely in that ui". Advertised-but-missing
 * would be an I4 violation, unadvertised-but-allowed is only extra freedom for hand-written
 * clients. UIs this class does not model (e.g. {@code unknown:SOMETHING}) return {@code null} from
 * {@link #allowedFor(String)} and are not policed at all — fail open, never fail destructive.</p>
 */
public final class ActionLegality {

    /**
     * Module-internal actions: scheduled by {@link DelayedActions} and never advertised. They must
     * always be allowed because the ui can change between scheduling and firing.
     */
    private static final Set<String> INTERNAL = unmodifiable(
            ActionTypes.VERIFY_CLAIM_RELIC,
            ActionTypes.VERIFY_RUN_OVER,
            ActionTypes.VERIFY_ENCOUNTER);

    /**
     * Actions that cannot hurt in any ui: each one re-checks its own preconditions and answers with
     * a business error code, so gating them per-ui would only take an escape hatch away.
     */
    private static final Set<String> UNIVERSAL = unmodifiable(ActionTypes.ABANDON_MID_RUN);

    /** ui name -> action types that may run there. A missing key means "not modelled". */
    private static final Map<String, Set<String>> MATRIX = new LinkedHashMap<String, Set<String>>();

    static {
        // ---- main-menu family
        // Issue #91: the main menu has no "back" - the old entry made act{back} answer ok/queued and
        // locked the in-flight watchdog for 3 s while nothing happened. back stays legal where it
        // really exists (mode select / character select).
        put("main_menu", ActionTypes.OPEN_MODE_SELECT, ActionTypes.CONTINUE_RUN,
                ActionTypes.ABANDON_RUN, ActionTypes.CONFIRM_ABANDON, ActionTypes.CANCEL_ABANDON);
        put("mode_select", ActionTypes.CHOOSE_MODE, ActionTypes.BACK);
        put("char_select", ActionTypes.CHOOSE_CHARACTER, ActionTypes.CHOOSE_ASCENSION,
                ActionTypes.EMBARK, ActionTypes.BACK);
        put("tutorial_tip", ActionTypes.DISMISS_TIP);

        // ---- run
        put("neow", ActionTypes.CHOOSE_NEOW_REWARD, ActionTypes.PROCEED);
        put("map", ActionTypes.CHOOSE_MAP_NODE, ActionTypes.PROCEED,
                ActionTypes.ABANDON_MID_RUN);
        put("combat", ActionTypes.PLAY_CARD, ActionTypes.END_TURN, ActionTypes.USE_POTION,
                ActionTypes.DISCARD_POTION);
        // Issue #57: the card-select screen is the *only* thing that may be touched while it is up.
        // No proceed, no play_card, no end_turn — the screen exists because the game is waiting for
        // a card choice and closing it behind the effect's back loses the choice.
        put("hand_select", ActionTypes.CHOOSE_HAND_CARDS, ActionTypes.CONFIRM_HAND_SELECT,
                ActionTypes.CANCEL_HAND_SELECT);
        put("grid", ActionTypes.CHOOSE_GRID_CARDS, ActionTypes.CONFIRM_GRID,
                ActionTypes.CANCEL_GRID);

        // ---- reward screens
        // #116a: a potion reward cannot be taken while the belt is full, and both the state's
        // blocked_reason and the POTION_SLOT_FULL rejection answer "先丢掉一瓶才能拿". That instruction
        // was not executable: discard_potion was not a combat_reward action at all. It is now - the
        // planner advertises it (only for slots the executor would accept) and the executor runs it.
        put("combat_reward", ActionTypes.TAKE_REWARD, ActionTypes.DISCARD_POTION,
                ActionTypes.PROCEED);
        // C7-01: no proceed here. The planner never advertises one (skipping the pick *is*
        // skip_card), yet the matrix let proceed through and ActionExecutor#proceed() forwarded it
        // to skipCard() — an action the agent could run while legal_actions never listed it. The
        // screen keeps exactly the two actions the planner offers, so "advertised" and "executable"
        // are the same set (未广告动作一律 ILLEGAL, C7-03).
        put("card_reward", ActionTypes.TAKE_CARD, ActionTypes.SKIP_CARD);
        // boss_reward keeps proceed for now: it has the same "executor has a branch the planner does
        // not advertise" shape (proceed == skip_relic there), but C7-01 only reported card_reward and
        // removing it is a behaviour change nobody has re-tested on the real machine yet.
        put("boss_reward", ActionTypes.CLAIM_RELIC, ActionTypes.SKIP_RELIC, ActionTypes.PROCEED);

        // ---- rooms
        put("rest", ActionTypes.REST, ActionTypes.SMITH, ActionTypes.TOKE, ActionTypes.LIFT,
                ActionTypes.PROCEED);
        put("shop", ActionTypes.OPEN_SHOP, ActionTypes.BUY, ActionTypes.PURGE_CARD,
                ActionTypes.LEAVE, ActionTypes.PROCEED);
        put("shop_room", ActionTypes.OPEN_SHOP, ActionTypes.BUY, ActionTypes.PURGE_CARD,
                ActionTypes.LEAVE, ActionTypes.PROCEED);
        put("event", ActionTypes.CHOOSE_OPTION, ActionTypes.EVENT_CONTINUE,
                ActionTypes.CHOOSE_MATCH_CARD, ActionTypes.PROCEED);
        put("treasure", ActionTypes.OPEN_CHEST, ActionTypes.PROCEED);
        put("boss_cleared", ActionTypes.PROCEED);

        // ---- run-over / plain "leave this screen" states
        put("death", ActionTypes.PROCEED);
        put("victory", ActionTypes.PROCEED);
        put("view", ActionTypes.PROCEED);
        put("credits", ActionTypes.PROCEED);
        // Issue #83: ui=transform is vanilla's "transform a card" picker, which runs on the grid
        // screen (GridCardSelectScreen.forTransform). The grid actions must be allowed so the choice
        // can actually be made; proceed stays as the last-resort exit so transform can never be a
        // dead end (unlike hand_select, where #57 proved an accidental close loses the choice).
        put("transform", ActionTypes.CHOOSE_GRID_CARDS, ActionTypes.CONFIRM_GRID,
                ActionTypes.CANCEL_GRID, ActionTypes.PROCEED);
        put("door_unlock", ActionTypes.PROCEED);
        put("idle", ActionTypes.PROCEED);
        put("other", ActionTypes.PROCEED);
    }

    private ActionLegality() {
    }

    private static void put(String ui, String... types) {
        MATRIX.put(ui, unmodifiable(types));
    }

    private static Set<String> unmodifiable(String... types) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(Arrays.asList(types)));
    }

    /**
     * The facts the matrix needs, as plain strings so nothing here touches the game.
     *
     * <p>Built from {@code AbstractDungeon} by {@code ActionExecutor#facts()}; every field is
     * nullable except the booleans.
     */
    public static final class Facts {

        /** A tutorial tip ({@code AbstractDungeon.ftue}) owns the screen. */
        public final boolean tutorialTip;
        /** {@code CardCrawlGame.mode == GAMEPLAY && AbstractDungeon.player != null}. */
        public final boolean inGame;
        /** {@code CardCrawlGame.mainMenuScreen.screen.name()} or {@code null}. */
        public final String menuScreen;
        /** {@code AbstractDungeon.screen.name()} or {@code null}. */
        public final String screen;
        /** Current room simple class name or {@code null}. */
        public final String roomClass;
        /** Current room phase name or {@code null}. */
        public final String roomPhase;
        /** {@code AbstractDungeon.victoryScreen != null}. */
        public final boolean victoryScreen;

        public Facts(boolean tutorialTip, boolean inGame, String menuScreen, String screen,
                String roomClass, String roomPhase, boolean victoryScreen) {
            this.tutorialTip = tutorialTip;
            this.inGame = inGame;
            this.menuScreen = menuScreen;
            this.screen = screen;
            this.roomClass = roomClass;
            this.roomPhase = roomPhase;
            this.victoryScreen = victoryScreen;
        }

        /** Convenience for tests: the main-menu situation for a given menu screen name. */
        public static Facts menu(String menuScreenName) {
            return new Facts(false, false, menuScreenName, "NONE", null, null, false);
        }

        /** Convenience for tests: an in-run situation with no screen of its own. */
        public static Facts room(String roomClass, String roomPhase) {
            return new Facts(false, true, null, "NONE", roomClass, roomPhase, false);
        }

        /** Convenience for tests: an in-run situation with a screen on top. */
        public static Facts screen(String screenName, String roomClass, String roomPhase) {
            return new Facts(false, true, null, screenName, roomClass, roomPhase, false);
        }
    }

    /**
     * Mirrors the fixed priority of {@code StateBuilder#deriveUi}: tutorial tip, then main menu,
     * then the screen, then the room.
     *
     * @return a ui name, never {@code null}
     */
    public static String uiOf(Facts facts) {
        if (facts == null) {
            return "other";
        }
        if (facts.tutorialTip) {
            return "tutorial_tip";
        }
        if (!facts.inGame) {
            return UiNames.ofMenu(facts.menuScreen);
        }
        if (facts.screen != null) {
            String mapped = UiNames.ofScreen(facts.screen);
            if (mapped != null) {
                return mapped;
            }
        }
        if (facts.roomClass == null) {
            if (facts.victoryScreen) {
                return "victory";
            }
            return "other";
        }
        switch (facts.roomClass) {
            case "VictoryRoom":
                return "victory";
            default:
                break;
        }
        if ("COMBAT".equals(facts.roomPhase)) {
            return "combat";
        }
        switch (facts.roomClass) {
            case "NeowRoom":
                return "neow";
            case "EventRoom":
                return "event";
            case "RestRoom":
                return "rest";
            case "TreasureRoom":
            case "TreasureRoomBoss":
                return "treasure";
            case "ShopRoom":
                return "SHOP".equals(facts.screen) ? "shop" : "shop_room";
            case "MonsterRoomBoss":
                return "COMPLETE".equals(facts.roomPhase) ? "boss_cleared" : "idle";
            default:
                return "idle";
        }
    }

    /**
     * @return the action types allowed in {@code ui}, or {@code null} when this ui is not modelled
     *         (callers must then allow everything)
     */
    public static Set<String> allowedFor(String ui) {
        if (ui == null) {
            return null;
        }
        return MATRIX.get(ui);
    }

    /** True when {@code type} may run in {@code ui}; unmodelled uis allow everything. */
    public static boolean allows(String ui, String type) {
        if (type == null) {
            return true;
        }
        if (INTERNAL.contains(type) || UNIVERSAL.contains(type)) {
            return true;
        }
        Set<String> allowed = allowedFor(ui);
        if (allowed == null) {
            return true;
        }
        return allowed.contains(type);
    }

    /**
     * @return {@code null} when the action may run, otherwise a human sentence explaining why not
     *         and what is allowed instead
     */
    public static String check(String ui, String type) {
        return check(ui, type, null);
    }

    /**
     * @param advertised what the planner is advertising right now, or {@code null} to fall back to
     *                   the static matrix
     */
    public static String check(String ui, String type, java.util.Collection<String> advertised) {
        if (allows(ui, type)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ui=").append(ui).append(" 下不能执行 ").append(type).append("（可用: ")
                .append(describeAdvertised(ui, advertised)).append("）");
        sb.append(hintFor(ui, type));
        return sb.toString();
    }

    /**
     * The "可用" list of a rejection must be what the agent can actually do (issue #91).
     *
     * <p>The static matrix is a superset (it also holds the internal/universal entries), so printing
     * it listed actions that are not on offer — {@code back} on the main menu was the reported one.
     * When the caller passes the planner's set, that is what gets printed; otherwise the matrix is
     * still a sane fallback for callers that have no game to read.</p>
     */
    static String describeAdvertised(String ui, java.util.Collection<String> advertised) {
        if (advertised == null || advertised.isEmpty()) {
            return describe(ui);
        }
        StringBuilder sb = new StringBuilder();
        for (String type : advertised) {
            if (type == null || type.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(type);
        }
        return sb.length() == 0 ? describe(ui) : sb.toString();
    }

    /**
     * Per-action tail sentence (issue #91): the old message ended with "主菜单没有可以 proceed 的
     * 对象" no matter which action had been rejected, so a rejected {@code back} explained proceed.
     */
    static String hintFor(String ui, String type) {
        if (type == null) {
            return "";
        }
        if ("main_menu".equals(ui)) {
            if (ActionTypes.BACK.equals(type)) {
                return "；主菜单没有上一级可回（back 只在选模式/选人界面才有意义）";
            }
            if (ActionTypes.PROCEED.equals(type)) {
                return "；主菜单没有可以 proceed 的对象";
            }
            return "；主菜单只能使用上面列出的入口";
        }
        if ("hand_select".equals(ui) && ActionTypes.PROCEED.equals(type)) {
            return "；选牌界面必须 choose_hand_cards / confirm_hand_select / cancel_hand_select，"
                    + "proceed 会把这次选择吞掉";
        }
        if ("grid".equals(ui) || "transform".equals(ui)) {
            return "；选牌网格里请用 choose_grid_cards / confirm_grid / cancel_grid";
        }
        if ("treasure".equals(ui) && ActionTypes.PROCEED.equals(type)) {
            return "；宝箱还没开，先 open_chest";
        }
        return "；请改用上面列出的动作";
    }

    /**
     * Issue #92: in a treasure room {@code proceed} before {@code open_chest} closes the room and
     * silently destroys the chest reward (the room is marked complete, the rewards are never shown).
     *
     * <p>Pure: the chest state is handed in. {@code chestNeedsOpening} must be the very condition the
     * planner uses to advertise {@code open_chest} — "the chest is untouched: not open, no screen up,
     * no rewards pending" — so the two can never disagree and an advertised {@code proceed} is always
     * executable. It is {@code false} for every other room type.</p>
     *
     * @return {@code null} when the action may run, otherwise the reason
     */
    public static String treasureRule(String ui, String type, boolean chestNeedsOpening) {
        if ("treasure".equals(ui) && ActionTypes.PROCEED.equals(type) && chestNeedsOpening) {
            return "宝箱还没开，先 open_chest（直接 proceed 会丢掉宝箱奖励）";
        }
        return null;
    }

    /**
     * B2-12: an action type the mod does not implement must answer {@code UNKNOWN_ACTION_TYPE}
     * (what the docs/samples promise), not {@code ILLEGAL_ACTION} from the legality matrix.
     *
     * @return the error code when {@code type} is unknown, {@code null} when it is implemented
     */
    public static String unknownTypeCode(String type) {
        if (type == null) {
            return null;
        }
        return ActionTypes.isKnown(type) ? null : ErrorCodes.UNKNOWN_ACTION_TYPE;
    }

    /**
     * Machine code for a rejection.
     *
     * <p>{@code HAND_SELECT_OPEN} for {@code hand_select} because design §8.1 and the issue both
     * name that code; {@code ILLEGAL_ACTION} everywhere else.</p>
     */
    public static String violationCode(String ui, String type) {
        if ("hand_select".equals(ui) && type != null) {
            return ErrorCodes.HAND_SELECT_OPEN;
        }
        return ErrorCodes.ILLEGAL_ACTION;
    }

    /** The action types allowed in {@code ui} as one comma separated line (for messages/tests). */
    public static String describe(String ui) {
        Set<String> allowed = allowedFor(ui);
        if (allowed == null) {
            return "（该 ui 未建模，任何动作都放行）";
        }
        StringBuilder sb = new StringBuilder();
        for (String type : allowed) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(type);
        }
        return sb.toString();
    }

    /** Every ui this class polices, in declaration order. */
    public static Set<String> modelledUis() {
        return Collections.unmodifiableSet(MATRIX.keySet());
    }
}
