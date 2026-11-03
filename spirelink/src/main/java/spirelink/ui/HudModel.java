package spirelink.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure ring buffer behind the agent HUD (design P0.5).
 *
 * <p>Kept free of game/libgdx imports on purpose: {@link AgentHud} only delegates here, so
 * {@code AgentHudTest} can run in plain JUnit without loading any Slay the Spire class.
 *
 * <p>Newest line is last in {@link #lines()} (the renderer draws top -> bottom, so the newest
 * entry ends up at the bottom of the panel).
 */
public final class HudModel {

    /** Default number of visible lines. */
    public static final int DEFAULT_LINES = 4;
    public static final int MIN_LINES = 1;
    public static final int MAX_LINES = 6;

    /** Hard cut for one line; longer text is shortened and suffixed with an ellipsis. */
    public static final int MAX_CHARS = 20;
    public static final String ELLIPSIS = "\u2026";
    /** Prefix drawn in front of every line. */
    public static final String BULLET = "\u203a ";

    /** One visible line; {@code age == 0} means "just happened". */
    public static final class Line {
        public final String text;
        public final int age;
        /** How many identical events this row stands for (1 = no merge). */
        public final int count;

        Line(String text, int age, int count) {
            this.text = text;
            this.age = age;
            this.count = count;
        }

        /** Text as drawn: {@code Agent 已连接 ×2} when repeats were merged. */
        public String display() {
            return count > 1 ? text + " ×" + count : text;
        }
    }

    /** Internal row: text plus how often it repeated consecutively. */
    private static final class Entry {
        final String text;
        int count;

        Entry(String text) {
            this.text = text;
            this.count = 1;
        }
    }

    /** Injectable clock so idle-fade logic is unit testable without real time. */
    public interface Clock {
        long nowMillis();
    }

    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }
    };

    /** Idle thresholds (design P0.5 v2): past 8 s dim a bit, past 20 s almost vanish. */
    public static final long IDLE_DIM_MILLIS = 8000L;
    public static final long IDLE_FAINT_MILLIS = 20000L;
    public static final float IDLE_DIM_FACTOR = 0.55f;
    public static final float IDLE_FAINT_FACTOR = 0.30f;

    private final Clock clock;
    private final List<Entry> entries = new ArrayList<>();
    private int capacity = DEFAULT_LINES;
    private long lastPushMillis;

    public HudModel() {
        this(SYSTEM_CLOCK);
    }

    public HudModel(Clock clock) {
        this.clock = clock == null ? SYSTEM_CLOCK : clock;
    }

    /** Clamps to {@code [MIN_LINES, MAX_LINES]} and drops the oldest overflow. */
    public synchronized void configure(int capacity) {
        this.capacity = Math.max(MIN_LINES, Math.min(MAX_LINES, capacity));
        trim();
    }

    public synchronized int capacity() {
        return capacity;
    }

    /**
     * Appends one line. Blank input is ignored.
     *
     * @return true when the line was accepted
     */
    public synchronized boolean push(String raw) {
        String text = normalize(raw);
        if (text == null) {
            return false;
        }
        // Merge only with the immediately preceding row: two sessions both saying "Agent 已连接"
        // become one row with ×2 instead of flooding the panel.
        if (!entries.isEmpty()) {
            Entry last = entries.get(entries.size() - 1);
            if (last.text.equals(text)) {
                last.count++;
                lastPushMillis = clock.nowMillis();
                return true;
            }
        }
        entries.add(new Entry(text));
        lastPushMillis = clock.nowMillis();
        trim();
        return true;
    }

    /** When the last accepted line arrived, or 0 when nothing was pushed yet. */
    public long lastPushMillis() {
        return lastPushMillis;
    }

    /** Milliseconds since the last accepted line; {@link Long#MAX_VALUE} when never pushed. */
    public long idleMillis() {
        long last = lastPushMillis;
        if (last == 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, clock.nowMillis() - last);
    }

    /** Overall multiplier applied to the panel while the agent is idle. */
    public static float idleAlphaFactor(long idleMillis) {
        if (idleMillis > IDLE_FAINT_MILLIS) {
            return IDLE_FAINT_FACTOR;
        }
        if (idleMillis > IDLE_DIM_MILLIS) {
            return IDLE_DIM_FACTOR;
        }
        return 1.0f;
    }

    /** Per-line alpha by age: newest 1.0, then 0.80 / 0.60 / 0.45 / 0.32 (older lines reuse 0.32). */
    public static float lineAlpha(int age) {
        switch (age) {
            case 0:
                return 1.00f;
            case 1:
                return 0.80f;
            case 2:
                return 0.60f;
            case 3:
                return 0.45f;
            default:
                return 0.32f;
        }
    }

    /** Oldest -> newest, each with its age (0 = newest) and repeat count. */
    public synchronized List<Line> lines() {
        int size = entries.size();
        List<Line> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Entry entry = entries.get(i);
            out.add(new Line(entry.text, size - 1 - i, entry.count));
        }
        return out;
    }

    public synchronized List<String> texts() {
        List<String> out = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            out.add(entry.text);
        }
        return out;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** Trims, rejects blanks and shortens to {@link #MAX_CHARS} (ellipsis included). */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Collapse whitespace runs so one event can never become two rows.
        trimmed = trimmed.replaceAll("\\s+", " ");
        if (trimmed.length() > MAX_CHARS) {
            return trimmed.substring(0, MAX_CHARS - 1) + ELLIPSIS;
        }
        return trimmed;
    }

    private void trim() {
        while (entries.size() > capacity) {
            entries.remove(0);
        }
    }
}
