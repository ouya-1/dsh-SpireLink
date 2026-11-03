package spirelink.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Pure state machine behind the read-only notes panel on the main menu (issue #46).
 *
 * <p>Everything that can be wrong without a running game lives here: turning the note store into a
 * sorted entry list, slicing it into pages, formatting one row as {@code [类型] 名称：正文} and
 * clipping that row to the pixel width the renderer actually has. No libgdx class and no Slay the
 * Spire class is touched, so {@code NotesPanelTest} drives the whole thing on a plain JVM.</p>
 *
 * <p>The panel is <b>read-only by construction</b>: it only ever receives entries that the caller
 * built from {@code Notes.list()}. There is no setter for a note, no per-note delete and no edit
 * affordance anywhere in this class - a human can page through what the agent wrote.</p>
 *
 * <p>The one destructive action is the human-only "清空全部" (issue #47), and even that one is a
 * state machine: {@link #clickClear(long)} only <em>asks</em> for a reset after the second click.
 * The wipe itself is done by {@code Notes.clearAll()} in the caller, and no MCP tool exposes it.</p>
 *
 * <p>Page model: {@link #PAGE_SIZE} rows are visible at once; the window starts at {@link #firstRow()}
 * and can be nudged one row (arrow keys / wheel) or one page (PgUp / PgDn). {@link #page()} is
 * 1-based, and {@code End} on 55 notes parks on page 5 with the last 7 rows, so paging through the
 * whole store never repeats a note.</p>
 */
public final class NotesPanel {

    /** Rows visible at once (issue #46: 12 rows per page). */
    public static final int PAGE_SIZE = 12;
    /** Appended to a row that had to be cut to fit. */
    public static final String ELLIPSIS = "…";
    /** Hard character cap for one row, whatever the measured width would allow. */
    public static final int DEFAULT_MAX_ROW_CHARS = 120;
    /** Fallback row width in pixels; the renderer replaces it with the real panel inner width. */
    public static final float DEFAULT_ROW_WIDTH = 1000f;
    /** Kind used when a key carries no {@code kind:} prefix. */
    public static final String DEFAULT_KIND = "general";
    /** How long the human-only "清空全部" button stays armed before it cancels itself (issue #47). */
    public static final long CLEAR_CONFIRM_MS = 3000L;
    /** Idle label of the reset button. */
    public static final String CLEAR_LABEL = "清空全部";
    /** Armed label: the button is red and the second click really wipes every note. */
    public static final String CLEAR_CONFIRM_LABEL = "再点一次确认清空，3 秒后取消";

    /** {@code notes_panel} switch (default on), wired from {@link spirelink.SpireLinkConfig}. */
    private static volatile boolean enabled = true;

    /**
     * Turns a note into the three pieces a row shows.
     *
     * <p>The game-side implementation delegates to {@link NoteHud} (localized kind names, monster /
     * card / relic display names, colour-marker stripping); tests inject a plain stub, which is why
     * this class stays free of game types.</p>
     */
    public interface Formatter {
        /** Localized kind label, e.g. {@code 敌人}. */
        String kindLabel(String kind);

        /** Best-effort human name for a note id. */
        String displayName(String kind, String id);

        /** Strips game markup and folds the text for single-line display. */
        String body(String text);
    }

    /** Measures a rendered row, so clipping matches the font the renderer will use. */
    public interface Measurer {
        float width(String text);
    }

    /** One note, already detached from the JSON store. */
    public static final class Entry {
        public final String key;
        public final String kind;
        public final String id;
        public final String text;
        public final long updatedAt;

        public Entry(String key, String kind, String id, String text, long updatedAt) {
            this.key = key == null ? "" : key;
            this.kind = kind == null || kind.isEmpty() ? DEFAULT_KIND : kind;
            this.id = id == null ? "" : id;
            this.text = text == null ? "" : text;
            this.updatedAt = updatedAt;
        }
    }

    private final Formatter formatter;
    private final List<Entry> entries = new ArrayList<Entry>();
    private final List<String> rows = new ArrayList<String>();

    private Measurer measurer;
    private float rowWidth = DEFAULT_ROW_WIDTH;
    private int maxRowChars = DEFAULT_MAX_ROW_CHARS;

    private boolean open;
    private int firstRow;

    /** True between the first and the second click on "清空全部". */
    private boolean confirmingClear;
    /** Wall clock (ms) the confirm window started at; only meaningful while confirming. */
    private long confirmSinceMs;

    public NotesPanel(Formatter formatter) {
        this.formatter = formatter;
    }

    // ---- switch ----

    /** Applied by {@link spirelink.SpireLinkConfig} when the config is (re)read. */
    public static void configure(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // ---- parsing ----

    /**
     * Detaches the note store ({@code Notes.list(null)}: {@code {key: {text, updated_at}}}) into
     * entries. Malformed entries are skipped, never thrown on: a broken notes.json must not kill the
     * main menu.
     */
    public static List<Entry> parse(JsonObject notes) {
        List<Entry> out = new ArrayList<Entry>();
        if (notes == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : notes.entrySet()) {
            JsonElement value = e.getValue();
            if (value == null || !value.isJsonObject()) {
                continue;
            }
            JsonObject entry = value.getAsJsonObject();
            JsonElement text = entry.get("text");
            if (text == null || text.isJsonNull()) {
                continue;
            }
            long updatedAt = 0L;
            try {
                JsonElement updated = entry.get("updated_at");
                if (updated != null && !updated.isJsonNull()) {
                    updatedAt = updated.getAsLong();
                }
            } catch (Throwable ignored) {
                // a bad timestamp only costs us the sort order
            }
            String key = e.getKey();
            out.add(new Entry(key, kindOf(key), idOf(key), asText(text), updatedAt));
        }
        return out;
    }

    /** {@code monster:JawWorm -> monster}; a key without a prefix is a {@code general} note. */
    public static String kindOf(String key) {
        if (key == null) {
            return DEFAULT_KIND;
        }
        int colon = key.indexOf(':');
        if (colon <= 0) {
            return DEFAULT_KIND;
        }
        return key.substring(0, colon);
    }

    /** {@code monster:JawWorm -> JawWorm}; a key without a prefix is its own id. */
    public static String idOf(String key) {
        if (key == null) {
            return "";
        }
        int colon = key.indexOf(':');
        return colon < 0 ? key : key.substring(colon + 1);
    }

    private static String asText(JsonElement text) {
        try {
            return text.isJsonPrimitive() ? text.getAsString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ---- layout inputs ----

    /** The renderer's row measurer (font metrics); {@code null} falls back to a character cap. */
    public void setMeasurer(Measurer value) {
        this.measurer = value;
    }

    /** Usable width of one row in pixels. */
    public void setRowWidth(float value) {
        this.rowWidth = value <= 0f ? DEFAULT_ROW_WIDTH : value;
    }

    public float rowWidth() {
        return rowWidth;
    }

    /** Hard character cap on top of the pixel clip. */
    public void setMaxRowChars(int value) {
        this.maxRowChars = value <= 0 ? DEFAULT_MAX_ROW_CHARS : value;
    }

    // ---- open / close ----

    public boolean isOpen() {
        return open;
    }

    /** Opens the panel on the newest page with a fresh snapshot of the notes. */
    public void open(List<Entry> snapshot) {
        entries.clear();
        if (snapshot != null) {
            entries.addAll(snapshot);
        }
        Collections.sort(entries, NEWEST_FIRST);
        rebuildRows();
        open = true;
        firstRow = 0;
        cancelClear();
    }

    public void close() {
        open = false;
        firstRow = 0;
        cancelClear();
    }

    /** Re-clips the cached rows after the panel was resized; keeps the current window. */
    public void rebuildRows() {
        rows.clear();
        for (int i = 0; i < entries.size(); i++) {
            rows.add(clip(formatRow(entries.get(i))));
        }
        clampFirstRow();
    }

    // ---- content ----

    /** Number of notes in this snapshot. */
    public int count() {
        return entries.size();
    }

    /** {@code 第 1/5 页 · 共 55 条}. */
    public String header() {
        return header(page(), pageCount(), count());
    }

    /** Static form so the closed-panel entry can show a live count. */
    public static String header(int page, int pageCount, int count) {
        return "第 " + page + "/" + pageCount + " 页 · 共 " + count + " 条";
    }

    /** {@code 便签（55）}: the main-menu entry label. */
    public static String entryLabel(int count) {
        return "便签（" + Math.max(0, count) + "）";
    }

    public int pageCount() {
        int count = count();
        if (count <= 0) {
            return 1;
        }
        return (count + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    /** 1-based page the window currently starts on (row-scrolling can leave it mid-page). */
    public int page() {
        return Math.max(1, Math.min(pageCount(), firstRow / PAGE_SIZE + 1));
    }

    /** Index (0-based) of the first visible row. */
    public int firstRow() {
        return firstRow;
    }

    /**
     * Highest legal {@link #firstRow()}: the last page starts here, so 55 notes page 5 shows rows
     * 49-55 instead of scrolling past the end and repeating rows already seen.
     */
    public int maxFirstRow() {
        return Math.max(0, (pageCount() - 1) * PAGE_SIZE);
    }

    /** How many rows this window really has (12 on a full page, fewer on the last one). */
    public int visibleCount() {
        return Math.max(0, Math.min(PAGE_SIZE, count() - firstRow));
    }

    /** Row text inside the current page window; {@code null} outside it. */
    public String row(int index) {
        if (index < 0 || index >= visibleCount()) {
            return null;
        }
        int at = firstRow + index;
        return at < rows.size() ? rows.get(at) : null;
    }

    // ---- hit-testing ----

    /**
     * Rectangle hit-test in game coordinates (bottom-up), shared by the entry label and the centred
     * plate so the click area is exactly what was drawn.
     */
    public static boolean contains(float rx, float ry, float rw, float rh, float x, float y) {
        if (rw <= 0f || rh <= 0f) {
            return false;
        }
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    // ---- navigation ----

    /** Arrow keys / wheel: move the window by {@code delta} rows. */
    public void scrollRows(int delta) {
        if (delta == 0) {
            return;
        }
        firstRow = clamp(firstRow + delta, 0, maxFirstRow());
    }

    /** PgUp / PgDn: jump exactly one page, clamped to the last full page. */
    public void pageStep(int pages) {
        int step = pages > 0 ? 1 : (pages < 0 ? -1 : 0);
        int left = Math.abs(pages);
        for (int i = 0; i < left && step != 0; i++) {
            if (step > 0) {
                int next = (firstRow / PAGE_SIZE + 1) * PAGE_SIZE;
                firstRow = clamp(next, 0, maxFirstRow());
            } else {
                int prev = ((Math.max(0, firstRow - 1)) / PAGE_SIZE) * PAGE_SIZE;
                firstRow = clamp(prev, 0, maxFirstRow());
            }
        }
    }

    /** Home. */
    public void first() {
        firstRow = 0;
    }

    /** End. */
    public void last() {
        firstRow = maxFirstRow();
    }

    // ---- clear all (issue #47) ----

    /**
     * True while the reset button is armed. This class never wipes anything by itself: a
     * {@code true} from {@link #clickClear(long)} is a request, the caller runs the store reset and
     * re-opens the panel on the now empty snapshot.
     */
    public boolean isConfirmingClear() {
        return confirmingClear;
    }

    /** Button caption for the current state (longer and red once armed). */
    public String clearLabel() {
        return confirmingClear ? CLEAR_CONFIRM_LABEL : CLEAR_LABEL;
    }

    /**
     * One click on the reset button.
     *
     * <p>First click arms the button and returns {@code false} - nothing is deleted. A second click
     * inside {@link #CLEAR_CONFIRM_MS} returns {@code true} and the caller performs the reset.
     * Anything else leaves the store untouched: a click after the window already lapsed is treated
     * as a fresh first click, and {@link #cancelClear()} / {@link #open} / {@link #close} drop the
     * arming altogether.</p>
     */
    public boolean clickClear(long nowMs) {
        if (confirmingClear && !clearExpired(nowMs)) {
            confirmingClear = false;
            confirmSinceMs = 0L;
            return true;
        }
        confirmingClear = true;
        confirmSinceMs = nowMs;
        return false;
    }

    /**
     * Frame tick: disarms the button on its own once the 3 s window is over, so a half-finished
     * confirmation can never survive to a later click. Returns {@code true} when it just cancelled.
     */
    public boolean tickClear(long nowMs) {
        if (!confirmingClear || !clearExpired(nowMs)) {
            return false;
        }
        confirmingClear = false;
        confirmSinceMs = 0L;
        return true;
    }

    /** A click anywhere else, ESC, or a reopen: drops a pending confirmation. */
    public boolean cancelClear() {
        if (!confirmingClear) {
            return false;
        }
        confirmingClear = false;
        confirmSinceMs = 0L;
        return true;
    }

    /** Milliseconds left in the confirm window; 0 when the button is not armed. */
    public long clearRemainingMs(long nowMs) {
        if (!confirmingClear) {
            return 0L;
        }
        long left = CLEAR_CONFIRM_MS - (nowMs - confirmSinceMs);
        return left < 0L ? 0L : left;
    }

    private boolean clearExpired(long nowMs) {
        return nowMs - confirmSinceMs >= CLEAR_CONFIRM_MS;
    }

    // ---- row formatting ----

    private String formatRow(Entry entry) {
        String kind = formatter == null ? null : formatter.kindLabel(entry.kind);
        if (kind == null || kind.isEmpty()) {
            kind = entry.kind;
        }
        String name = formatter == null ? entry.id : formatter.displayName(entry.kind, entry.id);
        name = oneLine(name);
        String rawBody = formatter == null ? entry.text : formatter.body(entry.text);
        String body = oneLine(rawBody);

        StringBuilder sb = new StringBuilder(64);
        sb.append('[').append(kind).append(']');
        if (!name.isEmpty()) {
            sb.append(' ').append(name);
        }
        if (!body.isEmpty()) {
            sb.append('：').append(body);
        }
        return sb.toString();
    }

    /** Everything on one line: the panel draws one note per row, so newlines become spaces. */
    static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean space = c == '\r' || c == '\n' || c == '\t' || c == ' ';
            if (space) {
                if (!lastWasSpace && sb.length() > 0) {
                    sb.append(' ');
                }
                lastWasSpace = true;
            } else {
                sb.append(c);
                lastWasSpace = false;
            }
        }
        return sb.toString().trim();
    }

    /**
     * Cuts a row to the configured character cap, then to the measured pixel width. The width cut is
     * a binary search over prefixes, so the result is the longest prefix that still fits plus an
     * ellipsis - a 208-character note in a 1000px panel ends with "…", it never runs off the plate.
     */
    String clip(String text) {
        if (text == null) {
            return "";
        }
        String out = text;
        if (maxRowChars > 0 && out.length() > maxRowChars) {
            out = out.substring(0, Math.max(0, maxRowChars - 1)) + ELLIPSIS;
        }
        Measurer m = measurer;
        float limit = rowWidth;
        if (m == null || limit <= 0f) {
            return out;
        }
        try {
            if (m.width(out) <= limit) {
                return out;
            }
            int lo = 0;
            int hi = out.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                String candidate = out.substring(0, mid) + ELLIPSIS;
                if (m.width(candidate) <= limit) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return out.substring(0, lo) + ELLIPSIS;
        } catch (Throwable ignored) {
            return out;
        }
    }

    private void clampFirstRow() {
        firstRow = clamp(firstRow, 0, maxFirstRow());
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    /** Newest first; ties fall back to the key so the order never flickers between frames. */
    private static final Comparator<Entry> NEWEST_FIRST = new Comparator<Entry>() {
        @Override
        public int compare(Entry a, Entry b) {
            if (a.updatedAt != b.updatedAt) {
                return a.updatedAt > b.updatedAt ? -1 : 1;
            }
            return a.key.compareTo(b.key);
        }
    };
}
