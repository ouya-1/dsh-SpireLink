package spirelink.state;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

/**
 * "ready" judgement (design doc 5): the game is at rest and a snapshot is meaningful.
 *
 * <p>{@link #evaluate(Inputs)} is pure and unit tested with a truth table;
 * {@link #probe()} is the only game-facing part.
 *
 * <p>Ready means:
 * <ul>
 *   <li>not in a scene fade;</li>
 *   <li>in a run, with a player, and the action manager queue / current action / card queue are
 *       all empty;</li>
 *   <li>if a screen is up, it is one that waits for a human choice (reward, shop, map, grid, ...),
 *       not a blocking popup (tutorial, door unlock, no-interact, credits).</li>
 * </ul>
 */
public final class StableGate {

    public enum Mode {
        /** Main menu / character select: no run loaded. */
        MENU,
        /** Splash, dungeon transition: do not read. */
        TRANSITION,
        GAMEPLAY
    }

    public enum ScreenKind {
        /** No screen up. */
        NONE,
        /** A screen that is itself waiting for a choice: reward, shop, map, grid, death, ... */
        CHOICE,
        /** A screen/animation that blocks play: tutorial popup, door unlock, no-interact, ... */
        BLOCKING
    }

    /** Plain-value inputs so the truth table can be tested without a game. */
    public static final class Inputs {
        public Mode mode = Mode.MENU;
        public boolean inGame;
        public boolean playerPresent;
        public boolean fading;
        /**
         * Issue #121 · C12-01: a save is being loaded (continue_run), so the main menu is still on
         * screen but it is on its way out - no menu action may be advertised.
         */
        public boolean loading;
        /**
         * Issue #123 · S2: we just abandoned the run, so the dungeon still in memory is on its way
         * out - its screens must not be described or advertised.
         */
        public boolean leavingRun;
        public boolean blockingPopupOpen;
        public boolean actionsEmpty = true;
        public boolean currentActionNull = true;
        public boolean cardQueueEmpty = true;
        public boolean preTurnActionsEmpty = true;
        public boolean screenUp;
        /** An event room is still typing its text, so it offers no choice yet. */
        public boolean eventTextTyping;
        /** Combat is won but the room has not switched to rewards yet. */
        public boolean battleOver;
        /**
         * Issue #125 · C16-04: the combat room is up but still initializing - at least one live monster
         * still carries the engine's placeholder intent ({@code Intent.DEBUG}), which is exactly the
         * state where the hand is empty and the energy panel has not been filled yet.
         */
        public boolean combatSettling;
        /** The player is inside VictoryRoom: the end-of-run decision is always available. */
        public boolean victoryRoom;
        public ScreenKind screen = ScreenKind.NONE;
        public boolean dungeonBeaten;
        /** Main-thread frame counter, used to tell "busy" from "wedged with a live render loop". */
        public long frames;
        public int actionCount;
        public String currentActionName;
        /** Set by {@link #evaluate} when the queue has been stuck for too long. */
        public boolean actionsStuck;
    }

    public static final class Decision {
        public final boolean ready;
        public final String reason;
        /** True when the action queue is stuck: reads stay available, actions do not. */
        public final boolean actionsStuck;

        Decision(boolean ready, String reason) {
            this(ready, reason, false);
        }

        Decision(boolean ready, String reason, boolean actionsStuck) {
            this.ready = ready;
            this.reason = reason;
            this.actionsStuck = actionsStuck;
        }

        @Override
        public String toString() {
            return (ready ? "ready" : "not-ready") + "(" + reason + ")";
        }
    }

    /** How long a non-empty action queue may block reads before it is called stuck. */
    private static final long STUCK_MS = 8000L;
    private static long stuckSinceMs;
    private static long stuckFrames;
    /** Issue #125 · C16-04: how long the engine's placeholder intent may hold readiness before we give up. */
    private static final long SETTLING_MAX_MS = 4000L;
    private static long settlingSinceMs;

    private StableGate() {
    }

