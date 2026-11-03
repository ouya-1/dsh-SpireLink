package spirelink.state;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** I3/I4: every ui has a defined awaiting value, and "unknown" is never a silent fallback. */
public class AwaitingTest {

    private static final List<String> VOCABULARY = Arrays.asList(
            "playing", "enemy_turn", "animating", "screen_up", "transition",
            "battle_over", "event_text_typing", "unknown");

    private static final String[] REASONS = {
            "idle", "menu", "screen_choice", "popup", "blocking_screen",
            "event_text_typing", "battle_over", "fading", "transition",
            "dungeon_beaten", "actions_running", "card_queue", "preturn_actions",
            "not_in_dungeon", "no_input", "some_future_reason",
    };

    @Test
    public void everyReasonMapsToVocabulary() {
        for (String reason : REASONS) {
            String awaiting = StateBuilder.awaitingOf(reason);
            assertNotNull("reason=" + reason, awaiting);
            assertTrue("reason=" + reason + " -> " + awaiting,
                    VOCABULARY.contains(awaiting));
            assertFalse("reason=" + reason + " 不该落到 unknown",
                    "unknown".equals(awaiting));
        }
    }

    @Test
    public void idleAndMenuArePlayable() {
        assertEquals("playing", StateBuilder.awaitingOf("idle"));
        assertEquals("playing", StateBuilder.awaitingOf("menu"));
    }

    @Test
    public void knownWaitsAreDistinguished() {
        assertEquals("screen_up", StateBuilder.awaitingOf("screen_choice"));
        assertEquals("battle_over", StateBuilder.awaitingOf("battle_over"));
        assertEquals("event_text_typing", StateBuilder.awaitingOf("event_text_typing"));
        assertEquals("transition", StateBuilder.awaitingOf("fading"));
        assertEquals("transition", StateBuilder.awaitingOf("dungeon_beaten"));
    }
}
