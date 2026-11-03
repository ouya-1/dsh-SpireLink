package spirelink.ui;

import java.util.List;

/**
 * Process-wide agent activity feed for the in-game HUD (design P0.5).
 *
 * <p>No game or libgdx types here: MCP worker threads call {@link #push(String)} directly and
 * the renderer ({@link AgentHudRenderer}) reads {@link #lines()} on the game thread. All methods
 * are thread safe.
 *
 * <p>The process boundary matters: starting/stopping the game, waiting for {@code /health} and
 * similar concerns live in {@code scripts/start-game.bat}, <em>not</em> in the mod. This class
 * only observes what the MCP side did inside the running game.
 */
public final class AgentHud {

    /** Messages used by the transport/tool layer; short so they fit one row. */
    public static final String MSG_CONNECTED = "Agent 已连接";
    public static final String MSG_WAITING = "正在琢磨怎么打…";
    public static final String MSG_NOT_READY = "游戏未就绪";
    public static final String MSG_BUSY = "上一条还在处理";
    public static final String MSG_ERROR = "读取异常";
    public static final String MSG_UNAUTHORIZED = "鉴权失败，已拒绝";
    public static final String MSG_UNKNOWN_TOOL = "未知工具被拒";
    /** Last time each unknown tool name was shown, so probing agents cannot flood the HUD. */
    private static final java.util.Map<String, Long> UNKNOWN_TOOL_SEEN =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();
    private static final long UNKNOWN_TOOL_DEDUPE_MS = 30000L;

    /** Shows "未知名工具：<name>" at most once per name per 30 s. */
    public static void unknownTool(String name) {
        String label = name == null || name.isEmpty() ? "?" : name;
        Long last = UNKNOWN_TOOL_SEEN.get(label);
        long now = System.currentTimeMillis();
        if (last != null && now - last.longValue() < UNKNOWN_TOOL_DEDUPE_MS) {
            return;
        }
        UNKNOWN_TOOL_SEEN.put(label, Long.valueOf(now));
        push("未知名工具：" + label);
    }
    public static final String MSG_SESSION_LOST = "会话失效，重连";

    private static final HudModel MODEL = new HudModel();
    private static volatile boolean enabled = true;
    private static volatile boolean idleFadeEnabled = true;

    /** The single note line (agent's notes about the current content), drawn above the feed. */
    private static volatile String noteText;
    private static volatile long noteStartedAt;
    private static volatile long noteExpireAt;
    /** Fade-out window at the end of the note line's life. */
    private static final long NOTE_FADE_MS = 1200L;

    /**
     * The thought block (issue #28): the agent's current reasoning, drawn top-left above the
     * activity feed. Fed by the off-MCP endpoint {@code POST /hud/say} (the "tee" script), never by
     * a tool call, so the agent's prompt and {@code tools/list} stay byte-identical.
     */
    private static volatile String thoughtText;
    private static volatile long thoughtStartedAt;
    private static volatile long thoughtExpireAt;
    /** Hard cap for one thought, wrapping into 2-3 rows happens in the renderer. */
    public static final int THOUGHT_MAX_CHARS = 240;
    /** TTL used when the caller sends 0/negative: long enough to cover one step, short enough to fade. */
    public static final long DEFAULT_THOUGHT_TTL_MS = 12000L;
    private static final long THOUGHT_MIN_TTL_MS = 1500L;
    /** Fade-out window at the end of the thought block's life (acceptance: 按 TTL 淡出). */
    public static final long THOUGHT_FADE_MS = 1500L;

    /**
     * Test seam: the note/thought timers read the wall clock through this, so unit tests drive time
     * instead of sleeping. Production always keeps {@link HudModel#SYSTEM_CLOCK}.
     */
    private static volatile HudModel.Clock clock = HudModel.SYSTEM_CLOCK;

    private AgentHud() {
    }

    /** Applies config: {@code hud=false} disables drawing, {@code lines} is clamped 1..6. */
    public static void configure(boolean hudEnabled, int lines) {
        configure(hudEnabled, lines, true);
    }

    public static void configure(boolean hudEnabled, int lines, boolean idleFade) {
        enabled = hudEnabled;
        idleFadeEnabled = idleFade;
        MODEL.configure(lines);
    }