    public static Decision evaluate(Inputs in) {
        if (in == null) {
            return new Decision(false, "no_input");
        }
        // Issue #121 · C12-01: checked before anything else (before the menu branch!) because the loading
        // window looks exactly like "the main menu is up and idle" from the outside: mode is still
        // MENU/CHAR_SELECT, the gate would answer ready:true("menu") and every menu button - including
        // continue_run - would be advertised again while the click that started this was already spent.
        if (in.loading) {
            return new Decision(false, "loading");
        }
        // Issue #123 · S2: abandoning a run keeps mode=GAMEPLAY and the old room/map alive for a few
        // frames; without this the gate advertised proceed/choose_map_node on screens that were already
        // gone (the action then failed with "ui=main_menu 下不能执行").
        if (in.leavingRun) {
            return new Decision(false, "leaving_run");
        }
        if (in.fading) {
            return new Decision(false, "fading");
        }
        if (in.mode == Mode.MENU) {
            return new Decision(true, "menu");
        }
        if (in.mode == Mode.TRANSITION) {
            return new Decision(false, "transition");
        }
        if (!in.inGame || !in.playerPresent) {
            return new Decision(false, "not_in_dungeon");
        }
        // Issue #125 · C16-04: entering a combat room the first frames are not a decision point yet - the
        // monsters still carry the engine's placeholder intent and the hand/energy are not dealt. QA read
        // a ready frame with hand=[], energy=0 and intent {"id":"DEBUG","unknown":true} and could have
        // acted on it; both get_state and legal_actions now say "not ready" until the combat settles.
        if (in.combatSettling) {
            return new Decision(false, "combat_settling");
        }
        if (in.blockingPopupOpen) {
            return new Decision(false, "popup");
        }
        if (in.screen == ScreenKind.BLOCKING) {
            return new Decision(false, "blocking_screen");
        }
        if (in.eventTextTyping) {
            // Kept for compatibility only: ActionPlanner.eventTextTyping() now always returns false,
            // because an event with no visible option still offers event_continue.
            return new Decision(false, "event_text_typing");
        }
        if (in.battleOver) {
            return new Decision(false, "battle_over");
        }
        if (in.victoryRoom) {
            // Never wait for the victory screen to be built: the executor creates it on demand, so a
            // missing screen must not turn the end of a won run into a dead state.
            return new Decision(true, "victory_screen");
        }
        if (in.screenUp) {
            // A screen that waits for the player can be up *inside* the action queue (a relic like
            // Gambling Chip, a card that asks for a discard). Those are decision points even while
            // GameActionManager.phase is EXECUTING_ACTIONS, so they must not wait for the queue.
            return new Decision(true, "screen_choice");
        }
        if (!in.actionsEmpty || !in.currentActionNull) {
            // Distinguish "an animation is playing" from "the queue is wedged while the render loop
            // is perfectly alive". A wedged queue (dev teleport / replaced room) never drains on its
            // own, so the state is reported as stuck instead of silently busy forever.
            long now = System.currentTimeMillis();
            if (stuckSinceMs == 0L) {
                stuckSinceMs = now;
                stuckFrames = in.frames;
            }
            boolean framesMoved = in.frames > stuckFrames;
            if (framesMoved) {
                stuckFrames = in.frames;
            }
            long stuckFor = now - stuckSinceMs;
            String detail = "actions=" + in.actionCount + ",current="
                    + (in.currentActionName == null ? "-" : in.currentActionName);
            if (stuckFor > STUCK_MS && framesMoved) {
                in.actionsStuck = true;
                return new Decision(false, "actions_stuck(" + detail + "," + stuckFor + "ms)", true);
            }
            return new Decision(false, "actions_running(" + detail + ")");
        }
        stuckSinceMs = 0L;
        if (!in.cardQueueEmpty) {
            return new Decision(false, "card_queue");
        }
        if (!in.preTurnActionsEmpty) {
            return new Decision(false, "preturn_actions");
        }
        if (in.dungeonBeaten) {
            return new Decision(false, "dungeon_beaten");
        }
        return new Decision(true, "idle");
    }

