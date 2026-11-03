package spirelink.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Human-only full reset of the note store (issue #47): {@link Notes#clearAll()} backs the file up,
 * writes {@code {}} and reports how many notes it wiped.
 *
 * <p>Every test points the store at a JUnit temporary folder first ({@link
 * Notes#useDirectoryForTests(File)}), so the real {@code mods/spirelink-data/notes.json} of the
 * machine running the build is never read, written or deleted by a test run.</p>
 */
public class NotesTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File dataDir;

    @Before
    public void setUp() throws Exception {
        dataDir = folder.newFolder("spirelink-data");
        Notes.useDirectoryForTests(dataDir);
    }

    @After
    public void tearDown() {
        Notes.useDirectoryForTests(null);
        Notes.resetForTests();
    }

    // ---- fixtures ----

    private File notesFile() {
        return new File(dataDir, "notes.json");
    }

    private List<File> backups() {
        List<File> out = new ArrayList<File>();
        File[] files = dataDir.listFiles();
        if (files == null) {
            return out;
        }
        for (File file : files) {
            if (file.getName().startsWith(Notes.BACKUP_PREFIX)) {
                out.add(file);
            }
        }
        return out;
    }

    private JsonObject jsonOf(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            return new JsonParser().parse(new InputStreamReader(in, UTF8)).getAsJsonObject();
        } finally {
            in.close();
        }
    }

    private void writeNotes(String... kindAndIdPairs) {
        int note = 0;
        for (int i = 0; i + 1 < kindAndIdPairs.length; i += 2) {
            Notes.set(kindAndIdPairs[i], kindAndIdPairs[i + 1], "正文 " + note++);
        }
    }

    // ---- clearAll ----

    @Test
    public void clearAllWipesEveryKindAndReportsTheCount() throws Exception {
        writeNotes("monster", "JawWorm", "event", "BigFish", "card", "Strike_R",
                "potion", "Fire Potion", "relic", "Burning Blood", "general", "start");
        assertEquals(6, Notes.list(null).entrySet().size());

        int removed = Notes.clearAll();

        assertEquals(6, removed);
        assertEquals(0, Notes.list(null).entrySet().size());
        assertEquals(0, Notes.list("monster").entrySet().size());
        assertEquals("{}", jsonOf(notesFile()).toString());
        assertEquals(0, jsonOf(notesFile()).entrySet().size());
    }

    @Test
    public void clearAllBacksUpTheFileNextToItself() throws Exception {
        writeNotes("monster", "JawWorm", "event", "BigFish");

        assertTrue(Notes.clearAll() > 0);

        List<File> backups = backups();
        assertEquals(1, backups.size());
        File backup = backups.get(0);
        assertEquals(dataDir, backup.getParentFile());
        assertTrue(backup.getName(), backup.getName().matches("notes\\.json\\.bak-\\d{8}-\\d{6}"));
        // The suffix really is a timestamp: it parses back to (almost) now.
        String stamp = backup.getName().substring(Notes.BACKUP_PREFIX.length());
        long parsed = new SimpleDateFormat(Notes.BACKUP_TIME_PATTERN).parse(stamp).getTime();
        assertTrue("备份时间戳 " + stamp, Math.abs(System.currentTimeMillis() - parsed) < 120000L);
        // ... and it still holds what the human just wiped.
        JsonObject old = jsonOf(backup);
        assertEquals(2, old.entrySet().size());
        assertEquals("正文 0", old.getAsJsonObject("monster:JawWorm").get("text").getAsString());
        assertEquals("正文 1", old.getAsJsonObject("event:BigFish").get("text").getAsString());
    }

    @Test
    public void clearAllOnAnEmptyStoreReturnsZeroAndWritesNoBackup() throws Exception {
        assertEquals(0, Notes.list(null).entrySet().size());

        assertEquals(0, Notes.clearAll());
        assertTrue(backups().isEmpty());
        assertEquals("{}", jsonOf(notesFile()).toString());

        // A second reset of the already empty store behaves the same way.
        assertEquals(0, Notes.clearAll());
        assertTrue(backups().isEmpty());
    }

    @Test
    public void notesCanBeWrittenAgainAfterAClear() throws Exception {
        writeNotes("monster", "JawWorm");
        assertEquals(1, Notes.clearAll());

        assertEquals("先攒格挡", Notes.set("general", "start", "先攒格挡"));

        assertEquals("先攒格挡", Notes.get("general", "start"));
        assertEquals(1, Notes.list(null).entrySet().size());
        JsonObject onDisk = jsonOf(notesFile());
        assertEquals(1, onDisk.entrySet().size());
        assertEquals("先攒格挡", onDisk.getAsJsonObject("general:start").get("text").getAsString());
    }

    @Test
    public void clearingTwiceKeepsBothBackupsAndNeverOverwritesTheFirst() throws Exception {
        writeNotes("monster", "JawWorm");
        assertEquals(1, Notes.clearAll());

        writeNotes("event", "BigFish");
        assertEquals(1, Notes.clearAll());

        List<File> after = backups();
        assertEquals(2, after.size());
        // Same second -> "-2" suffix, a second later -> another timestamp; both are legal, but the
        // two backups must stay distinct and the older one must still hold the note it captured.
        assertTrue(after.get(0).getName(), !after.get(0).getName().equals(after.get(1).getName()));
        JsonObject firstBackup = jsonOf(find(after, "monster:JawWorm"));
        assertEquals(1, firstBackup.entrySet().size());
    }

    private static File find(List<File> files, String key) throws Exception {
        for (File file : files) {
            FileInputStream in = new FileInputStream(file);
            try {
                JsonObject parsed = new JsonParser().parse(new InputStreamReader(in, UTF8))
                        .getAsJsonObject();
                if (parsed.has(key)) {
                    return file;
                }
            } finally {
                in.close();
            }
        }
        throw new AssertionError("没有备份保留 " + key);
    }

    // ---- backup helper ----

    @Test
    public void backupOfAMissingFileIsSkippedInsteadOfThrowing() {
        assertNull(Notes.backup(new File(dataDir, "does-not-exist.json")));
        assertNull(Notes.backup(null));
    }

    @Test
    public void backupCopiesBytesAndNeverTouchesTheSource() throws Exception {
        writeNotes("monster", "JawWorm");
        File source = notesFile();
        long sizeBefore = source.length();
        String contentBefore = jsonOf(source).toString();

        File copy = Notes.backup(source);

        assertNotNull(copy);
        assertTrue(copy.isFile());
        assertEquals(sizeBefore, copy.length());
        assertEquals(contentBefore, jsonOf(copy).toString());
        assertTrue(source.isFile());
        assertEquals(contentBefore, jsonOf(source).toString());
    }
}