    /** Overall alpha multiplier: 1.0 while the agent is active, dimmed when it has been quiet. */
    public static float idleFactor() {
        if (!idleFadeEnabled) {
            return 1.0f;
        }
        return HudModel.idleAlphaFactor(MODEL.idleMillis());
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Records one line; blank text is ignored. Safe to call from any thread. */
    public static void push(String text) {
        // "thinking" lines also drive the fish pointer's busy face.
        try {
            boolean thinking = MSG_WAITING.equals(text) || MSG_BUSY.equals(text)
                    || MSG_NOT_READY.equals(text);
            AgentCursor.setBusy(thinking);
            if (MSG_NOT_READY.equals(text)) {
                AgentCursor.setUnavailable(true);
            } else if (!thinking) {
                AgentCursor.setUnavailable(false);
            }
        } catch (Throwable ignored) {
            // the pointer is decoration
        }
        if (!enabled) {
            return;
        }
        MODEL.push(text);
    }

    /** Convenience for actions (P1: 打出 打击 / 结束回合 / 用药水 …). */
    public static void action(String text) {
        // Same sentence feeds the fish pointer's caption, so the two can never disagree.
        AgentCursor.label(text);
        push(text);
    }

    /**
     * Shows one note line (A: wrote, B: read back, C: already has a note) for {@code durationMs}.
     * Safe to call from any thread.
     */
    public static void note(String text, long durationMs) {
        if (!enabled || text == null || text.isEmpty()) {
            return;
        }
        long now = now();
        noteText = text;
        noteStartedAt = now;
        noteExpireAt = now + Math.max(1500L, durationMs);
        spirelink.util.Diag.info("HUD 笔记行: " + text);
    }

    /** Current note line, or null when it already faded. */
    public static String noteText() {
        String text = noteText;
        if (text == null || now() >= noteExpireAt) {
            return null;
        }
        return text;
    }

    /** 1.0 while fresh, linear fade over the last {@link #NOTE_FADE_MS}. */
    public static float noteAlpha() {
        String text = noteText;
        if (text == null) {
            return 0f;
        }
        long now = now();
        if (now >= noteExpireAt) {
            return 0f;
        }
        long remaining = noteExpireAt - now;
        if (remaining >= NOTE_FADE_MS) {
            return 1f;
        }
        return remaining / (float) NOTE_FADE_MS;
    }

    /**
     * Shows the agent's current thought for {@code ttlMs} ({@code <= 0} means
     * {@link #DEFAULT_THOUGHT_TTL_MS}). Safe to call from any thread - the HTTP worker does.
     *
     * <p>Blank text is ignored so a silent step never clears the previous thought (issue #28:
     * "这一步没有思考时保留上一条，不要清屏").
     *
     * @return true when the text was accepted
     */
    public static boolean thought(String text, long ttlMs) {
        String normalized = normalizeThought(text);
        if (!enabled || normalized == null) {
            return false;
        }
        long now = now();
        thoughtText = normalized;
        thoughtStartedAt = now;
        thoughtExpireAt = now + Math.max(THOUGHT_MIN_TTL_MS,
                ttlMs > 0 ? ttlMs : DEFAULT_THOUGHT_TTL_MS);
        spirelink.util.Diag.info("HUD 思考: " + normalized);
        return true;
    }

    /** Current thought, or null once its TTL ran out (the renderer then gives the rows back). */
    public static String thoughtText() {
        String text = thoughtText;
        if (text == null || now() >= thoughtExpireAt) {
            return null;
        }
        return text;
    }

    /** 1.0 while fresh, linear fade over the last {@link #THOUGHT_FADE_MS} (0 when gone). */
    public static float thoughtAlpha() {
        String text = thoughtText;
        if (text == null) {
            return 0f;
        }
        long now = now();
        if (now >= thoughtExpireAt) {
            return 0f;
        }
        long remaining = thoughtExpireAt - now;
        if (remaining >= THOUGHT_FADE_MS) {
            return 1f;
        }
        return remaining / (float) THOUGHT_FADE_MS;
    }

    /** When the current thought was accepted, or 0 when there never was one. */
    public static long thoughtStartedAt() {
        return thoughtStartedAt;
    }

    /** Forgets the current thought immediately (used by tests and by {@link #clear()}). */
    public static void clearThought() {
        thoughtText = null;
        thoughtStartedAt = 0L;
        thoughtExpireAt = 0L;
    }

    /** Drops trailing whitespace/control noise and caps the text at {@link #THOUGHT_MAX_CHARS}. */
    static String normalizeThought(String raw) {
        if (raw == null) {
            return null;
        }
        // Reasoning arrives with newlines and markdown indentation; the block has 3 rows, so one
        // flat sentence is the only shape that survives wrapping.
        String flat = raw.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return null;
        }
        if (flat.length() > THOUGHT_MAX_CHARS) {
            return flat.substring(0, THOUGHT_MAX_CHARS - 1) + HudModel.ELLIPSIS;
        }
        return flat;
    }

    /** Wall clock used by the note/thought timers (see {@link #clock}). */
    private static long now() {
        return clock.nowMillis();
    }

    /** Package-visible test seam; production never calls it. */
    static void setClock(HudModel.Clock newClock) {
        clock = newClock == null ? HudModel.SYSTEM_CLOCK : newClock;
    }

    /** Oldest -> newest (newest is drawn at the bottom). */
    public static List<HudModel.Line> lines() {
        return MODEL.lines();
    }

    public static int capacity() {
        return MODEL.capacity();
    }

    /** Clears the activity feed and the thought block (the note line keeps its own lifetime). */
    public static void clear() {
        MODEL.clear();
        clearThought();
    }
}
