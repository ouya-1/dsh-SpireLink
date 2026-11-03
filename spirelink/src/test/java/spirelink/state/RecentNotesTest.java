package spirelink.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import spirelink.util.NotesDedupe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** recent_notes：倒序、去重（同一局同一版本只推一次）、最多 8 条。 */
public class RecentNotesTest {

    @Test
    public void newestFirstAndCappedAtEight() {
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        JsonObject all = new JsonObject();
        for (int i = 0; i < 12; i++) {
            addNote(all, "monster:M" + i, "text-" + i, 100L + i);
        }
        JsonArray recent = RecentNotes.build(all, null);
        assertEquals(RecentNotes.MAX_ITEMS, recent.size());
        assertEquals("monster", recent.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("M11", recent.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(111L, recent.get(0).getAsJsonObject().get("updated_at").getAsLong());
        // 倒序第 8 条：M11 是第 1 条，所以第 8 条是 M4。
        assertEquals("M4", recent.get(7).getAsJsonObject().get("id").getAsString());
    }

    @Test
    public void sameNoteVersionIsDeliveredOnlyOncePerRun() {
        // issue #3：主菜单路径每次 get_state 都重复推同一批笔记。
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        JsonObject all = new JsonObject();
        addNote(all, "monster:JawWorm", "会加力量", 100L);
        addNote(all, "general:start", "先攒格挡", 90L);
        assertEquals(2, RecentNotes.build(all, null).size());
        assertEquals(0, RecentNotes.build(all, null).size());
        assertEquals(2, NotesDedupe.omitted());
    }

    @Test
    public void peekDoesNotConsumeTheWindowButClaimDoes() {
        // 构建 body 用 peek（claim=false）：缓存快照反复构建也不会把笔记白白消耗掉；
        // 真正发出去的那一次（claim=true）才推进去重窗口。
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        JsonObject all = new JsonObject();
        addNote(all, "general:start", "先攒格挡", 100L);
        assertEquals(1, RecentNotes.build(all, null, false).size());
        assertEquals(1, RecentNotes.build(all, null, false).size());
        assertEquals(0, NotesDedupe.omitted());
        assertEquals(1, RecentNotes.build(all, null, true).size());
        assertEquals(0, RecentNotes.build(all, null, true).size());
        assertEquals(1, NotesDedupe.omitted());
    }

    @Test
    public void dedupeNeverPullsOlderNotesForward() {
        // issue #3 实测到的坑：只有 8 条上限、去重掉的不补位，否则连续 5 次 get_state
        // 会一批一批把 50 条笔记全推一遍（8/8/8/8/8）。第二次起必须就是 0 条。
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        JsonObject all = new JsonObject();
        for (int i = 0; i < 50; i++) {
            addNote(all, "monster:M" + i, "text-" + i, 100L + i);
        }
        assertEquals(8, RecentNotes.build(all, null).size());
        for (int round = 0; round < 4; round++) {
            assertEquals("第 " + (round + 2) + " 次 get_state 必须为空",
                    0, RecentNotes.build(all, null).size());
        }
        // 每次响应都会看一遍「最新 8 条」，被去重的各计一次：4 次 × 8 条 = 32。
        assertEquals(32, NotesDedupe.omitted());
    }

    @Test
    public void updatedNoteComesBack() {
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        JsonObject all = new JsonObject();
        addNote(all, "monster:JawWorm", "旧", 100L);
        assertEquals(1, RecentNotes.build(all, null).size());
        addNote(all, "monster:JawWorm", "新", 200L);
        JsonArray again = RecentNotes.build(all, null);
        assertEquals(1, again.size());
        assertEquals("新", again.get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void newRunClearsTheWindow() {
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        JsonObject all = new JsonObject();
        addNote(all, "general:start", "先攒格挡", 100L);
        assertEquals(1, RecentNotes.build(all, null).size());
        NotesDedupe.observeRun("run-b");
        assertEquals(1, RecentNotes.build(all, null).size());
    }

    @Test
    public void forceNotesDumpsEverythingOnceThenDedupesAgain() {
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        JsonObject all = new JsonObject();
        addNote(all, "general:start", "先攒格挡", 100L);
        assertEquals(1, RecentNotes.build(all, null).size());
        NotesDedupe.setForce(true);
        try {
            assertEquals(1, RecentNotes.build(all, null).size());
        } finally {
            NotesDedupe.setForce(false);
        }
        assertEquals(0, RecentNotes.build(all, null).size());
    }

    @Test
    public void countsCoverEveryNoteIncludingDedupedOnes() {
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        JsonObject all = new JsonObject();
        for (int i = 0; i < 10; i++) {
            addNote(all, "monster:M" + i, "text-" + i, 100L + i);
        }
        addNote(all, "general:start", "先攒格挡", 1L);
        JsonObject counts = new JsonObject();
        JsonArray recent = RecentNotes.build(all, counts);
        assertEquals(8, recent.size());
        // 计数看的是全部 11 条，不是推送的 8 条。
        assertEquals(10, counts.get("monster").getAsInt());
        assertEquals(1, counts.get("general").getAsInt());
        assertEquals(11, all.entrySet().size());
    }

    @Test
    public void malformedEntriesAreSkippedNotFatal() {
        NotesDedupe.resetForTests();
        NotesDedupe.observeRun("run-a");
        JsonObject all = new JsonObject();
        all.addProperty("general:broken", "不是对象");
        addNote(all, "general:ok", "正文", 100L);
        JsonArray recent = RecentNotes.build(all, null);
        assertEquals(1, recent.size());
        assertEquals("ok", recent.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(0, RecentNotes.build(null, null).size());
    }

    private static void addNote(JsonObject all, String key, String text, long updatedAt) {
        JsonObject entry = new JsonObject();
        entry.addProperty("text", text);
        entry.addProperty("updated_at", updatedAt);
        all.add(key, entry);
    }
}
