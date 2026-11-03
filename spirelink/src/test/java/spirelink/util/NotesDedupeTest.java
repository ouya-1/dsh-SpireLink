package spirelink.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Per-run note dedupe: same version once per run, new run resets, force dumps everything. */
public class NotesDedupeTest {

    @Test
    public void sameNoteVersionOnlyDeliveredOncePerRun() {
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        assertTrue(NotesDedupe.claim("monster:JawWorm@1", "会加力量"));
        assertFalse(NotesDedupe.claim("monster:JawWorm@1", "会加力量"));
        assertEquals(1, NotesDedupe.omitted());
    }

    @Test
    public void newRunClearsTheWindow() {
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        assertTrue(NotesDedupe.claim("general:start@1", "先攒格挡"));
        NotesDedupe.observeRun("run-b");
        assertTrue(NotesDedupe.claim("general:start@1", "先攒格挡"));
        assertEquals(0, NotesDedupe.omitted());
    }

    @Test
    public void updatedNoteIsDeliveredAgain() {
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        assertTrue(NotesDedupe.claim("monster:JawWorm@1", "旧"));
        assertTrue(NotesDedupe.claim("monster:JawWorm@2", "新"));
    }

    @Test
    public void forceDumpsEverythingOnceThenDedupesAgain() {
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        assertTrue(NotesDedupe.claim("general:start@1", "先攒格挡"));
        assertFalse(NotesDedupe.claim("general:start@1", "先攒格挡"));
        NotesDedupe.setForce(true);
        assertTrue(NotesDedupe.claim("general:start@1", "先攒格挡"));
        NotesDedupe.setForce(false);
        assertFalse(NotesDedupe.claim("general:start@1", "先攒格挡"));
    }

    @Test
    public void blankTextIsNeverClaimed() {
        NotesDedupe.resetForTests();
        assertFalse(NotesDedupe.claim("general:x@1", " "));
        assertFalse(NotesDedupe.claim("general:x@1", null));
    }
}
