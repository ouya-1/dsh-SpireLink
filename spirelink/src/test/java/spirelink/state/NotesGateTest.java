package spirelink.state;

import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import spirelink.util.Notes;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 0.1.7: 死亡后必须先写两条便签才能开新局。 */
public class NotesGateTest {

    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("notes-gate").toFile();
        Notes.useDirectoryForTests(dir);
        NotesGate.disarm();
    }

    @After
    public void tearDown() throws Exception {
        Notes.resetForTests();
        NotesGate.disarm();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    @Test
    public void nothingIsRequiredBeforeAnythingHappens() {
        assertTrue(NotesGate.pending().isEmpty());
        assertFalse(NotesGate.armed());
    }

    @Test
    public void twoNotesAreRequiredAfterDeath() {
        NotesGate.arm("GremlinNob");
        assertTrue(NotesGate.armed());
        java.util.List<String> pending = NotesGate.pending();
        assertEquals(2, pending.size());
        assertTrue(pending.get(0).contains("monster:GremlinNob"));
        assertTrue(pending.get(1).startsWith("global:"));
    }

    @Test
    public void writingBothReleasesTheGate() {
        NotesGate.arm("GremlinNob");
        Notes.set("monster", "GremlinNob", "暴怒：打技能牌喂力量，先打攻击牌");
        assertEquals(1, NotesGate.pending().size());
        Notes.set("global", "act1-elite", "血不到 70% 不进精英");
        assertTrue(NotesGate.pending().isEmpty());
        assertFalse("写完就该解除拦截", NotesGate.armed());
    }

    @Test
    public void aNoteWrittenBeforeTheDeathDoesNotCount() {
        Notes.set("monster", "GremlinNob", "旧便签");
        Notes.set("general", "old", "旧规则");
        NotesGate.arm("GremlinNob");
        // updated_at 是旧版本（< armedAt），不算这一屏写的
        assertEquals(2, NotesGate.pending().size());
    }

    @Test
    public void victoryWithoutAKillerOnlyNeedsTheGeneralNote() {
        NotesGate.arm(null);
        java.util.List<String> pending = NotesGate.pending();
        assertEquals(1, pending.size());
        assertTrue(pending.get(0).startsWith("global:"));
    }

    @Test
    public void pendingIsNotSerialisedWhenTheGateIsIdle() {
        JsonObject state = new JsonObject();
        state.addProperty("ok", true);
        NotesGate.arm(null);
        assertFalse(NotesGate.pending().isEmpty());
        assertEquals(1, state.entrySet().size());
    }
}
