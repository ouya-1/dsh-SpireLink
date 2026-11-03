package spirelink.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Truth table for the "ready" judgement (design 5). No game classes are touched: the inputs are
 * plain values, so this runs with {@code mvn test} and no Slay the Spire process.
 */
public class StableGateTest {

    private static StableGate.Inputs inGame() {
        StableGate.Inputs in = new StableGate.Inputs();
        in.mode = StableGate.Mode.GAMEPLAY;
        in.inGame = true;
        in.playerPresent = true;
        return in;
    }

    private static void assertReady(StableGate.Inputs inputs, String expectedReason) {
        StableGate.Decision decision = StableGate.evaluate(inputs);
        assertTrue("expected ready, got " + decision, decision.ready);
        assertEquals(expectedReason, decision.reason);
    }

    private static void assertNotReady(StableGate.Inputs inputs, String expectedReason) {
        StableGate.Decision decision = StableGate.evaluate(inputs);
        assertFalse("expected not-ready, got " + decision, decision.ready);
        // Reasons may carry a detail suffix (actions=N,current=Class) for diagnosis.
        if (decision.reason != null && decision.reason.startsWith(expectedReason)) {
            return;
        }
        assertEquals(expectedReason, decision.reason);
    }

    @Test
    public void eventStillTypingIsNotReady() {
        StableGate.Inputs in = inGame();
        in.eventTextTyping = true;
        assertNotReady(in, "event_text_typing");
    }

    @Test
    public void wonBattleIsNotReadyUntilRewards() {
        StableGate.Inputs in = inGame();
        in.battleOver = true;
        assertNotReady(in, "battle_over");
    }

    @Test
    public void mainMenuIsReady() {
        StableGate.Inputs in = new StableGate.Inputs();
        in.mode = StableGate.Mode.MENU;
        assertReady(in, "menu");
    }

    @Test
    public void sceneTransitionIsNotReady() {
        StableGate.Inputs in = new StableGate.Inputs();
        in.mode = StableGate.Mode.TRANSITION;
        assertNotReady(in, "transition");
    }

    @Test
    public void fadeWinsOverEverything() {
        StableGate.Inputs menu = new StableGate.Inputs();
        menu.fading = true;
        assertNotReady(menu, "fading");

        StableGate.Inputs combat = inGame();
        combat.fading = true;
        assertNotReady(combat, "fading");
    }

    @Test
    public void gameplayWithoutPlayerIsNotReady() {
        StableGate.Inputs in = inGame();
        in.playerPresent = false;
        in.inGame = false;
        assertNotReady(in, "not_in_dungeon");
    }

    @Test
    public void blockingPopupIsNotReady() {
        StableGate.Inputs in = inGame();
        in.blockingPopupOpen = true;
        assertNotReady(in, "popup");
    }

    @Test
    public void blockingScreenIsNotReady() {
        StableGate.Inputs in = inGame();
        in.screenUp = true;
        in.screen = StableGate.ScreenKind.BLOCKING;
        assertNotReady(in, "blocking_screen");
    }

    @Test
    public void runningActionsAreNotReady() {
        StableGate.Inputs in = inGame();
        in.actionsEmpty = false;
        assertNotReady(in, "actions_running");
    }

    @Test
    public void nonNullCurrentActionIsNotReady() {
        StableGate.Inputs in = inGame();
        in.currentActionNull = false;
        assertNotReady(in, "actions_running");
    }

    @Test
    public void choiceScreenIsReady() {
        StableGate.Inputs in = inGame();
        in.screenUp = true;
        in.screen = StableGate.ScreenKind.CHOICE;
        assertReady(in, "screen_choice");
    }

    @Test
    public void pendingCardQueueIsNotReady() {
        StableGate.Inputs in = inGame();
        in.cardQueueEmpty = false;
        assertNotReady(in, "card_queue");
    }

    @Test
    public void pendingPreturnActionsAreNotReady() {
        StableGate.Inputs in = inGame();
        in.preTurnActionsEmpty = false;
        assertNotReady(in, "preturn_actions");
    }

    @Test
    public void beatenDungeonIsNotReady() {
        StableGate.Inputs in = inGame();
        in.dungeonBeaten = true;
        assertNotReady(in, "dungeon_beaten");
    }

    @Test
    public void idleCombatIsReady() {
        assertReady(inGame(), "idle");
    }

    @Test
    public void nullInputIsNotReady() {
        assertNotReady(null, "no_input");
    }
}
