package spirelink.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure logic of the read-only notes panel (issue #46): parsing the note store, page arithmetic, row
 * formatting / clipping and hit-testing.
 *
 * <p>No libgdx and no Slay the Spire class is loaded here - that is the whole point of keeping the
 * state machine in {@link NotesPanel} and the drawing in the patch.</p>
 */
public class NotesPanelTest {

    /** Deterministic stand-in for NoteHud: localized kind labels, id as the display name. */
    private static final NotesPanel.Formatter FORMATTER = new NotesPanel.Formatter() {
        @Override
        public String kindLabel(String kind) {
            if ("monster".equals(kind)) {
                return "敌人";
            }
            if ("event".equals(kind)) {
                return "事件";
            }
            return "通用";
        }

        @Override
        public String displayName(String kind, String id) {
            return id;
        }

        @Override
        public String body(String text) {
            return text == null ? "" : text.replace("#g", "").replace("#r", "");
        }
    };

    private static NotesPanel panel() {
        return new NotesPanel(FORMATTER);
    }

    /** {@code count} monster notes, oldest first in the input, m0..m{count-1}. */
    private static List<NotesPanel.Entry> entries(int count) {
        List<NotesPanel.Entry> out = new ArrayList<NotesPanel.Entry>();
        for (int i = 0; i < count; i++) {
            out.add(entry("m" + i, "正文" + i, 1000L + i));
        }
        return out;
    }

    private static NotesPanel.Entry entry(String id, String text, long updatedAt) {
        return new NotesPanel.Entry("monster:" + id, "monster", id, text, updatedAt);
    }

    private static NotesPanel openWith(int count) {
        NotesPanel panel = panel();
        panel.open(entries(count));
        return panel;
    }

    // ---- parsing ----

    @Test
    public void parseReadsKindAndIdFromTheKey() {
        JsonObject notes = new JsonObject();
        notes.add("monster:JawWorm", jsonEntry("会加力量", 1700000000L));
        notes.add("event:BigFish", jsonEntry("选 1 回血", 1700000001L));
        notes.add("noPrefixKey", jsonEntry("没有类型前缀", 0L));

        List<NotesPanel.Entry> parsed = NotesPanel.parse(notes);
        assertEquals(3, parsed.size());
        assertEquals("monster", parsed.get(0).kind);
        assertEquals("JawWorm", parsed.get(0).id);
        assertEquals("会加力量", parsed.get(0).text);
        assertEquals(1700000000L, parsed.get(0).updatedAt);
        assertEquals("event", parsed.get(1).kind);
        assertEquals("BigFish", parsed.get(1).id);
        assertEquals("general", parsed.get(2).kind);
        assertEquals("noPrefixKey", parsed.get(2).id);
    }

    @Test
    public void parseSkipsEntriesWithoutTextAndNonObjects() {
        JsonObject notes = new JsonObject();
        notes.add("monster:noText", new JsonObject());
        JsonObject nullText = new JsonObject();
        nullText.add("text", com.google.gson.JsonNull.INSTANCE);
        notes.add("monster:nullText", nullText);
        notes.add("monster:notAnObject", new JsonPrimitive("boom"));

        assertTrue(NotesPanel.parse(notes).isEmpty());
        assertTrue(NotesPanel.parse(null).isEmpty());
    }

    @Test
    public void parseToleratesMissingOrBrokenTimestamps() {
        JsonObject notes = new JsonObject();
        notes.add("monster:noStamp", jsonEntryOnlyText("ok"));
        JsonObject broken = new JsonObject();
        broken.addProperty("text", "ok");
        broken.addProperty("updated_at", "not-a-number");
        notes.add("monster:brokenStamp", broken);

        List<NotesPanel.Entry> parsed = NotesPanel.parse(notes);
        assertEquals(2, parsed.size());
        assertEquals(0L, parsed.get(0).updatedAt);
        assertEquals(0L, parsed.get(1).updatedAt);
    }

    @Test
    public void entryFallsBackToGeneralKindForBlankOrNull() {
        assertEquals("general", new NotesPanel.Entry("k", null, "id", "t", 0L).kind);
        assertEquals("general", new NotesPanel.Entry("k", "", "id", "t", 0L).kind);
        assertEquals("", new NotesPanel.Entry("k", "monster", null, null, 0L).id);
        assertEquals("", new NotesPanel.Entry("k", "monster", "id", null, 0L).text);
    }

    @Test
    public void kindAndIdHelpersHandleKeysWithoutPrefix() {
        assertEquals("monster", NotesPanel.kindOf("monster:JawWorm"));
        assertEquals("JawWorm", NotesPanel.idOf("monster:JawWorm"));
        assertEquals("general", NotesPanel.kindOf("plain"));
        assertEquals("plain", NotesPanel.idOf("plain"));
        assertEquals("general", NotesPanel.kindOf(":idOnly"));
        assertEquals("general", NotesPanel.kindOf(null));
    }

    private static JsonObject jsonEntry(String text, long updatedAt) {
        JsonObject entry = new JsonObject();
        entry.addProperty("text", text);
        entry.addProperty("updated_at", updatedAt);
        return entry;
    }

    private static JsonObject jsonEntryOnlyText(String text) {
        JsonObject entry = new JsonObject();
        entry.addProperty("text", text);
        return entry;
    }

    // ---- ordering ----

    @Test
    public void newestNoteComesFirst() {
        NotesPanel panel = panel();
        List<NotesPanel.Entry> list = new ArrayList<NotesPanel.Entry>();
        list.add(entry("old", "旧", 10L));
        list.add(entry("newest", "新", 30L));
        list.add(entry("middle", "中", 20L));
        panel.open(list);

        assertTrue(panel.row(0), panel.row(0).contains("newest"));
        assertTrue(panel.row(1), panel.row(1).contains("middle"));
        assertTrue(panel.row(2), panel.row(2).contains("old"));
    }

    @Test
    public void equalTimestampsSortByKeySoTheOrderNeverFlickers() {
        NotesPanel panel = panel();
        List<NotesPanel.Entry> list = new ArrayList<NotesPanel.Entry>();
        list.add(new NotesPanel.Entry("monster:b", "monster", "b", "B", 5L));
        list.add(new NotesPanel.Entry("monster:a", "monster", "a", "A", 5L));
        panel.open(list);

        assertTrue(panel.row(0), panel.row(0).contains("a"));
        assertTrue(panel.row(1), panel.row(1).contains("b"));
    }

    // ---- page arithmetic ----

    @Test
    public void emptyStoreStillShowsOneEmptyPage() {
        NotesPanel panel = panel();
        panel.open(new ArrayList<NotesPanel.Entry>());
        assertEquals(0, panel.count());
        assertEquals(1, panel.pageCount());
        assertEquals(1, panel.page());
        assertEquals(0, panel.visibleCount());
        assertNull(panel.row(0));
        assertEquals("第 1/1 页 · 共 0 条", panel.header());
        assertEquals("便签（0）", NotesPanel.entryLabel(panel.count()));
    }

    @Test
    public void fiftyFiveNotesMakeFivePagesOfTwelve() {
        NotesPanel panel = openWith(55);
        assertEquals(55, panel.count());
        assertEquals(5, panel.pageCount());
        assertEquals(12, panel.visibleCount());
        assertEquals(1, panel.page());
        assertEquals("第 1/5 页 · 共 55 条", panel.header());
        assertEquals(0, panel.firstRow());
        assertNull(panel.row(12));
    }

    @Test
    public void endLandsOnTheLastPageWithoutRunningPastTheNewestOrOldestRow() {
        NotesPanel panel = openWith(55);
        panel.last();
        // 5 pages of 12: the last one starts at row 48 and carries the remaining 7 notes.
        assertEquals(48, panel.firstRow());
        assertEquals(5, panel.page());
        assertEquals(7, panel.visibleCount());
        assertTrue(panel.row(0), panel.row(0).contains("m6"));
        assertTrue(panel.row(6), panel.row(6).contains("m0"));
        assertNull(panel.row(7));
        panel.first();
        assertEquals(0, panel.firstRow());
        assertEquals(1, panel.page());
    }

    @Test
    public void pagingThroughEverythingShowsEveryNoteExactlyOnce() {
        NotesPanel panel = openWith(55);
        List<String> seen = new ArrayList<String>();
        for (int page = 0; page < panel.pageCount(); page++) {
            for (int row = 0; row < panel.visibleCount(); row++) {
                seen.add(panel.row(row));
            }
            panel.pageStep(1);
        }
        assertEquals(55, seen.size());
        for (String row : seen) {
            assertTrue(row, row.contains("m"));
        }
        // newest first: row 0 holds m54, the last row holds m0
        assertTrue(seen.get(0), seen.get(0).contains("m54"));
        assertTrue(seen.get(54), seen.get(54).contains("m0"));
        assertEquals(55, new java.util.HashSet<String>(seen).size());
    }

    @Test
    public void scrollMovesOneRowAndClampsAtBothEnds() {
        NotesPanel panel = openWith(55);
        panel.scrollRows(1);
        assertEquals(1, panel.firstRow());
        assertTrue(panel.row(0), panel.row(0).contains("m53"));
        panel.scrollRows(-1);
        assertEquals(0, panel.firstRow());
        panel.scrollRows(-1);
        assertEquals(0, panel.firstRow());
        panel.scrollRows(1000);
        assertEquals(48, panel.firstRow());
        panel.scrollRows(1000);
        assertEquals(48, panel.firstRow());
    }

    @Test
    public void pageStepWalksPageByPageInBothDirections() {
        NotesPanel panel = openWith(55);
        panel.pageStep(1);
        assertEquals(12, panel.firstRow());
        assertEquals(2, panel.page());
        panel.pageStep(1);
        assertEquals(24, panel.firstRow());
        panel.pageStep(1);
        assertEquals(36, panel.firstRow());
        panel.pageStep(1);
        assertEquals(48, panel.firstRow());
        assertEquals(5, panel.page());
        panel.pageStep(1);
        assertEquals(48, panel.firstRow());
        panel.pageStep(-1);
        assertEquals(36, panel.firstRow());
        assertEquals(4, panel.page());
        panel.pageStep(-5);
        assertEquals(0, panel.firstRow());
        assertEquals(1, panel.page());
    }

    @Test
    public void shortListNeverPaginates() {
        NotesPanel panel = openWith(3);
        assertEquals(1, panel.pageCount());
        assertEquals(3, panel.visibleCount());
        panel.scrollRows(5);
        assertEquals(0, panel.firstRow());
        panel.pageStep(1);
        assertEquals(0, panel.firstRow());
        assertEquals("第 1/1 页 · 共 3 条", panel.header());
    }

    @Test
    public void reopeningSnapsBackToTheNewestPage() {
        NotesPanel panel = openWith(55);
        panel.pageStep(1);
        panel.pageStep(1);
        assertEquals(24, panel.firstRow());
        panel.close();
        assertFalse(panel.isOpen());
        panel.open(entries(55));
        assertTrue(panel.isOpen());
        assertEquals(0, panel.firstRow());
        assertEquals(1, panel.page());
    }

    @Test
    public void navigationWithoutANapshotIsHarmless() {
        NotesPanel panel = panel();
        panel.scrollRows(3);
        panel.pageStep(1);
        panel.first();
        panel.last();
        assertEquals(0, panel.firstRow());
        assertEquals(1, panel.page());
        assertEquals(0, panel.count());
    }

    @Test
    public void entryLabelCarriesTheLiveCount() {
        assertEquals("便签（55）", NotesPanel.entryLabel(55));
        assertEquals("便签（1）", NotesPanel.entryLabel(1));
        assertEquals("便签（0）", NotesPanel.entryLabel(0));
        assertEquals("便签（0）", NotesPanel.entryLabel(-3));
    }

    // ---- row formatting ----

    @Test
    public void rowLooksLikeTypeNameColonBody() {
        NotesPanel panel = panel();
        List<NotesPanel.Entry> list = new ArrayList<NotesPanel.Entry>();
        list.add(entry("JawWorm", "会加力量 #g红色", 1L));
        panel.open(list);
        assertEquals("[敌人] JawWorm：会加力量 红色", panel.row(0));
    }

    @Test
    public void rowIsAlwaysOneLine() {
        NotesPanel panel = panel();
        List<NotesPanel.Entry> list = new ArrayList<NotesPanel.Entry>();
        list.add(entry("x", "第一行\r\n第二行\t结束", 1L));
        panel.open(list);
        String row = panel.row(0);
        assertEquals("[敌人] x：第一行 第二行 结束", row);
        assertFalse(row.indexOf('\n') >= 0);
        assertFalse(row.indexOf('\r') >= 0);
    }

    @Test
    public void rowWithoutBodyKeepsTypeAndNameOnly() {
        NotesPanel panel = panel();
        List<NotesPanel.Entry> list = new ArrayList<NotesPanel.Entry>();
        list.add(entry("onlyName", "", 1L));
        panel.open(list);
        assertEquals("[敌人] onlyName", panel.row(0));
    }

    @Test
    public void longNoteIsClippedByTheCharacterCapWithAnEllipsis() {
        NotesPanel panel = panel();
        panel.setMaxRowChars(40);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 208; i++) {
            body.append('长');
        }
        List<NotesPanel.Entry> list = new ArrayList<NotesPanel.Entry>();
        list.add(entry("longOne", body.toString(), 1L));
        panel.open(list);

        String row = panel.row(0);
        assertEquals(40, row.length());
        assertTrue(row.endsWith("…"));
        assertTrue(row.startsWith("[敌人] longOne：长"));
    }

    @Test
    public void longNoteIsClippedByTheMeasuredPixelWidth() {
        NotesPanel panel = panel();
        panel.setMeasurer(pixelMeasurer(10f));
        panel.setRowWidth(200f);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append('长');
        }
        List<NotesPanel.Entry> list = new ArrayList<NotesPanel.Entry>();
        list.add(entry("longOne", body.toString(), 1L));
        panel.open(list);

        String row = panel.row(0);
        assertTrue("width " + row.length() * 10f, row.length() * 10f <= 200f);
        assertTrue(row.endsWith("…"));
    }

    @Test
    public void noRowOverflowsOnAnyPage() {
        NotesPanel panel = panel();
        panel.setMeasurer(pixelMeasurer(7.5f));
        panel.setRowWidth(300f);
        List<NotesPanel.Entry> list = new ArrayList<NotesPanel.Entry>();
        for (int i = 0; i < 30; i++) {
            StringBuilder body = new StringBuilder();
            for (int c = 0; c < 60 + i; c++) {
                body.append('中');
            }
            list.add(entry("n" + i, body.toString(), 100L + i));
        }
        panel.open(list);
        for (int page = 0; page < panel.pageCount(); page++) {
            for (int row = 0; row < panel.visibleCount(); row++) {
                String text = panel.row(row);
                assertTrue("page " + page + " row " + row,
                        pixelMeasurer(7.5f).width(text) <= 300f);
            }
            panel.pageStep(1);
        }
    }

    @Test
    public void resizeReclipsTheCachedRowsWithoutLosingTheWindow() {
        NotesPanel panel = panel();
        panel.setMeasurer(pixelMeasurer(10f));
        panel.setRowWidth(200f);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            body.append('长');
        }
        List<NotesPanel.Entry> list = new ArrayList<NotesPanel.Entry>();
        list.add(entry("longOne", body.toString(), 1L));
        list.add(entry("other", "短", 2L));
        panel.open(list);
        // Newest first: "other" row 0, the 200-character note row 1.
        assertTrue(panel.row(1).length() * 10f <= 200f);

        panel.setRowWidth(400f);
        panel.rebuildRows();
        assertTrue(panel.row(1).length() * 10f <= 400f);
        assertTrue(panel.row(1).length() * 10f > 200f);
        assertEquals(0, panel.firstRow());
    }

    private static NotesPanel.Measurer pixelMeasurer(final float perChar) {
        return new NotesPanel.Measurer() {
            @Override
            public float width(String text) {
                return text == null ? 0f : text.length() * perChar;
            }
        };
    }

    // ---- hit-testing ----

    @Test
    public void containsCoversTheDrawnRectangle() {
        assertTrue(NotesPanel.contains(10f, 20f, 100f, 40f, 10f, 20f));
        assertTrue(NotesPanel.contains(10f, 20f, 100f, 40f, 60f, 40f));
        assertTrue(NotesPanel.contains(10f, 20f, 100f, 40f, 110f, 60f));
        assertFalse(NotesPanel.contains(10f, 20f, 100f, 40f, 111f, 40f));
        assertFalse(NotesPanel.contains(10f, 20f, 100f, 40f, 60f, 61f));
        assertFalse(NotesPanel.contains(10f, 20f, 0f, 40f, 10f, 20f));
        assertFalse(NotesPanel.contains(10f, 20f, 100f, 0f, 10f, 20f));
    }

    // ---- clear all, two-click confirmation (issue #47) ----

    @Test
    public void clearButtonStartsIdle() {
        NotesPanel panel = openWith(3);
        assertFalse(panel.isConfirmingClear());
        assertEquals("清空全部", panel.clearLabel());
        assertEquals(NotesPanel.CLEAR_LABEL, panel.clearLabel());
        assertEquals(3000L, NotesPanel.CLEAR_CONFIRM_MS);
    }

    @Test
    public void firstClickOnlyArmsTheButton() {
        NotesPanel panel = openWith(3);
        assertFalse("第一次点击不清空", panel.clickClear(1000L));
        assertTrue(panel.isConfirmingClear());
        assertEquals("再点一次确认清空，3 秒后取消", panel.clearLabel());
        assertEquals(NotesPanel.CLEAR_CONFIRM_LABEL, panel.clearLabel());
        // The state machine itself deletes nothing: the snapshot is still there.
        assertEquals(3, panel.count());
    }

    @Test
    public void secondClickInsideTheWindowAsksForTheReset() {
        NotesPanel panel = openWith(3);
        panel.clickClear(1000L);
        assertTrue(panel.clickClear(2500L));
        assertFalse("触发后立刻回落", panel.isConfirmingClear());
        assertEquals(NotesPanel.CLEAR_LABEL, panel.clearLabel());
    }

    @Test
    public void clickAfterTheWindowCountsAsAFirstClickAgain() {
        NotesPanel panel = openWith(3);
        panel.clickClear(1000L);
        // 3 s lapsed without a tick in between: no reset, the button is armed afresh.
        assertFalse(panel.clickClear(4000L));
        assertTrue(panel.isConfirmingClear());
        assertTrue(panel.clickClear(4500L));
        assertFalse(panel.isConfirmingClear());
    }

    @Test
    public void threeSecondsOfNothingDisarmTheButton() {
        NotesPanel panel = openWith(3);
        panel.clickClear(0L);
        assertFalse(panel.tickClear(2999L));
        assertTrue(panel.isConfirmingClear());
        assertTrue(panel.tickClear(3000L));
        assertFalse(panel.isConfirmingClear());
        assertFalse("已经复位，再 tick 无事可做", panel.tickClear(3001L));
        assertFalse("超时后下一次点击重新开始", panel.clickClear(3002L));
        assertTrue(panel.isConfirmingClear());
    }

    @Test
    public void clickingAnywhereElseDropsThePendingConfirmation() {
        NotesPanel panel = openWith(3);
        panel.clickClear(0L);
        assertTrue(panel.cancelClear());
        assertFalse(panel.isConfirmingClear());
        assertFalse("本来就空闲，取消是空操作", panel.cancelClear());
        assertFalse(panel.clickClear(10L));
        assertTrue(panel.isConfirmingClear());
    }

    @Test
    public void closingOrReopeningResetsTheButton() {
        NotesPanel panel = openWith(3);
        panel.clickClear(0L);
        panel.close();
        assertFalse(panel.isConfirmingClear());
        panel.clickClear(0L);
        panel.open(entries(3));
        assertFalse(panel.isConfirmingClear());
    }

    @Test
    public void remainingTimeCountsDownAndFloorsAtZero() {
        NotesPanel panel = openWith(3);
        assertEquals(0L, panel.clearRemainingMs(0L));
        panel.clickClear(1000L);
        assertEquals(3000L, panel.clearRemainingMs(1000L));
        assertEquals(1500L, panel.clearRemainingMs(2500L));
        assertEquals(1L, panel.clearRemainingMs(3999L));
        assertEquals(0L, panel.clearRemainingMs(4000L));
    }

    @Test
    public void afterTheResetThePanelShowsTheEmptyFirstPage() {
        NotesPanel panel = openWith(55);
        panel.clickClear(0L);
        assertTrue(panel.clickClear(500L));
        // What the patch does after Notes.clearAll(): re-open on the now empty snapshot.
        panel.open(new ArrayList<NotesPanel.Entry>());
        assertEquals("第 1/1 页 · 共 0 条", panel.header());
        assertEquals(0, panel.visibleCount());
        assertNull(panel.row(0));
        assertFalse(panel.isConfirmingClear());
        assertEquals("便签（0）", NotesPanel.entryLabel(panel.count()));
    }

    // ---- switch ----

    @Test
    public void panelIsOnByDefaultAndCanBeSwitchedOff() {
        boolean before = NotesPanel.isEnabled();
        try {
            NotesPanel.configure(true);
            assertTrue(NotesPanel.isEnabled());
            NotesPanel.configure(false);
            assertFalse(NotesPanel.isEnabled());
        } finally {
            NotesPanel.configure(before);
        }
    }
}
