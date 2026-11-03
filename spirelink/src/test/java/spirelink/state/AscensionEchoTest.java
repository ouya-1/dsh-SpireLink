package spirelink.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Issue #77, the state half: after {@code choose_ascension} the level must be readable again from
 * {@code meta.ascension}, without waiting for the run to start.
 *
 * <p>Vanilla copies the chosen level into {@code AbstractDungeon.ascensionLevel} only inside the
 * character-select screen's own confirm handler, so right after setting it the meta block reported a
 * stale {@code 0} - while {@code tools.md} tells the caller to verify the setting with exactly that
 * field. The screen is the source of truth while it is up; the dungeon field everywhere else.
 */
public class AscensionEchoTest {

    @Test
    public void charSelectScreenLevelWins() {
        // choose_ascension 3 while the dungeon field still says 0 (no run yet).
        assertEquals(3, StateBuilder.ascensionFrom(true, Integer.valueOf(3), 0));
    }

    @Test
    public void charSelectWithoutAScreenFallsBackToTheDungeonField() {
        // Screen not built (or unreadable): never invent a number.
        assertEquals(7, StateBuilder.ascensionFrom(true, null, 7));
    }

    @Test
    public void otherScreensUseTheDungeonField() {
        // Mid-run: the screen object may still hold some old value, it must not leak into meta.
        assertEquals(12, StateBuilder.ascensionFrom(false, Integer.valueOf(3), 12));
    }

    @Test
    public void normalRunOnCharSelectReportsZero() {
        // isAscensionMode == false: the screen keeps its last level but a normal run is level 0.
        assertEquals(0, StateBuilder.ascensionFrom(true, Integer.valueOf(0), 0));
    }

    @Test
    public void settingZeroBackIsReported() {
        // Turning ascension off must be observable too (level 0 with a non-zero dungeon leftover).
        assertEquals(0, StateBuilder.ascensionFrom(true, Integer.valueOf(0), 15));
    }

    @Test
    public void noGameMeansNoScreenValue() {
        // Unit-test JVM: there is no character-select screen, so the reader degrades to null and the
        // decision falls back instead of throwing into buildMeta.
        assertEquals(null, StateBuilder.charSelectAscension());
        assertEquals(0, StateBuilder.ascensionForUi("char_select"));
        assertEquals(0, StateBuilder.ascensionForUi("combat"));
    }
}