    /** Reads the live game state. Only call from the game main thread. */
    public static Inputs probe() {
        Inputs in = new Inputs();

        CardCrawlGame.GameMode gameMode = CardCrawlGame.mode;
        if (gameMode == CardCrawlGame.GameMode.GAMEPLAY) {
            in.mode = Mode.GAMEPLAY;
        } else if (gameMode == CardCrawlGame.GameMode.CHAR_SELECT || gameMode == null) {
            in.mode = Mode.MENU;
        } else {
            in.mode = Mode.TRANSITION;
        }

        in.playerPresent = AbstractDungeon.player != null;
        in.inGame = in.mode == Mode.GAMEPLAY && in.playerPresent;
        in.fading = AbstractDungeon.isFadingIn || AbstractDungeon.isFadingOut;
        in.loading = LoadingWindow.active();
        in.leavingRun = RunExitWindow.active();
        in.dungeonBeaten = AbstractDungeon.isDungeonBeaten;
        in.blockingPopupOpen = CardCrawlGame.isPopupOpen;

        GameActionManager actionManager = AbstractDungeon.actionManager;
        if (actionManager != null) {
            in.actionsEmpty = actionManager.actions == null || actionManager.actions.isEmpty();
            in.currentActionNull = actionManager.currentAction == null;
            in.actionCount = actionManager.actions == null ? 0 : actionManager.actions.size();
            in.currentActionName = actionManager.currentAction == null ? null
                    : actionManager.currentAction.getClass().getSimpleName();
            in.frames = spirelink.util.RuntimeProbe.frames();
            in.cardQueueEmpty = actionManager.cardQueue == null || actionManager.cardQueue.isEmpty();
            in.preTurnActionsEmpty = actionManager.preTurnActions == null
                    || actionManager.preTurnActions.isEmpty();
        }

        in.screenUp = AbstractDungeon.isScreenUp;
        try {
            // getCurrRoom() NPEs when currMapNode is null, hence the guard.
            in.victoryRoom = AbstractDungeon.currMapNode != null
                    && AbstractDungeon.getCurrRoom()
                            instanceof com.megacrit.cardcrawl.rooms.VictoryRoom;
        } catch (Throwable t) {
            in.victoryRoom = false;
        }
        in.combatSettling = combatSettling();
        in.eventTextTyping = spirelink.actions.ActionPlanner.eventTextTyping();
        in.battleOver = spirelink.actions.ActionPlanner.battleOver();
        in.screen = classify(AbstractDungeon.screen, in.screenUp);
        return in;
    }

    /**
     * Issue #125 · C16-04: is a combat room up but still being set up?
     *
     * <p>The honest signal is the engine's own placeholder: {@code AbstractMonster.Intent.DEBUG} is what
     * a monster carries until it rolls its intent, i.e. before the first turn exists. Reading {@code
     * MonsterSerializer} the same placeholder is already documented as the "transition frame" intent.
     * Everything is guarded: a half-built room must make us say "not ready", never throw.</p>
     */
    private static boolean combatSettling() {
        boolean placeholder;
        try {
            placeholder = hasPlaceholderIntent();
        } catch (Throwable t) {
            placeholder = false;
        }
        if (!placeholder) {
            settlingSinceMs = 0L;
            return false;
        }
        long now = System.currentTimeMillis();
        if (settlingSinceMs == 0L) {
            settlingSinceMs = now;
        }
        // Safety net: a monster whose intent stays DEBUG (a modded/broken one) must not brick the agent
        // forever - after SETTLING_MAX_MS the usual rules decide again.
        return now - settlingSinceMs <= SETTLING_MAX_MS;
    }

    /** True when a live monster in a COMBAT room still carries the engine's placeholder intent. */
    private static boolean hasPlaceholderIntent() {
        if (AbstractDungeon.currMapNode == null) {
            return false;
        }
        com.megacrit.cardcrawl.rooms.AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null
                || room.phase != com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT) {
            return false;
        }
        com.megacrit.cardcrawl.monsters.MonsterGroup monsters = AbstractDungeon.getMonsters();
        if (monsters == null || monsters.monsters == null) {
            return false;
        }
        for (com.megacrit.cardcrawl.monsters.AbstractMonster monster : monsters.monsters) {
            if (monster == null || monster.isDeadOrEscaped() || monster.isDying) {
                continue;
            }
            if (monster.intent == com.megacrit.cardcrawl.monsters.AbstractMonster.Intent.DEBUG) {
                return true;
            }
        }
        return false;
    }

    static ScreenKind classify(AbstractDungeon.CurrentScreen screen, boolean screenUp) {
        if (!screenUp || screen == null || screen == AbstractDungeon.CurrentScreen.NONE) {
            return ScreenKind.NONE;
        }
        switch (screen) {
            case MAP:
            case SHOP:
            case COMBAT_REWARD:
            case CARD_REWARD:
            case BOSS_REWARD:
            case GRID:
            case HAND_SELECT:
            case TRANSFORM:
            case CHOOSE_ONE:
            case DEATH:
            case VICTORY:
            case NEOW_UNLOCK:
            case FTUE:
            case SETTINGS:
            case INPUT_SETTINGS:
            case MASTER_DECK_VIEW:
            case DISCARD_VIEW:
            case EXHAUST_VIEW:
            case GAME_DECK_VIEW:
                return ScreenKind.CHOICE;
            case NO_INTERACT:
            case DOOR_UNLOCK:
            case UNLOCK:
            case CREDITS:
                return ScreenKind.BLOCKING;
            case NONE:
            default:
                // Unknown/new screens are treated conservatively: do not claim ready.
                return ScreenKind.BLOCKING;
        }
    }
}
