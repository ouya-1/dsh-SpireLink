package spirelink.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Issue #49: on the character select screen {@code meta.character} / {@code meta.awaiting} must
 * describe THIS screen, not the previous run.
 */
public class MetaCharacterTest {

    @Test
    public void charSelectScreenWinsOverThePreviousRun() {
        // The previous run's DEFECT is still on AbstractDungeon.player while THE_SILENT is highlighted.
        StateBuilder.CharacterMeta meta =
                StateBuilder.resolveCharacter("char_select", "DEFECT", true, "THE_SILENT");
        assertEquals("THE_SILENT", meta.name);
        assertEquals("char_select", meta.source);
    }

    @Test
    public void charSelectWithoutAPickReportsNothingNotTheOldRun() {
        StateBuilder.CharacterMeta meta =
                StateBuilder.resolveCharacter("char_select", "DEFECT", true, null);
        assertNull("没选人时不能报上一局的角色", meta.name);
        assertEquals("char_select", meta.source);
    }

    @Test
    public void charSelectWithoutAScreenFallsBackToThePlayer() {
        StateBuilder.CharacterMeta meta =
                StateBuilder.resolveCharacter("char_select", "DEFECT", false, null);
        assertEquals("DEFECT", meta.name);
        assertEquals("player", meta.source);
    }

    @Test
    public void inRunCharacterAlwaysComesFromThePlayer() {
        StateBuilder.CharacterMeta meta =
                StateBuilder.resolveCharacter("map", "IRONCLAD", true, "THE_SILENT");
        assertEquals("IRONCLAD", meta.name);
        assertEquals("player", meta.source);
    }

    @Test
    public void beforeARunTheHighlightedCharacterIsTheOnlyAnswer() {
        StateBuilder.CharacterMeta meta =
                StateBuilder.resolveCharacter("main_menu", null, true, "WATCHER");
        assertEquals("WATCHER", meta.name);
        assertEquals("char_select", meta.source);
    }

    @Test
    public void nothingKnownStaysNull() {
        StateBuilder.CharacterMeta meta =
                StateBuilder.resolveCharacter("main_menu", null, true, null);
        assertNull(meta.name);
    }

    @Test
    public void charSelectIsNotPlaying() {
        assertEquals("char_select", StateBuilder.awaitingFor("char_select", true, "menu"));
        assertEquals("playing", StateBuilder.awaitingFor("map", true, "idle"));
        assertEquals("dead", StateBuilder.awaitingFor("death", true, "idle"));
        // Fading out into the run: the real wait wins, "char_select" must not hide it.
        assertEquals("transition", StateBuilder.awaitingFor("char_select", false, "fading"));
    }
}
