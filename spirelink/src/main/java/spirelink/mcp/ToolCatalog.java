package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import spirelink.actions.ActionExecutor;
import spirelink.actions.ActionPlanner;
import spirelink.actions.ActionResult;
import spirelink.actions.ActionSpec;
import spirelink.actions.IdempotencyStore;
import spirelink.state.StateBuilder;
import spirelink.ui.AgentHud;
import spirelink.util.ModLog;

import java.util.List;
import java.util.Map;

/**
 * Tool registry (design 13). P1 exposes {@code get_state}, {@code legal_actions} and {@code act}.
 *
 * <p>The remaining tool names are fixed constants and deliberately not registered — the harness
 * caches {@code tools/list}, so exposing a half-implemented tool would be worse than not having it.
 *
 * <p>Process boundary: starting/stopping the game and waiting for {@code /health} belong to
 * {@code scripts/start-game.bat}; everything that touches the live game object graph (state,
 * legal actions, action execution) belongs here and runs on the game thread.
 */
public final class ToolCatalog {

    public static final String TOOL_GET_STATE = "get_state";

    /**
     * The HUD thought channel (issue #28) as a first-class tool.
     *
     * <p>Agents that wire the MCP server up as native tools ({@code mcp__spirelink__act}, ...) never
     * enter a code sandbox, so a plain HTTP endpoint was unreachable for them in practice: the
     * thought block stayed empty even though the prompt said "say something every step". Exposing
     * it here keeps "be visible" inside the same tool flow as {@code act}.
     */
    public static final String TOOL_SAY = "say";

    // Reserved names (design 13), not exposed in P0.
    public static final String TOOL_LEGAL_ACTIONS = "legal_actions";
    public static final String TOOL_ACT = "act";
    public static final String TOOL_CARD_INFO = "card_info";
    public static final String TOOL_NOTES_LIST = "notes_list";
    public static final String TOOL_EVENT_NOTE = "event_note";
    public static final String TOOL_DEV_EVENT = "dev_event";
    public static final String TOOL_DEV_MAP = "dev_map";
    public static final String TOOL_DEV_ROOM = "dev_room";
    public static final String TOOL_DEV_ENCOUNTER = "dev_encounter";
    public static final String TOOL_DEV_POTION = "dev_potion";
    public static final String TOOL_DEV_HEAL = "dev_heal";
    public static final String TOOL_DEV_USE_CARD = "dev_use_card";
    public static final String TOOL_DEV_CARD = "dev_card";
    public static final String TOOL_DEV_RELIC = "dev_relic";
    public static final String TOOL_DEV_REWARD = "dev_reward";
    public static final String TOOL_INSPECT = "inspect";
    public static final String TOOL_PLAN_ROUTE = "plan_route";
    public static final String TOOL_COMPENDIUM = "compendium";
    public static final String TOOL_GLOSSARY = "glossary";
    public static final String TOOL_NOTE_SET = "note_set";
    public static final String TOOL_NOTE_GET = "note_get";
    public static final String TOOL_NOTE_RETRACT = "note_retract";
    public static final String TOOL_RUN_LOG = "run_log";
    public static final String TOOL_SUBMIT_LESSON = "submit_lesson";

    /** {@code notes_list.sort}: newest update first (default) or by {@code kind:id} key. */
    public static final String NOTES_SORT_RECENT = "recent";
    public static final String NOTES_SORT_NAME = "name";
    /** The pre-#54 spelling of {@link #NOTES_SORT_NAME}: still accepted, never advertised. */
    static final String NOTES_SORT_LEGACY_ID = "id";

    /** One command may wait at most 10 s on the main thread (design 5). */
    public static final long COMMAND_TIMEOUT_MS = 10_000L;

    // The in-flight (queued -> ACTION_IN_FLIGHT) wall clock has exactly one source of truth:
    // ActionInFlight.TIMEOUT_MS (3 s). Never declare a second timeout constant here - the old
    // IN_FLIGHT_TIMEOUT_MS = 30_000 (with queuedAt / queuedVersion) was written but never read,
    // and the docs quoted its 30 s as if it were live (issue #54).

    /** After {@code act}, how long to wait for a new stable state before answering {@code queued}. */
    public static final long ACT_STABLE_TIMEOUT_MS = 10_000L;

    /**
     * Issue #123 · C14-03: how long a <em>read</em> may wait for the main thread while a run-start load
     * is in progress.
     *
     * <p>Vanilla builds the whole dungeon on the game thread (measured: ~10 s for a loaded save), so a
     * read that waits for it cannot answer either - and then the loading window is invisible to a
     * polling agent, which was exactly QA's complaint ("载入窗口从未出现"). During the window reads
     * answer from the cache instead; the NOT_READY wording already explains the wait.</p>
     */
    static final long LOADING_READ_TIMEOUT_MS = 500L;
    /**
     * Issue #124 · C15-06: how often the act path re-checks {@code state_version} while waiting for the
     * world to settle.
     *
     * <p>A {@code play_card} measures ~0.98-1.08 s, and almost all of it is vanilla's own animation
     * (card play + hit flash) - the version only moves once that is over, so waiting less is not an
     * option without lying about {@code applied}. What we <em>can</em> remove is our own quantization:
     * at 100 ms the loop could report up to one tick late, at 40 ms that drops to 40 ms (measured cost:
     * one volatile read + a sleep).</p>
     */
    private static final long ACT_POLL_MS = 40L;

    /** Tool-level outcome: {@code payload} is the business JSON, {@code isError} the MCP flag. */
    public static final class ToolResult {
        public final JsonObject payload;
        public final boolean isError;

        ToolResult(JsonObject payload, boolean isError) {
            this.payload = payload;
            this.isError = isError;
        }
    }

    private final MainThreadQueue queue;
    private final StateBuilder stateBuilder;
    private final ModLog log;
    private final IdempotencyStore idempotency = new IdempotencyStore();

    public ToolCatalog(MainThreadQueue queue, StateBuilder stateBuilder, ModLog log) {
        this.queue = queue;
        this.stateBuilder = stateBuilder;
        this.log = log;
    }

    /** {@code tools/list} body: get_state + legal_actions + act. */
    public JsonArray listTools() {
        JsonArray tools = new JsonArray();
        tools.add(getStateDefinition());
        tools.add(legalActionsDefinition());
        tools.add(actDefinition());
        tools.add(sayDefinition());
        tools.add(cardInfoDefinition());
        tools.add(GlossaryTool.definition());
        tools.add(noteSetDefinition());
        tools.add(noteGetDefinition());
        tools.add(notesListDefinition());
        tools.add(eventNoteDefinition());
        if (devToolsEnabled()) {
            tools.add(devEventDefinition());
            tools.add(devMapDefinition());
            tools.add(devRoomDefinition());
            tools.add(devEncounterDefinition());
            tools.add(devPotionDefinition());
            tools.add(devCardDefinition());
            tools.add(devRelicDefinition());
            tools.add(devRewardDefinition());
            tools.add(devHealDefinition());
            tools.add(devUseCardDefinition());
        }
        return tools;
    }

    private JsonObject sayDefinition() {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_SAY);
        tool.addProperty("description",
                "把一行字推到游戏左上角的思考块 —— 人类靠它看见你在想什么。"
                        + "一次只显示最新一条，新的覆盖旧的；默认 12 秒后自己消失，"
                        + "想让它留着就再推一次。只影响显示，不改任何游戏状态。");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.add("text", stringProp("要显示的一行字（中文即可）。"));
        properties.add("ttl_ms", intProp("这行字停留多少毫秒，默认 12000。"));
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("text");
        schema.add("required", required);
        tool.add("inputSchema", schema);
        return tool;
    }

    /**
     * {@code say}: not a game action, so it never touches {@code state_version} or idempotency.
     *
     * <p>{@link AgentHud#thought(String, long)} is documented as safe from any thread (the HTTP
     * worker already calls it), so this runs straight off the MCP worker instead of the game queue.
     */
    private ToolResult sayHud(JsonObject arguments) {
        String text = "";
        if (arguments != null && arguments.has("text") && !arguments.get("text").isJsonNull()) {
            text = arguments.get("text").getAsString();
        }
        if (text.trim().isEmpty()) {
            return new ToolResult(error("BAD_REQUEST", "say 需要非空的 text", 0), true);
        }
        long ttlMs = 0L;
        if (arguments.has("ttl_ms") && !arguments.get("ttl_ms").isJsonNull()) {
            ttlMs = arguments.get("ttl_ms").getAsLong();
        }
        boolean shown = AgentHud.thought(text, ttlMs);
        JsonObject payload = new JsonObject();
        payload.addProperty("shown", shown);
        payload.addProperty("chars", text.length());
        payload.addProperty("ttl_ms", ttlMs > 0 ? ttlMs : AgentHud.DEFAULT_THOUGHT_TTL_MS);
        return new ToolResult(payload, false);
    }

    private JsonObject getStateDefinition() {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_GET_STATE);
        tool.addProperty("description",
                "读取当前局面快照。P0 返回 meta / player / potions / combat / hints 区块；"
                        + "meta.ui 是派生的界面标识，meta.state_version 与返回内容同源。"
                        + "动画未结束时返回 ok=false、error.code=NOT_READY。");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject include = new JsonObject();
        include.addProperty("type", "array");
        include.addProperty("description",
                "白名单：给了就只返回 meta + 这些区块（如 [\"combat\"]）。"
                        + "可用：player potions map memory event combat rewards boss_reward "
                        + "card_reward shop char_select neow match_game postmortem deck briefing hints "
                        + "discard_pile exhaust_pile draw_pile。"
                        + "要 deck / discard_pile / exhaust_pile / draw_pile 时，屏幕上会真的把那份牌表"
                        + "打开 4 秒（指针先走过去），人类看得见 agent 在读什么。"
                        + "briefing 可以单独要：单独要时以顶层 briefing 区块返回，"
                        + "和 memory 一起要时仍在 memory.briefing 下（不重复发）");
        JsonArray itemEnum = new JsonArray();
        for (String block : new String[]{"player", "potions", "map", "memory", "event", "combat",
                "rewards", "boss_reward", "card_reward", "shop", "char_select", "neow",
                "match_game", "postmortem", "deck", "briefing", "hints", "discard_pile",
                "exhaust_pile", "draw_pile"}) {
            itemEnum.add(block);
        }
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        items.add("enum", itemEnum);
        include.add("items", items);
        properties.add("include", include);
        JsonObject forceNotes = new JsonObject();
        forceNotes.addProperty("type", "boolean");
        forceNotes.addProperty("description",
                "true 时忽略「本局已推送过」的去重，把所有笔记再返回一次（调试用）");
        properties.add("force_notes", forceNotes);
        JsonObject exclude = new JsonObject();
        exclude.addProperty("type", "array");
        exclude.addProperty("description", "黑名单：从默认区块里去掉这些（如 [\"map\",\"memory\"]）");
        properties.add("exclude", exclude);
        schema.add("properties", properties);
        tool.add("inputSchema", schema);
        return tool;
    }

    /**
     * Executes one tool call and normalises the error shape of whatever came back (issue #86).
     *
     * <p>One wrapper, nineteen tools: the failure contract is decided here instead of in every
     * branch, so a new tool cannot quietly answer with a bare string code.
     *
     * @return {@code null} when the tool name is unknown (the transport maps that to -32602).
     */
    public ToolResult call(String name, JsonObject arguments) {
        ToolResult result = dispatch(name, arguments);
        if (result == null) {
            return null;
        }
        normalizeError(result.payload);
        return withHonestErrorFlag(name, result);
    }

    /**
     * Issue #86 side-find: a payload that says {@code ok:false} must also say {@code isError:true}.
     *
     * <p>{@code glossary} hard-coded {@code isError:false} on every call, so a failed lookup arrived
     * as "success with an error code inside" - a client that trusts the MCP flag never noticed the
     * failure at all. The flag is derived from {@code ok} here, once, for every tool.
     */
    private static ToolResult withHonestErrorFlag(String toolName, ToolResult result) {
        if (result.payload == null || !result.payload.has("ok")
                || result.payload.get("ok").isJsonNull()
                || !result.payload.get("ok").isJsonPrimitive()
                || result.payload.get("ok").getAsBoolean()) {
            return result;
        }
        // Issue #126 · C17-01: a read that answers "the world is not ready yet" is a successful read, not
        // a failed tool call. QA saw legal_actions come back as MCP isError:true while the body was a
        // perfectly normal {ready:false,...} - same second in which get_state answered the same thing.
        // Both directions are handled here: the flag may be missing (set it) or wrongly set (clear it).
        if (readsRefuseInsteadOfFailing(toolName, result.payload)) {
            return result.isError ? new ToolResult(result.payload, false) : result;
        }
        return result.isError ? result : new ToolResult(result.payload, true);
    }

    /**
     * Issue #126 · C17-01: which {@code ok:false} answers are still "the tool worked".
     *
     * <p>Only the two <b>read</b> tools, and only the "wait" code: {@code NOT_READY} says the game is in a
     * transition (loading / leaving the run / an animation), which is exactly what these tools are for.
     * Everything else - {@code INTERNAL}, {@code GAME_DEAD}, {@code BAD_REQUEST}, and every {@code act}
     * rejection - keeps {@code isError:true} (issue #86: a real failure must never arrive as success).</p>
     */
    static boolean readsRefuseInsteadOfFailing(String toolName, JsonObject payload) {
        if (payload == null) {
            return false;
        }
        boolean readTool = TOOL_GET_STATE.equals(toolName) || TOOL_LEGAL_ACTIONS.equals(toolName);
        if (!readTool || !payload.has("error") || !payload.get("error").isJsonObject()) {
            return false;
        }
        JsonObject error = payload.getAsJsonObject("error");
        if (!error.has("code") || error.get("code").isJsonNull()) {
            return false;
        }
        return "NOT_READY".equals(error.get("code").getAsString());
    }

    /** Routes a tool call to its implementation; {@code null} for an unknown name. */
    private ToolResult dispatch(String name, JsonObject arguments) {
        if (TOOL_SAY.equals(name)) {
            return sayHud(arguments);
        }
        if (TOOL_GET_STATE.equals(name)) {
            return getState(arguments);
        }
        if (TOOL_LEGAL_ACTIONS.equals(name)) {
            return legalActions();
        }
        if (TOOL_GLOSSARY.equals(name)) {
            JsonObject payload = GlossaryTool.run(arguments);
            // Issue #86: a failed lookup used to answer isError=false (see withHonestErrorFlag).
            return new ToolResult(payload, !isOk(payload));
        }
        if (TOOL_CARD_INFO.equals(name)) {
            return cardInfo(arguments);
        }
        if (TOOL_NOTE_SET.equals(name)) {
            return noteSet(arguments);
        }
        if (TOOL_NOTE_GET.equals(name)) {
            return noteGet(arguments);
        }
        if (TOOL_NOTES_LIST.equals(name)) {
            return notesList(arguments);
        }
        if (TOOL_EVENT_NOTE.equals(name)) {
            return eventNote(arguments);
        }
        if (TOOL_DEV_ROOM.equals(name)) {
            return devRoom(arguments);
        }
        if (TOOL_DEV_ENCOUNTER.equals(name)) {
            return devEncounter(arguments);
        }
        if (TOOL_DEV_POTION.equals(name)) {
            return devPotion(arguments);
        }
        if (TOOL_DEV_CARD.equals(name)) {
            return devCard(arguments);
        }
        if (TOOL_DEV_RELIC.equals(name)) {
            return devRelic(arguments);
        }
        if (TOOL_DEV_REWARD.equals(name)) {
            return devReward(arguments);
        }
        if (TOOL_DEV_HEAL.equals(name)) {
            return devHeal(arguments);
        }
        if (TOOL_DEV_USE_CARD.equals(name)) {
            return devUseCard(arguments);
        }
        if (TOOL_DEV_MAP.equals(name)) {
            return devMap();
        }
        if (TOOL_DEV_EVENT.equals(name)) {
            return devEvent(arguments);
        }
        if (TOOL_ACT.equals(name)) {
            return act(arguments);
        }
        return null;
    }

    /** Adds the optional blocks requested through {@code include:[...]} (only ever adds). */
    private static JsonArray arrayArg(JsonObject arguments, String name) {
        if (arguments == null || !arguments.has(name) || arguments.get(name).isJsonNull()) {
            return null;
        }
        try {
            return arguments.getAsJsonArray(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void attachIncludes(JsonObject body, JsonObject arguments) {
        if (arguments == null || !arguments.has("include")) {
            return;
        }
        JsonElement raw = arguments.get("include");
        if (raw == null || !raw.isJsonArray()) {
            return;
        }
        java.util.List<String> wanted = new java.util.ArrayList<>();
        for (JsonElement element : raw.getAsJsonArray()) {
            if (element != null && element.isJsonPrimitive()) {
                wanted.add(element.getAsString());
            }
        }
        if (wanted.contains("deck")) {
            addCardList(body, "deck", null);
        }
        // The piles are the other three "look at my cards" views a human opens by clicking a button.
        if (wanted.contains("discard_pile")) {
            addCardList(body, "discard_pile", "discard");
        }
        if (wanted.contains("exhaust_pile")) {
            addCardList(body, "exhaust_pile", "exhaust");
        }
        if (wanted.contains("draw_pile")) {
            addCardList(body, "draw_pile", "draw");
        }
    }

    /**
     * Issue #85: {@code include:["briefing"]} on its own used to answer nothing at all.
     *
     * <p>The briefing lives under {@code memory.briefing}, and {@link #refreshMemory} only refreshes
     * a memory block that is already present - so asking for {@code briefing} alone (or together with
     * anything but {@code memory}) returned {@code meta} and silence. Asked stand-alone the briefing
     * is now a top-level {@code briefing} block; asked together with {@code memory} it stays nested,
     * so one response never carries the same briefing twice.
     */
    private void attachBriefing(JsonObject payload, JsonObject arguments, boolean forceNotes) {
        if (payload == null || !wantsStandaloneBriefing(payload, arguments)) {
            return;
        }
        try {
            // Same note-dedupe window as refreshMemory: the briefing "claims" general_notes once.
            spirelink.util.NotesDedupe.setForce(forceNotes);
            try {
                payload.add("briefing", StateBuilder.freshBriefing());
            } finally {
                spirelink.util.NotesDedupe.setForce(false);
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("attachBriefing", t);
        }
    }

    /**
     * The decision half of {@link #attachBriefing} (issue #85), split out so it is unit-testable
     * without building a real briefing (which would read the human's notes files).
     *
     * @return true when the caller asked for {@code briefing} and the payload does not already
     *         carry it under {@code memory.briefing}
     */
    static boolean wantsStandaloneBriefing(JsonObject payload, JsonObject arguments) {
        if (payload == null || !wantsInclude(arguments, "briefing")) {
            return false;
        }
        if (payload.has("briefing")) {
            return false;
        }
        return !(payload.has("memory") && payload.get("memory").isJsonObject()
                && payload.getAsJsonObject("memory").has("briefing"));
    }

    /**
     * Applies {@code exclude:[...]} to a payload assembled from a cached snapshot (issue #84a).
     *
     * <p>The fresh path filters inside {@code StateBuilder}, but the cached body merged in on the
     * not-ready / error paths was filtered against an <em>older</em> request, so it could still carry
     * e.g. a {@code hints} block the caller had excluded - and the liveness decorator then made it
     * look deliberate. {@code meta} is never optional (issue #58b), everything else is.
     */
    static void dropExcluded(JsonObject payload, JsonArray exclude) {
        if (payload == null || exclude == null || exclude.size() == 0) {
            return;
        }
        for (JsonElement element : exclude) {
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
                continue;
            }
            String block = element.getAsString();
            if (block == null || block.isEmpty() || "meta".equals(block)) {
                continue;
            }
            payload.remove(block);
        }
    }

    /** Issue #123 · C14-03: see {@link #LOADING_READ_TIMEOUT_MS}. */
    static long readTimeoutMs() {
        return spirelink.state.LoadingWindow.active() ? LOADING_READ_TIMEOUT_MS : COMMAND_TIMEOUT_MS;
    }

    /** One card-list include: built on the main thread, added under {@code key}. */
    private void addCardList(JsonObject body, String key, final String kind) {
        MainThreadQueue.Result result = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                return kind == null ? spirelink.state.StateBuilder.buildDeckBlock()
                        : spirelink.state.StateBuilder.buildPileBlock(kind);
            }
        }, COMMAND_TIMEOUT_MS);
        if (result.status == MainThreadQueue.Result.Status.OK && result.value instanceof JsonObject) {
            body.add(key, (JsonObject) result.value);
        } else {
            log.warn("get_state include=" + key + " 失败: " + result.status);
        }
    }

    private ToolResult getState(JsonObject arguments) {
        // Issue #56: pump the in-flight guard on EVERY path, including the not-ready/exception ones.
        // The old placement (inside decorateLiveness, i.e. only after a successful main-thread call)
        // is why a wedged main thread kept the guard alive for 22 s.
        pumpInFlightGuard();
        try {
            // {force_notes:true} dumps every note again for debugging; normal reads dedupe per run.
            final boolean forceNotes = arguments != null && arguments.has("force_notes")
                    && !arguments.get("force_notes").isJsonNull()
                    && arguments.get("force_notes").getAsBoolean();
            // Real whitelist / blacklist: include=["combat"] answers meta+combat only.
            final JsonArray include = arrayArg(arguments, "include");
            final JsonArray exclude = arrayArg(arguments, "exclude");
            MainThreadQueue.Result result = queue.call(new MainThreadQueue.Task() {
                @Override
                public Object run() {
                    spirelink.util.NotesDedupe.setForce(forceNotes);
                    try {
                        return stateBuilder.viewWith(include, exclude);
                    } finally {
                        spirelink.util.NotesDedupe.setForce(false);
                    }
                }
            }, readTimeoutMs());

            if (result.status == MainThreadQueue.Result.Status.OK) {
                StateBuilder.Snapshot snapshot = (StateBuilder.Snapshot) result.value;
                if (snapshot.ready) {
                    AgentHud.push(spirelink.ui.HudPhrases.looked(snapshot.body));
                } else {
                    AgentHud.push(AgentHud.MSG_WAITING);
                }
                JsonObject body = payloadFor(snapshot);
                // Issue #120 · C11-03: a body whose screen offers nothing is not "ready".
                boolean blockedByActions = alignReady(body);
                attachIncludes(body, arguments);
                // Asking for the deck should be visible on screen: the human watching must see what
                // the agent is looking at. Read-only peek, never a decision point.
                if (wantsDeck(arguments)) {
                    spirelink.ui.DeckPeek.request(metaUi(body));
                }
                // Same idea for the piles: asking for one opens that pile's view, after the pointer
                // has walked over to its button.
                if (wantsInclude(arguments, "discard_pile")) {
                    spirelink.ui.DeckPeek.request(metaUi(body), spirelink.ui.DeckPeek.DISCARD);
                }
                if (wantsInclude(arguments, "exhaust_pile")) {
                    spirelink.ui.DeckPeek.request(metaUi(body), spirelink.ui.DeckPeek.EXHAUST);
                }
                if (wantsInclude(arguments, "draw_pile")) {
                    spirelink.ui.DeckPeek.request(metaUi(body), spirelink.ui.DeckPeek.DRAW);
                }
                decorateLiveness(body);
                refreshMemory(body, forceNotes);
                // Issue #85: include:["briefing"] on its own must actually answer something.
                attachBriefing(body, arguments, forceNotes);
                // Issue #84a: "exclude wins" on every path, including the blocks that were merged
                // in from a cached snapshot after the filter ran.
                dropExcluded(body, exclude);
                return new ToolResult(body, !snapshot.ready || blockedByActions);
            }
            if (result.status == MainThreadQueue.Result.Status.TIMEOUT
                    || result.status == MainThreadQueue.Result.Status.BUSY) {
                String message = result.status == MainThreadQueue.Result.Status.BUSY
                        ? "另一个请求正在处理，稍后重试 get_state。"
                        : readTimeoutMessage("get_state");
                log.info("get_state 未就绪: " + result.status);
                AgentHud.push(result.status == MainThreadQueue.Result.Status.BUSY
                        ? AgentHud.MSG_BUSY : AgentHud.MSG_NOT_READY);
                JsonObject notReady = notReadyPayload(stateBuilder.cachedView(), message);
                // The cached body may still carry a block the caller excluded (issue #84a).
                dropExcluded(notReady, exclude);
                return new ToolResult(notReady, true);
            }
            Throwable error = result.error;
            log.error("get_state 在主线程抛出异常", error);
            AgentHud.push(AgentHud.MSG_ERROR);
            JsonObject broken = internalErrorPayload(stateBuilder.cachedView(), error);
            dropExcluded(broken, exclude);
            return new ToolResult(broken, true);
        } catch (Throwable t) {
            log.error("get_state 调用失败", t);
            AgentHud.push(AgentHud.MSG_ERROR);
            return new ToolResult(internalErrorPayload(null, t), true);
        }
    }

    /**
     * Keeps {@code get_state.meta.ready} honest: a screen that offers no action is not ready, and
     * {@code legal_actions} already says so. Both answers must agree.
     *
     * <p>Issue #120 · C11-03: flipping {@code ready} to false without saying why produced the worst
     * answer in the protocol - "not ready, no error, nothing to do" - which reads as a dead end. The
     * explanation is the same sentence {@link #emptyActionsMessage} gives {@code legal_actions}, and
     * it is only added when the body has no error of its own. {@code ok} is flipped together with
     * {@code ready}: the not-ready shape has always been {@code ok:false + ready:false + error}, and
     * "ok:true but nothing is actionable" is the contradiction that made the empty frame look fine.</p>
     *
     * @return true when this call flipped the body to not-ready (the caller must then flag the result
     *         as an error, exactly like a snapshot that was never ready)
     */
    private boolean alignReady(JsonObject body) {
        try {
            if (body == null || !body.has("meta")) {
                return false;
            }
            JsonObject meta = body.getAsJsonObject("meta");
            if (meta == null || !meta.has("ready") || !meta.get("ready").getAsBoolean()) {
                return false;
            }
            String ui = meta.has("ui") && !meta.get("ui").isJsonNull()
                    ? meta.get("ui").getAsString() : null;
            if (ui == null) {
                return false;
            }
            if (ActionPlanner.actionsFor(ui).isEmpty()) {
                // Both the nested meta.ready and the top-level ready must agree with legal_actions.
                meta.addProperty("ready", false);
                meta.addProperty("blocked_ui", ui);
                // Issue #121 · C12-04: same address as legal_actions (top level), not only meta.*.
                body.addProperty("blocked_ui", ui);
                body.addProperty("ready", false);
                body.addProperty("ok", false);
                if (!meta.has("awaiting") || meta.get("awaiting").isJsonNull()
                        || "playing".equals(meta.get("awaiting").getAsString())) {
                    meta.addProperty("awaiting", "screen_up");
                }
                if (!body.has("error") || body.get("error").isJsonNull()) {
                    body.add("error", error("NOT_READY", emptyActionsMessage(ui, true),
                            meta.has("state_version") && !meta.get("state_version").isJsonNull()
                                    ? meta.get("state_version").getAsInt() : 0));
                }
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            // never break a state read for a consistency tweak
            return false;
        }
    }

    /**
     * True when the planner still advertises the action type we just failed to execute — the sure
     * sign that planner and executor disagree.
     */
    /**
     * Issue #108 · C7-05: is a rejection evidence that the planner and the executor disagree?
     *
     * <p>Only three things together make it one: the executor actually rejected, the type is still
     * advertised, <b>and</b> the rejection is not about the payload. A parameter-level rejection ({@link ActionResult#paramLevel} — index
     * or uuid out of range, a card/option that is not among the offered ones, a missing target) means
     * the caller sent something the advertisement never contained; the planner was right. Before this
     * guard every mistyped payload printed {@code planner/executor 不一致} and buried the real ones.</p>
     *
     * @param stillOffered whether the same tick still advertises {@code type}
     * @param result       what the executor answered, or {@code null}
     */
    static boolean plannerExecutorMismatch(boolean stillOffered, ActionResult result) {
        return stillOffered && result != null && !result.ok && !result.paramLevel;
    }

    private boolean stillOffered(String type) {
        try {
            for (ActionSpec spec : ActionPlanner.actionsFor(uiOf(stateBuilder.view()))) {
                if (spec.type.equals(type)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // diagnostics only
        }
        return false;
    }

    /** Dev tools exist only in test mode; a method so a unit test can lock that down. */
    public static boolean devToolsEnabled() {
        return spirelink.util.TestMode.enabled();
    }

    /** Names of the dev-only tools; empty in production. */
    public static List<String> devToolNames() {
        if (!devToolsEnabled()) {
            return java.util.Collections.emptyList();
        }
        return java.util.Arrays.asList(TOOL_DEV_EVENT, TOOL_DEV_MAP, TOOL_DEV_ROOM,
                TOOL_DEV_ENCOUNTER, TOOL_DEV_POTION, TOOL_DEV_CARD, TOOL_DEV_RELIC,
                TOOL_DEV_REWARD, TOOL_DEV_HEAL, TOOL_DEV_USE_CARD);
    }

    private JsonObject devRelicDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        // Issue #119 · C10-04: keys are not relics, but this is where QA looks for them.
        id.addProperty("description", "遗物类短名，如 BurningBlood；也认钥匙 id："
                + KeyRewards.acceptedIds() + "（造出真宝箱形态：同屏遗物行 + 钥匙行）");
        props.add("id", id);
        schema.add("properties", props);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_DEV_RELIC);
        tool.addProperty("description", "【测试模式专用】直接获得一个遗物；"
                + "传钥匙 id（RubyKey/EmeraldKey/SapphireKey）则造出钥匙宝箱并打开奖励屏");
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject devRewardDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        // The enum comes from DevRewardTool.KINDS, the same whitelist the tool itself validates
        // against - a hand-kept copy is exactly how issue #60 drifted.
        props.add("kind", enumProp("奖励类型（五选一，默认 card）", DevRewardTool.KINDS));
        schema.add("properties", props);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_DEV_REWARD);
        tool.addProperty("description", "【测试模式专用】造一个奖励并打开奖励界面");
        tool.add("inputSchema", schema);
        return tool;
    }

    private ToolResult devRelic(JsonObject arguments) {
        final String id = strArg(arguments, "id");
        return devCall(new DevCall() {
            @Override
            public JsonObject run() {
                return DevRelicTool.add(id);
            }
        });
    }

    private ToolResult devReward(JsonObject arguments) {
        final String kind = strArg(arguments, "kind");
        return devCall(new DevCall() {
            @Override
            public JsonObject run() {
                return DevRewardTool.add(kind);
            }
        });
    }

    private JsonObject devRoomDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("type", enumProp("房间类型（八选一）", DEV_ROOM_TYPES));
        schema.add("properties", props);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_DEV_ROOM);
        tool.addProperty("description", "【测试模式专用】直接进入指定房间");
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject devHealDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_DEV_HEAL);
        tool.addProperty("description", "【测试模式专用】回满血并清除 debuff（战斗中也有效）");
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject devUseCardDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        id.addProperty("description", "卡牌 ID 三种写法都行：类名 AThousandCuts / 游戏 ID \"A Thousand Cuts\" / 全限定名");
        props.add("id", id);
        JsonObject targetIndex = new JsonObject();
        targetIndex.addProperty("type", "integer");
        targetIndex.addProperty("description", "可选的怪物下标；不给则取第一个活怪");
        props.add("target_index", targetIndex);
        props.add("force", forceProp("true = 强制构造（跳过可获得性检查）；反射回退默认就启用，force 只作显式声明并原样回显"));
        schema.add("properties", props);
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("id");
        schema.add("required", required);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_DEV_USE_CARD);
        tool.addProperty("description",
                "【测试模式专用】构造一张牌并真的把它打出去：走原版出牌路径"
                        + "（AbstractPlayer.useCard：算伤害 → card.use() → 排 UseCardAction → 能量/手牌收尾），"
                        + "所以效果是真的 —— 格挡/力量/敌人掉血都会发生，副作用在下一拍生效。"
                        + "两个 dev 口径：不消耗能量（freeToPlayOnce）、卡牌是临时复制品打完即消失"
                        + "（purgeOnUse，不进弃牌堆）。必须在战斗中，否则回 NOT_READY（不会假装打出）。"
                        + "卡牌 ID 三种写法都行：类名 AThousandCuts / 游戏 ID 'A Thousand Cuts' / 全限定名。"
                        + "result 三态：played（use() 正常返回）/"
                        + "blocked_by_game_rule（cost=-2 规则不可打出，诅咒/状态牌，仍然打了）/"
                        + "metadata_only（use() 抛异常，工具上下文限制）；"
                        + "effect 三态：queued（排出的动作已进游戏动作队列）/ none（这张牌没有排出动作）/ "
                        + "dropped（use() 抛异常，半路排出的动作已清空），queued_actions 是实测排队数；"
                        + "reason 说明原因，卡牌若弹出选牌界面会返回 needs_screen");
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject devEncounterDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject list = new JsonObject();
        list.addProperty("type", "boolean");
        list.addProperty("description", "true 时只列出全部遭遇 id（含 act 与显示名），不进场");
        props.add("list", list);
        JsonObject ids = new JsonObject();
        ids.addProperty("type", "array");
        ids.addProperty("description", "遭遇 id 列表（MonsterHelper 的 *_ENC 值，如 Jaw Worm），按顺序取第一个能建出来的；"
                + "一次只开一个遭遇（C7-12）：多给的 id 不会一起上场，回包 ignored_ids / unknown_ids / notice 会说明，"
                + "要打第二个请再单独调用一次");
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        ids.add("items", item);
        props.add("ids", ids);
        JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        id.addProperty("description", "单个遭遇 id 的简写，等价于 ids 只有一个元素");
        props.add("id", id);
        JsonObject slot = new JsonObject();
        slot.addProperty("type", "integer");
        slot.addProperty("description", "保留字段（当前未使用）");
        props.add("act", slot);
        schema.add("properties", props);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_DEV_ENCOUNTER);
        tool.addProperty("description",
                "【测试模式专用】指定遭遇战：list:true 列出全部遭遇；ids:[…] 直接开战"
                        + "（ids 是候选表，一次只开第一个能用的 id，其余在 ignored_ids/notice 里回报）");
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject devPotionDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        id.addProperty("description",
                "药水名，三种写法都行：FirePotion（类名）/ Fire Potion（ID）/ 中文名");
        props.add("id", id);
        JsonObject slot = new JsonObject();
        slot.addProperty("type", "integer");
        slot.addProperty("description", "指定槽位（0..2），覆盖原有药水");
        props.add("slot", slot);
        JsonObject list = new JsonObject();
        list.addProperty("type", "boolean");
        list.addProperty("description", "true 时列出全部药水 id/类名/本地化名");
        props.add("list", list);
        schema.add("properties", props);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_DEV_POTION);
        tool.addProperty("description", "【测试模式专用】把指定药水放进空槽；{list:true} 列出全部药水");
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject devCardDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        id.addProperty("description", "卡牌 ID 三种写法都行：类名 AThousandCuts / 游戏 ID \"A Thousand Cuts\" / 全限定名");
        props.add("id", id);
        props.add("to", enumProp("放到哪里（默认 hand）",
                java.util.Arrays.asList("hand", "deck")));
        props.add("force", forceProp("true = 强制加入：跳过手牌上限（10 张）与可获得性检查，响应原样回显"));
        schema.add("properties", props);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_DEV_CARD);
        tool.addProperty("description",
                "【测试模式专用】把指定卡牌塞进手牌或牌组。卡牌 ID 三种写法都行：类名 AThousandCuts / "
                        + "游戏 ID 'A Thousand Cuts' / 全限定名；force=true 跳过手牌上限与可获得性检查");
        tool.add("inputSchema", schema);
        return tool;
    }

    /** Runs a dev tool body on the main thread and normalises the result. */
    private ToolResult devCall(final DevCall call) {
        if (!devToolsEnabled()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", false);
            payload.addProperty("error", "DEV_TOOL_DISABLED");
            return new ToolResult(payload, true);
        }
        MainThreadQueue.Result result = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                return call.run();
            }
        }, COMMAND_TIMEOUT_MS);
        if (result.status != MainThreadQueue.Result.Status.OK
                || !(result.value instanceof JsonObject)) {
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", false);
            payload.addProperty("error", "NOT_READY");
            return new ToolResult(payload, true);
        }
        JsonObject payload = (JsonObject) result.value;
        boolean ok = payload.has("ok") && payload.get("ok").getAsBoolean();
        return new ToolResult(payload, !ok);
    }

    private interface DevCall {
        JsonObject run();
    }

    private ToolResult devRoom(JsonObject arguments) {
        final String type = strArg(arguments, "type");
        return devCall(new DevCall() {
            @Override
            public JsonObject run() {
                return DevRoomTool.enter(type);
            }
        });
    }

    private ToolResult devHeal(JsonObject arguments) {
        return devCall(new DevCall() {
            @Override
            public JsonObject run() {
                return DevHealTool.heal();
            }
        });
    }

    private ToolResult devUseCard(JsonObject arguments) {
        final String id = strArg(arguments, "id");
        final int targetIndex = arguments != null && arguments.has("target_index")
                && !arguments.get("target_index").isJsonNull()
                ? arguments.get("target_index").getAsInt() : -1;
        final boolean force = boolArg(arguments, "force");
        return devCall(new DevCall() {
            @Override
            public JsonObject run() {
                return DevUseCardTool.use(id, targetIndex, force);
            }
        });
    }

    private ToolResult devEncounter(JsonObject arguments) {
        final boolean list = arguments != null && arguments.has("list")
                && !arguments.get("list").isJsonNull() && arguments.get("list").getAsBoolean();
        final java.util.List<String> ids = new java.util.ArrayList<String>();
        if (arguments != null && arguments.has("ids") && arguments.get("ids").isJsonArray()) {
            for (com.google.gson.JsonElement element : arguments.getAsJsonArray("ids")) {
                if (element != null && !element.isJsonNull()) {
                    ids.add(element.getAsString());
                }
            }
        }
        final String single = strArg(arguments, "id");
        if (single != null && !single.isEmpty()) {
            ids.add(single);
        }
        return devCall(new DevCall() {
            @Override
            public JsonObject run() {
                return list ? DevEncounterTool.list() : DevEncounterTool.enter(ids);
            }
        });
    }

    private ToolResult devPotion(JsonObject arguments) {
        if (arguments != null && arguments.has("list") && !arguments.get("list").isJsonNull()
                && arguments.get("list").getAsBoolean()) {
            return devCall(new DevCall() {
                @Override
                public JsonObject run() {
                    return DevPotionTool.list();
                }
            });
        }
        final String id = strArg(arguments, "id");
        final int slot = arguments != null && arguments.has("slot") && !arguments.get("slot").isJsonNull()
                ? arguments.get("slot").getAsInt() : -1;
        return devCall(new DevCall() {
            @Override
            public JsonObject run() {
                return DevPotionTool.add(id, slot);
            }
        });
    }

    private ToolResult devCard(JsonObject arguments) {
        final String id = strArg(arguments, "id");
        final String to = strArg(arguments, "to");
        final boolean force = boolArg(arguments, "force");
        return devCall(new DevCall() {
            @Override
            public JsonObject run() {
                return DevCardTool.add(id, to, force);
            }
        });
    }

    private JsonObject devMapDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_DEV_MAP);
        tool.addProperty("description", "【测试模式专用】导出地图前 3 行的节点/边/父边，用于诊断「没有可达节点」");
        tool.add("inputSchema", schema);
        return tool;
    }

    /** {@code dev_map}: raw map graph, so a missing-path bug can be pinned on real data. */
    private ToolResult devMap() {
        if (!devToolsEnabled()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", false);
            payload.addProperty("error", "DEV_TOOL_DISABLED");
            return new ToolResult(payload, true);
        }
        MainThreadQueue.Result result = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                return DevMapTool.dump();
            }
        }, COMMAND_TIMEOUT_MS);
        if (result.status != MainThreadQueue.Result.Status.OK
                || !(result.value instanceof JsonObject)) {
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", false);
            payload.addProperty("error", "NOT_READY");
            return new ToolResult(payload, true);
        }
        JsonObject payload = (JsonObject) result.value;
        boolean ok = payload.has("ok") && payload.get("ok").getAsBoolean();
        return new ToolResult(payload, !ok);
    }

    private JsonObject devEventDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject id = new JsonObject();
        id.addProperty("type", "string");
        id.addProperty("description", "事件短名（BigFish）或全类名");
        props.add("id", id);
        JsonObject act = new JsonObject();
        act.addProperty("type", "integer");
        // Issue #117: the act only labels the call now. dev_event replaces the room and never writes
        // actNum / floorNum / the map, so a cross-act jump can no longer leave a run describing a
        // dungeon it is not in. Leaving it out means "whatever act the run is in".
        act.addProperty("description", "幕数（可选，只做标注）：dev_event 只换房间，"
                + "不改 act/floor/地图推进状态；不传 = 跟随当前幕");
        props.add("act", act);
        // Issue #80: tools.md promised dev_event{force:true}; DevEventTool.enter(id, act, force)
        // honours it (skips the busy check and clears the action queue first), so the schema says so.
        props.add("force", forceProp("true = 跳过忙检查并先清空动作队列（从卡死自救）；"
                + "默认 false，正常进事件不需要它"));
        schema.add("properties", props);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_DEV_EVENT);
        tool.addProperty("description", "【测试模式专用】直接进入指定事件，用于覆盖全部事件界面；"
                + "force=true 时先清空动作队列，用于从卡死状态自救");
        tool.add("inputSchema", schema);
        return tool;
    }

    /** {@code dev_event}: test-mode only shortcut into an arbitrary event room. */
    private ToolResult devEvent(JsonObject arguments) {
        final String id = strArg(arguments, "id");
        final Integer act = intArg(arguments, "act");
        final Boolean force = arguments != null && arguments.has("force")
                && !arguments.get("force").isJsonNull()
                ? Boolean.valueOf(arguments.get("force").getAsBoolean()) : Boolean.FALSE;
        if (!devToolsEnabled()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", false);
            payload.addProperty("error", "DEV_TOOL_DISABLED");
            return new ToolResult(payload, true);
        }
        MainThreadQueue.Result result = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                // Issue #117: no default act any more - an omitted act used to become 1 and that
                // default is exactly what rewrote meta.act on the act-2 map.
                return DevEventTool.enter(id, act == null ? DevEventTool.ACT_UNSPECIFIED
                        : act.intValue(),
                        Boolean.TRUE.equals(force));
            }
        }, COMMAND_TIMEOUT_MS);
        if (result.status != MainThreadQueue.Result.Status.OK
                || !(result.value instanceof JsonObject)) {
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", false);
            payload.addProperty("error", "NOT_READY");
            return new ToolResult(payload, true);
        }
        JsonObject payload = (JsonObject) result.value;
        boolean ok = payload.has("ok") && payload.get("ok").getAsBoolean();
        return new ToolResult(payload, !ok);
    }

    private JsonObject noteSetDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("kind", enumProp("便签种类（六选一）", NOTE_KINDS));
        props.add("id", stringProp("该对象的稳定 id（事件=类名，卡牌=cardID，药水=类名，敌人=monster.id，遗物=relicId）"));
        props.add("text", stringProp("便签内容（必填）：写内容就传字符串；"
                + "要删除这条便签请显式传 text:\"\"。漏传 / 传 null → BAD_REQUEST，不会当作删除"));
        schema.add("properties", props);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_NOTE_SET);
        tool.addProperty("description",
                "给某个对象写便签，下次遇到同一对象时会自动带出。text 必填；"
                        + "只有显式传 text:\"\" 才会删除（issue #59）");
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject noteGetDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("kind", enumProp("便签种类（六选一）", NOTE_KINDS));
        props.add("id", stringProp("对象 id"));
        schema.add("properties", props);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_NOTE_GET);
        tool.addProperty("description", "读某个对象的便签");
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject notesListDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("kind", enumProp("可选，只列某一类（不给 = 全部）", NOTE_KINDS));
        // The implementation has always understood sort; the schema silently dropped it, so a
        // client reading inputSchema never learned the field existed (issue #54).
        JsonObject sort = new JsonObject();
        sort.addProperty("type", "string");
        JsonArray sortEnum = new JsonArray();
        sortEnum.add(NOTES_SORT_RECENT);
        sortEnum.add(NOTES_SORT_NAME);
        sort.add("enum", sortEnum);
        sort.addProperty("default", NOTES_SORT_RECENT);
        sort.addProperty("description",
                "排序：recent（默认，按 updated_at 降序，新的在前）/ "
                        + "name（按 kind:id 字典序）；回包的 sort 字段回显实际用的那种。"
                        + "旧写法 id 等价于 name。");
        props.add("sort", sort);
        schema.add("properties", props);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_NOTES_LIST);
        tool.addProperty("description",
                "列出所有便签（可按 kind 过滤、按 sort 排序），返回 count 与 items[]");
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject eventNoteDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        props.add("text", stringProp("便签内容；空字符串表示删除"));
        schema.add("properties", props);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_EVENT_NOTE);
        tool.addProperty("description", "给当前事件写便签（等价 note_set{kind:event}）");
        tool.add("inputSchema", schema);
        return tool;
    }

    /**
     * Argument contract of {@code note_set} / {@code event_note} (issue #59).
     *
     * <p>{@code text} used to be optional: a caller that forgot the field got {@code ok:true} and
     * <b>deleted</b> the note, because {@code Notes.set(kind, id, null)} treats null as "blank" and
     * blank means delete. The documented rule is "explicit empty string deletes, nothing else does",
     * so a missing / null / non-string {@code text} is now a BAD_REQUEST that says how to delete.
     *
     * @return the BAD_REQUEST message, or {@code null} when the arguments are acceptable
     */
    static String noteSetArgumentError(JsonObject arguments, String toolName) {
        String name = toolName == null ? TOOL_NOTE_SET : toolName;
        if (arguments == null) {
            return name + " 需要 kind（" + NOTE_KINDS + "）与 id";
        }
        String kind = strArg(arguments, "kind");
        String id = strArg(arguments, "id");
        if (kind == null || !NOTE_KINDS.contains(kind) || id == null || id.isEmpty()) {
            return name + " 需要 kind（" + NOTE_KINDS + "）与 id";
        }
        return noteTextArgumentError(arguments, name);
    }

    /** The {@code text} half of the note contract: required, a string, "" = delete (issue #59). */
    static String noteTextArgumentError(JsonObject arguments, String toolName) {
        String name = toolName == null ? TOOL_NOTE_SET : toolName;
        if (arguments == null || !arguments.has("text") || arguments.get("text").isJsonNull()) {
            return name + " 必须带 text：写内容就传字符串；要删除这条便签请显式传 text:\"\"。"
                    + "漏传 text 不会被当成删除（以前会静默删掉，issue #59）";
        }
        JsonElement text = arguments.get("text");
        if (!text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()) {
            return name + ".text 必须是字符串，收到 " + text + "；要删除请显式传 text:\"\"";
        }
        return null;
    }

    private ToolResult noteSet(JsonObject arguments) {
        String kind = strArg(arguments, "kind");
        String id = strArg(arguments, "id");
        String text = strArg(arguments, "text");
        JsonObject payload = new JsonObject();
        try {
            String badArgument = noteSetArgumentError(arguments, TOOL_NOTE_SET);
            if (badArgument != null) {
                payload.addProperty("ok", false);
                payload.addProperty("error", "BAD_REQUEST");
                payload.addProperty("message", badArgument);
                return new ToolResult(payload, true);
            }
            String stored = spirelink.util.Notes.set(kind, id, text);
            // A: show the write on the HUD at once, so a human can see the agent is taking notes.
            if (stored != null) {
                spirelink.ui.NoteHud.wrote(kind, id, stored);
            }
            // Notes are not part of the game state, so the cached snapshot must be invalidated by
            // hand or get_state keeps serving the snapshot from before the write.
            stateBuilder.invalidate();
            payload.addProperty("ok", true);
            payload.addProperty("key", spirelink.util.Notes.key(kind, id));
            payload.add("text", stored == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(stored));
            return new ToolResult(payload, false);
        } catch (Throwable t) {
            payload.addProperty("ok", false);
            payload.addProperty("error", "NOTE_WRITE_FAILED");
            payload.addProperty("message", String.valueOf(t.getMessage()));
            return new ToolResult(payload, true);
        }
    }

    /** Kinds the notes store accepts; anything else is a caller mistake, not an empty note. */
    private static final java.util.List<String> NOTE_KINDS = java.util.Arrays.asList(
            "event", "potion", "card", "monster", "relic", "general");

    /** Rooms {@code dev_room} can force (issue #81); the implementation also accepts aliases. */
    private static final java.util.List<String> DEV_ROOM_TYPES = java.util.Arrays.asList(
            "monster", "elite", "boss", "rest", "shop", "treasure", "event", "victory");

    private ToolResult noteGet(JsonObject arguments) {
        String kind = strArg(arguments, "kind");
        String id = strArg(arguments, "id");
        if (kind == null || !NOTE_KINDS.contains(kind) || id == null || id.isEmpty()) {
            JsonObject bad = new JsonObject();
            bad.addProperty("ok", false);
            bad.addProperty("error", "BAD_REQUEST");
            bad.addProperty("message", "note_get 需要 kind（" + NOTE_KINDS + "）和 id");
            return new ToolResult(bad, true);
        }
        JsonObject payload = new JsonObject();
        String note = spirelink.util.Notes.get(kind, id);
        // B: mirror the read on the HUD as well.
        if (note != null && !note.trim().isEmpty()) {
            spirelink.ui.NoteHud.read(kind, id, note);
        }
        payload.addProperty("ok", true);
        payload.addProperty("key", spirelink.util.Notes.key(kind, id));
        payload.add("text", note == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(note));
        return new ToolResult(payload, false);
    }

    private static long updatedAtOf(com.google.gson.JsonElement element) {
        try {
            com.google.gson.JsonObject object = element.getAsJsonObject();
            return object.has("updated_at") ? object.get("updated_at").getAsLong() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Canonical {@code notes_list.sort}: {@code recent} (newest first, the default) or {@code name}
     * (key order). {@code id} is the pre-#54 spelling of the key order and stays an alias; an
     * unknown value falls back to the default instead of failing, which is what the tool always did.
     */
    static String normalizeNotesSort(String raw) {
        if (NOTES_SORT_NAME.equals(raw) || NOTES_SORT_LEGACY_ID.equals(raw)) {
            return NOTES_SORT_NAME;
        }
        return NOTES_SORT_RECENT;
    }

    /**
     * The {@code notes_list} answer for an already-loaded note map. Pure on purpose: the order of
     * {@code items[]} and the echoed {@code sort} are the contract, so they are unit-testable
     * without touching the human's {@code notes.json}.
     */
    static JsonObject notesListPayload(com.google.gson.JsonObject notes, String rawSort) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        com.google.gson.JsonObject source = notes == null ? new JsonObject() : notes;
        payload.add("notes", source);
        // M4: explicit, sorted, count-prefixed view so an agent can review its notes deliberately.
        String sort = normalizeNotesSort(rawSort);
        java.util.List<java.util.Map.Entry<String, com.google.gson.JsonElement>> ordered =
                new java.util.ArrayList<>(source.entrySet());
        if (NOTES_SORT_NAME.equals(sort)) {
            ordered.sort(java.util.Map.Entry.comparingByKey());
        } else {
            ordered.sort((a, b) -> Long.compare(updatedAtOf(b.getValue()), updatedAtOf(a.getValue())));
        }
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : ordered) {
            String key = entry.getKey();
            int split = key.indexOf(':');
            com.google.gson.JsonObject item = new com.google.gson.JsonObject();
            item.addProperty("key", key);
            item.addProperty("kind", split > 0 ? key.substring(0, split) : "general");
            item.addProperty("id", split > 0 ? key.substring(split + 1) : key);
            item.addProperty("text", entry.getValue().getAsJsonObject().get("text").getAsString());
            item.addProperty("updated_at", updatedAtOf(entry.getValue()));
            items.add(item);
        }
        payload.addProperty("count", items.size());
        payload.addProperty("sort", sort);
        payload.add("items", items);
        return payload;
    }

    private ToolResult notesList(JsonObject arguments) {
        com.google.gson.JsonObject notes = spirelink.util.Notes.list(strArg(arguments, "kind"));
        JsonObject payload = notesListPayload(notes, strArg(arguments, "sort"));
        // B for the list form: count plus the newest entry, so the HUD never shows a wall of text.
        if (notes != null && !notes.entrySet().isEmpty()) {
            String newestKey = null;
            long newestAt = -1L;
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry
                    : notes.entrySet()) {
                com.google.gson.JsonObject value = entry.getValue().getAsJsonObject();
                long updated = value.has("updated_at") ? value.get("updated_at").getAsLong() : 0L;
                if (updated >= newestAt) {
                    newestAt = updated;
                    newestKey = entry.getKey();
                }
            }
            String line = null;
            if (newestKey != null) {
                int split = newestKey.indexOf(':');
                String kind = split > 0 ? newestKey.substring(0, split) : "general";
                String id = split > 0 ? newestKey.substring(split + 1) : newestKey;
                String text = notes.getAsJsonObject(newestKey).get("text").getAsString();
                line = spirelink.ui.NoteHud.label(kind, id, text);
            }
            spirelink.ui.NoteHud.readList(notes.entrySet().size(), line);
        }
        return new ToolResult(payload, false);
    }

    /** event_note: convenience alias for note_set on the event currently on screen. */
    private ToolResult eventNote(JsonObject arguments) {
        String text = strArg(arguments, "text");
        JsonObject payload = new JsonObject();
        String id = spirelink.state.EventPayload.currentEventId();
        if (id == null) {
            payload.addProperty("ok", false);
            payload.addProperty("error", "NOT_READY");
            payload.addProperty("message", "当前不在事件房");
            return new ToolResult(payload, true);
        }
        // Same data-loss rule as note_set (issue #59), checked AFTER the "not in an event room"
        // answer so the documented NOT_READY path stays first.
        String badArgument = noteTextArgumentError(arguments, TOOL_EVENT_NOTE);
        if (badArgument != null) {
            payload.addProperty("ok", false);
            payload.addProperty("error", "BAD_REQUEST");
            payload.addProperty("message", badArgument);
            return new ToolResult(payload, true);
        }
        try {
            String stored = spirelink.util.Notes.set("event", id, text);
            if (stored != null) {
                spirelink.ui.NoteHud.wrote("event", id, stored);
            }
            stateBuilder.invalidate();
            payload.addProperty("ok", true);
            payload.addProperty("id", id);
            payload.add("note", stored == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(stored));
            return new ToolResult(payload, false);
        } catch (Throwable t) {
            payload.addProperty("ok", false);
            payload.addProperty("error", "NOTE_WRITE_FAILED");
            return new ToolResult(payload, true);
        }
    }

    private JsonObject cardInfoDefinition() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject uuid = new JsonObject();
        uuid.addProperty("type", "string");
        uuid.addProperty("description", "要了解的牌 uuid（如 get_state.combat.hand[].uuid）");
        props.add("uuid", uuid);
        JsonObject upgraded = new JsonObject();
        upgraded.addProperty("type", "boolean");
        upgraded.addProperty("description", "true 时按「升级后」的牌面展示（游戏里弹出大图，含升级后的数值）；"
                + "已经升级过的牌就是这个样子");
        props.add("upgraded", upgraded);
        schema.add("properties", props);
        // Issue #81: the implementation refuses a card_info without uuid (BAD_REQUEST), so the
        // schema has to say it is required instead of leaving it to a runtime surprise.
        JsonArray required = new JsonArray();
        required.add("uuid");
        schema.add("required", required);
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_CARD_INFO);
        tool.addProperty("description", "查一张牌的牌面与效果；游戏里会自动把这张牌展示给玩家。"
                + "加 upgraded:true 看升级后的版本");
        tool.add("inputSchema", schema);
        return tool;
    }

    /** {@code card_info}: reading a card also shows it to the human (gaze is a side effect). */
    private ToolResult cardInfo(JsonObject arguments) {
        String uuid = strArg(arguments, "uuid");
        if (uuid == null || uuid.isEmpty()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", false);
            payload.addProperty("error", "BAD_REQUEST");
            payload.addProperty("message", "card_info 必须带 uuid");
            return new ToolResult(payload, true);
        }
        final String target = uuid;
        final boolean upgraded = arguments.has("upgraded") && !arguments.get("upgraded").isJsonNull()
                && arguments.get("upgraded").getAsBoolean();
        // Same rule as every action: a look is a mouse move too. The gaze lifts the card on its own;
        // the fish walks over to it while that happens.
        lookAtCard(target);
        MainThreadQueue.Result result = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                com.megacrit.cardcrawl.cards.AbstractCard asked =
                        spirelink.inspect.Inspector.find(target);
                // Same reasoning as get_state{include:deck}: show the deck while the card is examined
                // - but only when the card is NOT in hand: the deck view covers the hand, and then
                // the lift animation we just asked for happens behind a screen nobody can see.
                boolean inHand = asked != null
                        && com.megacrit.cardcrawl.dungeons.AbstractDungeon.player != null
                        && com.megacrit.cardcrawl.dungeons.AbstractDungeon.player.hand != null
                        && com.megacrit.cardcrawl.dungeons.AbstractDungeon.player.hand.group
                                .contains(asked);
                if (!inHand) {
                    spirelink.ui.DeckPeek.request(stateBuilder.currentUiOnMainThread());
                }
                if (upgraded) {
                    // "What would this become?" is a card view, not a hover: show the upgraded copy
                    // in the game's own big popup, the same thing a human sees when they flip it.
                    com.megacrit.cardcrawl.cards.AbstractCard card =
                            spirelink.inspect.Inspector.find(target);
                    if (card != null) {
                        com.megacrit.cardcrawl.cards.AbstractCard shown = card;
                        try {
                            if (!card.upgraded && card.canUpgrade()) {
                                shown = card.makeCopy();
                                shown.upgrade();
                            }
                        } catch (Throwable ignored) {
                            shown = card;
                        }
                        spirelink.ui.DeckPeek.showCard(shown, 3200L);
                    }
                }
                return spirelink.inspect.Inspector.request(target,
                        spirelink.inspect.Inspector.delayMs());
            }
        }, COMMAND_TIMEOUT_MS);
        if (result.status != MainThreadQueue.Result.Status.OK
                || !(result.value instanceof JsonObject)) {
            JsonObject payload = new JsonObject();
            payload.addProperty("ok", false);
            payload.addProperty("error", "NOT_READY");
            return new ToolResult(payload, true);
        }
        JsonObject payload = (JsonObject) result.value;
        // Same note the state carries, so the agent does not have to look it up separately.
        if (payload.has("id") && !payload.get("id").isJsonNull()) {
            String note = spirelink.util.Notes.get("card", payload.get("id").getAsString());
            payload.add("note", note == null
                    ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(note));
        }
        boolean ok = payload.has("ok") && payload.get("ok").getAsBoolean();
        return new ToolResult(payload, !ok);
    }

    /**
     * Rewrites the live numbers on every response, straight from the mod's own counters. A cached
     * snapshot kept its old `hints.frames`, which looked like "the game froze" while the loop was
     * perfectly alive — never serve stale numbers for these. Same rule for the last-action
     * diagnostics (issue #88): `act_seq` / `last_error` / `last_error_seq` /
     * `last_error_age_ms` used to be frozen at whatever the cached body was built with.
     */
    private void decorateLiveness(JsonObject payload) {
        pumpInFlightGuard();
        mergeLiveness(payload);
        refreshDiagnostics(payload);
    }

    /**
     * Merges the live counters into the {@code hints} block <b>only when that block already exists</b>.
     *
     * <p>Issue #66/#68: this method used to invent a hints object whenever the payload had none, so
     * every {@code ready:true} answer carried a four-field {@code hints}
     * ({@code frames/beats/ms_since_frame/ms_since_beat}) that {@code exclude:["hints"]} could not
     * even delete - and "hints present" stopped meaning "not ready" or "last act was refused".
     * Hints are the agent-facing explanation of a problem, so they are created by whoever has
     * something to explain, never by the liveness decorator.
     *
     * <p>Static and side-effect free on purpose: the merge contract is what the unit test pins.
     */
    static void mergeLiveness(JsonObject payload) {
        if (payload == null) {
            return;
        }
        try {
            if (!payload.has("hints") || payload.get("hints").isJsonNull()
                    || !payload.get("hints").isJsonObject()) {
                // No hints block: nothing to decorate, and we must not create one (issue #66/#68).
                return;
            }
            JsonObject hints = payload.getAsJsonObject("hints");
            long frames = spirelink.util.RuntimeProbe.frames();
            long beats = spirelink.util.RuntimeProbe.beats();
            long sinceFrame = spirelink.util.RuntimeProbe.msSinceFrame();
            long sinceBeat = spirelink.util.RuntimeProbe.msSinceBeat();
            hints.addProperty("frames", frames);
            hints.addProperty("beats", beats);
            hints.addProperty("ms_since_frame", sinceFrame);
            hints.addProperty("ms_since_beat", sinceBeat);
            // 3 s of no frame with requests still being answered = the loop is stuck, not just slow.
            if (sinceFrame > 3000L) {
                hints.addProperty("frames_stalled", true);
            }
            if (sinceBeat > 3000L) {
                hints.addProperty("thread_stalled", true);
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("decorateLiveness", t);
        }
    }

    /**
     * Issue #88: re-reads the "what did the last action do" diagnostics at serve time.
     *
     * <p>{@code hints.last_error} / {@code act_seq} / {@code last_error_seq} /
     * {@code last_error_age_ms} were written when the cached body was built, so after the first
     * refusal every later {@code get_state} kept reporting that same first error: the QA run saw
     * {@code "act → BAD_REQUEST"} frozen with {@code last_error_age_ms:270} while it was sending
     * {@code state_version-1} and getting {@code STALE_VERSION}. The counters live in
     * {@link spirelink.util.CrashGuard} and are always current, so they are re-read here instead of
     * being served from the cache — the same rule the liveness numbers follow (issue #84b).
     *
     * <p>Only merges into an existing {@code hints} object: the block itself stays a signal ("not
     * ready, or the last act was refused"), never something this decorator invents (#66/#68).
     */
    static void refreshDiagnostics(JsonObject payload) {
        mergeDiagnostics(payload, spirelink.util.CrashGuard.actionSeq(),
                spirelink.util.CrashGuard.lastErrorSeq(), spirelink.util.CrashGuard.lastError(),
                spirelink.util.CrashGuard.lastErrorAt(), System.currentTimeMillis(),
                spirelink.state.StateBuilder.lastDecisionChangeAt());
    }

    /**
     * The pure half of {@link #refreshDiagnostics}: every value is passed in, so the merge contract
     * and the age arithmetic can be pinned without a game, a clock or a real refusal.
     *
     * @param actSeq      calls to {@code act} so far (hints.act_seq)
     * @param errorSeq    the {@code act} that produced {@code lastError} (hints.last_error_seq)
     * @param lastError   last recorded refusal / internal error, or null when nothing ever failed
     * @param lastErrorAt wall clock of {@code lastError}; only meaningful when it is non-null
     * @param nowMs       serve-time wall clock, so "how long ago" is answered by the caller
     * @param worldMovedAtMs wall clock of the last real decision change
     *                    ({@link spirelink.state.StateBuilder#lastDecisionChangeAt()}), used by
     *                    {@code last_error_fresh} (issue #119 · C10-05)
     */
    static void mergeDiagnostics(JsonObject payload, long actSeq, long errorSeq,
            String lastError, long lastErrorAtMs, long nowMs, long worldMovedAtMs) {
        if (payload == null) {
            return;
        }
        try {
            if (!payload.has("hints") || payload.get("hints").isJsonNull()
                    || !payload.get("hints").isJsonObject()) {
                // No hints block: nothing to refresh, and we must not create one (#66/#68).
                return;
            }
            JsonObject hints = payload.getAsJsonObject("hints");
            hints.addProperty("act_seq", actSeq);
            hints.addProperty("last_error_seq", errorSeq);
            if (lastError == null) {
                // Nothing has ever failed: a leftover copy of an older error would be a lie.
                hints.remove("last_error");
                hints.remove("last_error_age_ms");
                hints.remove("last_error_fresh");
                return;
            }
            hints.addProperty("last_error", lastError);
            // Clamped: a wall clock that jumped backwards must not age an error into the future.
            hints.addProperty("last_error_age_ms", Math.max(0L, nowMs - lastErrorAtMs));
            // Issue #119 · C10-05: fresh = the error from the act you just sent, produced by an
            // action (never by a read that answered NOT_READY), and nothing has moved since.
            hints.addProperty("last_error_fresh",
                    spirelink.state.ErrorFreshness.isFreshFor(errorSeq, actSeq, lastError,
                            lastErrorAtMs, worldMovedAtMs));
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("mergeDiagnostics", t);
        }
    }

    /**
     * Issue #56: give the in-flight guard its wall-clock check without waiting for an act. Called at
     * the entry of every tool that can observe a queued action, so a poll releases it too.
     */
    private void pumpInFlightGuard() {
        try {
            spirelink.util.ActionInFlight.blocked(stateBuilder.version());
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("pumpInFlightGuard", t);
        }
    }

    /**
     * Re-reads the cross-run memory at serve time. A cached snapshot could be built before the run
     * was recorded (the death frame builds postmortem after memory), which made
     * `memory.run_history` look empty even though history.json already had the record.
     *
     * <p>Notes are rebuilt here too, and this is the only place that consumes the per-run dedupe
     * window: on the main menu the cached body is served over and over, so claiming while building
     * would either repeat every note (no claim) or burn notes that are never sent (claim at build).
     * <p>{@code force_notes:true} reopens the window for this one response.
     */
    private void refreshMemory(JsonObject payload, boolean forceNotes) {
        try {
            if (!payload.has("memory") || !payload.get("memory").isJsonObject()) {
                return;
            }
            JsonObject memory = payload.getAsJsonObject("memory");
            com.google.gson.JsonArray runs = StateBuilder.recentRunRecords(5);
            memory.add("run_history", runs);
            memory.addProperty("run_history_count", spirelink.util.RunHistory.count());
            spirelink.util.NotesDedupe.setForce(forceNotes);
            try {
                // recent_notes first: it carries every kind, so a general note still lands there
                // before briefing.general_notes can claim it (same order as the old build path).
                memory.add("recent_notes", StateBuilder.freshRecentNotes());
                if (memory.has("briefing") && memory.get("briefing").isJsonObject()) {
                    // Rebuild the whole briefing so notes counts and death_causes are as fresh as
                    // the run history they summarise.
                    memory.add("briefing", StateBuilder.freshBriefing());
                }
                memory.addProperty("notes_omitted_this_run", spirelink.util.NotesDedupe.omitted());
            } finally {
                spirelink.util.NotesDedupe.setForce(false);
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("refreshMemory", t);
        }
    }

    private JsonObject payloadFor(StateBuilder.Snapshot snapshot) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", snapshot.ready);
        payload.addProperty("ready", snapshot.ready);
        payload.addProperty("state_version", snapshot.version);
        if (snapshot.ready) {
            payload.add("error", JsonNull.INSTANCE);
        } else {
            // Issue #121 · C12-01: while the save is loading, say that instead of "the animation has
            // not finished" - and never let this answer still read like "the main menu is up".
            payload.add("error", error("NOT_READY",
                    notReadyReason(spirelink.state.LoadingWindow.active(),
                            spirelink.state.RunExitWindow.active(), "get_state"),
                    snapshot.version));
            // Issue #121 · C12-04: the same top-level blocked_ui legal_actions has always reported.
            payload.addProperty("blocked_ui", blockedUi(snapshot.body));
        }
        mergeBlocks(payload, snapshot.body);
        return payload;
    }

    private JsonObject notReadyPayload(StateBuilder.Snapshot cached, String message) {
        // Issue #69: when the game's own loop is gone for good, "please retry" is a lie - the only
        // way out is a restart. A dead game can only reach the agent through this cached path (the
        // main thread will never answer again), so the terminal signal is decided right here.
        boolean dead = cached != null && cached.gameDead;
        // Every refusal that reaches the agent must also land in hints.last_error.
        spirelink.util.CrashGuard.noteActionError(dead ? "GAME_DEAD" : "NOT_READY", "get_state");
        // Issue #121 · C12-01: a queue timeout during the save load is not "the game is animating",
        // so the loading sentence wins over the generic retry wording.
        boolean loading = spirelink.state.LoadingWindow.active();
        String why = (loading || spirelink.state.RunExitWindow.active())
                ? notReadyReason(loading, !loading, "get_state") : message;
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("ready", false);
        payload.addProperty("state_version", cached == null ? 0 : cached.version);
        payload.add("error", dead
                ? error("GAME_DEAD", spirelink.state.GameLiveness.DEAD_HINT, cached.version)
                : error("NOT_READY", why, cached == null ? 0 : cached.version));
        // Issue #121 · C12-04 / #124 · C15-01: symmetric with legal_actions (top level, always present
        // when not ready) and sourced from the same live ui.
        payload.addProperty("blocked_ui", reportedUiOrCached());
        if (cached != null) {
            mergeBlocks(payload, cached.body);
            // Issue #88: this body came from the cache, so its last-act diagnostics are as old as
            // the snapshot; a "not ready" answer must not explain the wrong refusal.
            refreshDiagnostics(payload);
            // Issue #123 · C14-03: the cached meta was built in a READY frame, so its awaiting says
            // "playing". On a cache-served not-ready answer that is a lie about why we cannot read.
            JsonObject meta = payload.has("meta") && payload.get("meta").isJsonObject()
                    ? payload.getAsJsonObject("meta") : null;
            if (meta != null) {
                meta.addProperty("awaiting", loading ? "loading"
                        : (spirelink.state.RunExitWindow.active() ? "transition" : "transition"));
            }
        }
        return payload;
    }

    private JsonObject internalErrorPayload(StateBuilder.Snapshot cached, Throwable error) {
        spirelink.util.CrashGuard.noteActionError("INTERNAL", "get_state");
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("ready", false);
        payload.addProperty("state_version", cached == null ? 0 : cached.version);
        String detail = error == null ? "" : (error.getClass().getSimpleName() + ": " + error.getMessage());
        payload.add("error", error("INTERNAL", "模组内部错误: " + detail,
                cached == null ? 0 : cached.version));
        if (cached != null) {
            mergeBlocks(payload, cached.body);
            // Issue #88: the cached hints still have to name the refusal that just happened.
            refreshDiagnostics(payload);
        }
        return payload;
    }

    private void mergeBlocks(JsonObject target, JsonObject body) {
        if (body == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
            if ("state_version".equals(entry.getKey()) || "ready".equals(entry.getKey())) {
                continue;
            }
            target.add(entry.getKey(), entry.getValue());
        }
    }


    // ---------------------------------------------------------------- P1: legal_actions / act

    private ToolResult legalActions() {
        // Issue #56: the guard must be pumped BEFORE the main-thread call. Doing it after meant a
        // wedged main thread (the only reason an action is still queued) burned the full 10 s queue
        // timeout first, so the 3 s wall clock released at 10-22 s instead of 3 s.
        pumpInFlightGuard();
        MainThreadQueue.Result result = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                return planPayload();
            }
        }, readTimeoutMs());
        // Belt and braces: if the version moved while we waited, the guard goes away right here.
        pumpInFlightGuard();
        if (result.status == MainThreadQueue.Result.Status.OK && result.value instanceof JsonObject) {
            JsonObject payload = (JsonObject) result.value;
            // Issue #84b: the hints block in this payload comes from the cached stable snapshot, so
            // its frames/beats/ms_since_* could be thousands of frames old while the same-tick
            // get_state reports the live numbers. Those numbers are how an agent decides whether the
            // game is still moving (and #69 decides "the run is dead" from them), so refresh them
            // here instead of serving the cache. Merged only if hints already exist (#66/#68).
            mergeLiveness(payload);
            // Issue #88: same source of truth as the same-tick get_state, so the two answers can
            // never describe different refusals.
            refreshDiagnostics(payload);
            AgentHud.push(spirelink.ui.HudPhrases.checked());
            return new ToolResult(payload, false);
        }
        log.info("legal_actions 未就绪: " + result.status);
        AgentHud.push(AgentHud.MSG_NOT_READY);
        return new ToolResult(notReadyLegalActions(result.status), true);
    }

    /** Main thread: one consistent view of the world plus its action list. */
    private JsonObject planPayload() {
        StateBuilder.Snapshot snapshot = stateBuilder.view();
        // Same source as get_state: the cached snapshot's meta.ui, so the version and the ui can
        // never disagree between the two tools.
        String ui = uiOf(snapshot);
        JsonObject payload = new JsonObject();
        payload.addProperty("state_version", snapshot.version);
        payload.addProperty("screen_type", screenType(snapshot));
        payload.addProperty("ui", ui);
        JsonArray actions = new JsonArray();
        if (snapshot.ready) {
            List<ActionSpec> specs = ActionPlanner.actionsFor(ui);
            for (ActionSpec spec : specs) {
                actions.add(spec.toJson());
            }
        }
        // Issue #120 · C11-03: "you may act" and "here are your moves" are decided in ONE place.
        // Caught mid-transition the planner can return nothing while the gate still says ready, and
        // the answer used to be actions:[] + ready:true + no error at all - an agent reading a single
        // frame concluded "no way out". Now ready is derived from the list, and an empty list always
        // comes with the sentence that explains it.
        boolean actionable = snapshot.ready && actions.size() > 0;
        payload.addProperty("ready", actionable);
        if (!actionable) {
            payload.addProperty("blocked_ui", ui);
            // Issue #121 · C12-01: during the save load the reason is "loading", not "the screen has
            // nothing to offer" - same field, honest sentence.
            boolean loading = spirelink.state.LoadingWindow.active();
            payload.add("error", error("NOT_READY",
                    emptyActionsReason(loading, spirelink.state.RunExitWindow.active(), ui,
                            snapshot.ready),
                    snapshot.version));
            logEmptyFrame(loading, ui, snapshot.ready);
        }
        // One round trip instead of two: ui/awaiting/hints travel with the action list, so the
        // caller cannot act on a state that disagrees with the actions it was given.
        JsonObject meta = snapshot.body != null && snapshot.body.has("meta")
                ? snapshot.body.getAsJsonObject("meta") : null;
        String awaiting = meta != null && meta.has("awaiting") && !meta.get("awaiting").isJsonNull()
                ? meta.get("awaiting").getAsString()
                : (snapshot.ready ? "playing" : "enemy_turn");
        if (!actionable && "playing".equals(awaiting)) {
            awaiting = "screen_up";
        }
        payload.addProperty("awaiting", awaiting);
        if (snapshot.body != null && snapshot.body.has("hints")) {
            payload.add("hints", snapshot.body.get("hints"));
        }
        payload.add("actions", actions);
        return payload;
    }

    /**
     * Issue #122 · C12-01: one log line per situation, not per frame.
     *
     * <p>QA greps the log to prove the loading window really opened, so the loading case has to be
     * greppable - but {@code legal_actions} is polled every ~40 ms, so an unthrottled line would bury
     * everything else. One line when the window opens, one when it closes.</p>
     */
    private void logEmptyFrame(boolean loading, String ui, boolean gateReady) {
        if (loading != loadingLogged) {
            loadingLogged = loading;
            if (loading) {
                log.info("存档正在载入（我们刚点过 continue_run，run 屏还没出现）：legal_actions 回 "
                        + "NOT_READY、不再广告菜单动作，直到 ui 变成 run 屏或到达上界");
            } else {
                log.info("载入窗口结束：ui=" + ui + "，动作表恢复");
            }
            return;
        }
        if (!loading && gateReady) {
            log.info("legal_actions: ui=" + ui + " 这一帧没有可执行动作，ready 改成 false");
        }
    }

    /** Issue #122 · C12-01: {@link #logEmptyFrame} logs the loading window once per click. */
    private static boolean loadingLogged;

    /** {@code include} may be an array or a comma separated string; true when it asks for the deck. */
    private static boolean wantsDeck(JsonObject arguments) {
        return wantsInclude(arguments, "deck");
    }

    /** True when {@code include} names {@code block} (array or comma separated string). */
    private static boolean wantsInclude(JsonObject arguments, String block) {
        if (arguments == null || !arguments.has("include")) {
            return false;
        }
        JsonElement raw = arguments.get("include");
        if (raw == null || raw.isJsonNull()) {
            return false;
        }
        if (raw.isJsonArray()) {
            for (JsonElement item : raw.getAsJsonArray()) {
                if (item != null && !item.isJsonNull() && block.equals(item.getAsString())) {
                    return true;
                }
            }
            return false;
        }
        return raw.getAsString().contains(block);
    }

    /** Sends the pointer to a card that is on screen right now (hand / reward row / grid). */
    private static void lookAtCard(String uuid) {
        try {
            com.megacrit.cardcrawl.characters.AbstractPlayer player =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.player;
            if (player == null || uuid == null) {
                return;
            }
            if (player.hand != null) {
                for (com.megacrit.cardcrawl.cards.AbstractCard card : player.hand.group) {
                    if (card != null && card.hb != null && uuid.equals(String.valueOf(card.uuid))) {
                        spirelink.ui.AgentCursor.flyToEta(card.hb.cX, card.hb.cY);
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
            // looking at a card must never break the read
        }
    }

    /** {@code meta.ui} of a payload, or "" when absent. */
    private static String metaUi(JsonObject body) {
        if (body == null || !body.has("meta") || body.get("meta").isJsonNull()) {
            return "";
        }
        JsonObject meta = body.getAsJsonObject("meta");
        return meta.has("ui") && !meta.get("ui").isJsonNull() ? meta.get("ui").getAsString() : "";
    }

    private String uiOf(StateBuilder.Snapshot snapshot) {
        String cached = uiOfBody(snapshot == null ? null : snapshot.body);
        return cached != null ? cached : stateBuilder.currentUiOnMainThread();
    }

    /**
     * {@code meta.ui} of a state body, or {@code null} when that body carries none.
     *
     * <p>Issue #119 · C10-02: pure and body-only, so every answer that must report "the same ui
     * get_state would" can go through one function — including the {@code queued} answer, which is
     * built on the request thread and therefore may not touch the game.</p>
     */
    static String uiOfBody(JsonObject body) {
        if (body == null || !body.has("meta") || body.get("meta").isJsonNull()) {
            return null;
        }
        JsonObject meta = body.getAsJsonObject("meta");
        if (meta == null || !meta.has("ui") || meta.get("ui").isJsonNull()) {
            return null;
        }
        return meta.get("ui").getAsString();
    }

    /**
     * {@code meta.ui} of a snapshot, read from the cache only (issue #119 · C10-02).
     *
     * <p>Unlike {@link #uiOf} this never falls back to {@code currentUiOnMainThread()}: the caller is
     * the request thread (the "queued" answer), so the game must not be touched from here. With no
     * snapshot at all it keeps the old {@code "other"} wording.</p>
     */
    private static String cachedUi(StateBuilder.Snapshot snapshot) {
        String ui = uiOfBody(snapshot == null ? null : snapshot.body);
        return ui == null ? "other" : ui;
    }

    private static String screenType(StateBuilder.Snapshot snapshot) {
        if (snapshot != null && snapshot.body != null && snapshot.body.has("meta")) {
            JsonObject meta = snapshot.body.getAsJsonObject("meta");
            if (meta != null && meta.has("screen_type") && !meta.get("screen_type").isJsonNull()) {
                return meta.get("screen_type").getAsString();
            }
        }
        return "NONE";
    }

    /**
     * Wording for a read tool that waited out the whole queue timeout (issue #97 / C3-06).
     *
     * <p>The "10 秒" used to be a literal in two places while the real budget is
     * {@link #COMMAND_TIMEOUT_MS}; both reads now derive it, so raising the timeout cannot leave a
     * sentence behind that lies about how long the agent waited.
     */
    static String readTimeoutMessage(String tool) {
        return "游戏动画未结束（" + (COMMAND_TIMEOUT_MS / 1000) + " 秒内未静止），稍后重试 " + tool + "。";
    }

    /**
     * Issue #97 / C3-06: the answer for an {@code act} that never reached the main thread.
     *
     * <p>Two facts decide what the agent should do next, and the old text carried neither:
     * <ul>
     *   <li><b>how long we actually waited</b> - a {@code BUSY} single-flight queue rejects in
     *       milliseconds, while {@code TIMEOUT} means the full {@link #COMMAND_TIMEOUT_MS} ran out.
     *       The old sentence claimed "10 秒" in both cases (QA measured 2.8 s);</li>
     *   <li><b>whether the state version moved while we waited</b> - with two concurrent acts the
     *       other one has often already been applied, and then retrying this one stacks a second
     *       click onto a screen that no longer exists.</li>
     * </ul>
     *
     * <p>So the message states the measured wait, the queue status, and - when the version moved -
     * tells the agent to re-read the state instead of retrying.
     */
    JsonObject queueMissPayload(String type, JsonObject args,
                                MainThreadQueue.Result.Status status, long waitedMs, int versionBefore) {
        int versionAfter = stateBuilder.version();
        String message;
        if (status == MainThreadQueue.Result.Status.BUSY) {
            message = "主线程队列里已经有一条动作在排队（单飞队列，不再接收第二条），"
                    + "这条没有执行：从发出到被打回只过了 " + waitedMs + "ms。"
                    + "（这是队列被占用，不是 3 秒墙钟护栏，所以回包里没有 retry_after_ms。）";
        } else {
            message = "等了 " + waitedMs + "ms 主线程仍没有执行这条动作（队列上限 "
                    + COMMAND_TIMEOUT_MS + "ms 已到），这条没有执行。";
        }
        boolean advanced = versionAfter != versionBefore;
        if (advanced) {
            message += "等待期间 state_version 从 " + versionBefore + " 变成了 " + versionAfter
                    + "：另一个动作已经生效，不要重发这条；先 get_state 看新状态再决定下一步。";
        } else {
            message += "state_version 仍是 " + versionAfter
                    + "，先 get_state 确认屏上还是不是这条动作，再决定要不要重发。";
        }
        // Issue #125 · C16-02: BUSY means "the single-flight main-thread slot is taken", which is NOT the
        // same as "your previous action is still landing" - a long transition frame occupies the slot
        // with nothing of ours in flight (QA: legal_actions answered ready:true, the very next act got
        // ACTION_IN_FLIGHT with waited_ms 0). Only claim ACTION_IN_FLIGHT when something really is.
        boolean actionPending = status == MainThreadQueue.Result.Status.BUSY
                && spirelink.util.ActionInFlight.blocked(versionAfter) >= 0;
        JsonObject payload = rejectedPayload(type, args,
                queueMissCode(status == MainThreadQueue.Result.Status.BUSY, actionPending),
                actionPending ? message : busyButNothingInFlight(message));
        payload.addProperty("state_version", versionAfter);
        payload.addProperty("waited_ms", waitedMs);
        payload.addProperty("queue_status", status == null ? "UNKNOWN" : status.name());
        payload.addProperty("version_advanced", advanced);
        // Issue #120 · C11-01: the documented place to read these three is error.*, so they go there
        // too (top level stays for callers that already parse it).
        mirrorIntoError(payload, "queue_status", "waited_ms", "version_advanced");
        return payload;
    }

    /**
     * Issue #108 · C7-02: one situation, one error code.
     *
     * <p>A single-flight {@code BUSY} rejection means <b>an action is in flight</b> — the very
     * definition of {@code ACTION_IN_FLIGHT} in the error-code table (and in the skill docs, which
     * tell agents "再发动作会被 ACTION_IN_FLIGHT 拒"). It used to answer {@code NOT_READY}, so the
     * same agent-visible situation came back with two different codes depending on <em>which</em>
     * guard caught it (the 3 s wall-clock guard vs. the main-thread queue). {@code TIMEOUT} keeps
     * {@code NOT_READY}: there nothing of ours is in flight any more, the main thread simply did not
     * execute the command within the queue budget.</p>
     */
    static String queueMissCode(MainThreadQueue.Result.Status status) {
        return queueMissCode(status == MainThreadQueue.Result.Status.BUSY, true);
    }

    /**
     * Issue #125 · C16-02: the same, but only reporting {@code ACTION_IN_FLIGHT} when an action of ours
     * really is in flight.
     *
     * @param busy          the single-flight main-thread slot was taken
     * @param actionPending {@code ActionInFlight} still holds an unlisted action
     */
    static String queueMissCode(boolean busy, boolean actionPending) {
        if (!busy) {
            return "NOT_READY";
        }
        return actionPending ? "ACTION_IN_FLIGHT" : "NOT_READY";
    }

    /** Issue #125 · C16-02: the sentence for "slot busy, but nothing of ours is landing". */
    static String busyButNothingInFlight(String message) {
        return message == null ? null : message.replace(
                "主线程队列里已经有一条动作在排队（单飞队列，不再接收第二条），",
                "主线程正忙着执行别的东西（单飞队列被占：过渡/动画那一帧，不是我们上一条动作在落地），");
    }

    private JsonObject notReadyLegalActions(MainThreadQueue.Result.Status status) {
        StateBuilder.Snapshot cached = stateBuilder.cachedView();
        // Same terminal signal as get_state (issue #69): a dead game is not "try again later".
        boolean dead = cached != null && cached.gameDead;
        JsonObject payload = new JsonObject();
        // Issue #126 · C17-01: this body was the only read answer without an `ok` field, so the MCP
        // layer could not tell that it is an ordinary "not ready" answer and shipped it as isError:true
        // while get_state answered the very same thing as a normal result.
        payload.addProperty("ok", false);
        payload.addProperty("ready", false);
        payload.addProperty("state_version", cached == null ? 0 : cached.version);
        payload.addProperty("screen_type", screenType(cached));
        // Issue #119 · C10-02: same rule as the queued act answer - one snapshot, one ui. This used to
        // say "other" while screen_type named the real screen, so an agent told to look at the
        // advertised screen was sent to a screen that does not exist.
        // Issue #124 · C15-01: same ui the reads report (cached body may still describe the old screen).
        payload.addProperty("ui", reportedUiOrCached());
        payload.add("actions", new JsonArray());
        // Issue #121 · C12-01: during the save load the honest reason is "loading", not "another
        // request is running" / "the animation has not finished".
        boolean loading = spirelink.state.LoadingWindow.active();
        boolean leavingRun = spirelink.state.RunExitWindow.active();
        payload.add("error", dead
                ? error("GAME_DEAD", spirelink.state.GameLiveness.DEAD_HINT, cached.version)
                : error("NOT_READY", (loading || leavingRun)
                        ? notReadyReason(loading, !loading, "legal_actions")
                        : (status == MainThreadQueue.Result.Status.BUSY
                                ? "另一个请求正在处理，稍后重试 legal_actions。"
                                : readTimeoutMessage("legal_actions")),
                        cached == null ? 0 : cached.version));
        // Issue #123 · S2/C14-04: the same vocabulary the in-band answers use, so a cache-served frame
        // never leaves the agent guessing which wait it is in.
        payload.addProperty("awaiting", loading ? "loading" : "transition");
        // Issue #121 · C12-04 / #125: same top-level field the in-band empty answer carries, and the
        // same source as `ui` above - a cache-served frame must not say ui="other" and blocked_ui="combat".
        payload.addProperty("blocked_ui", reportedUiOrCached());
        return payload;
    }

    private ToolResult act(JsonObject arguments) {
        // Issue #56: a queued action may release here even if get_state is never called again.
        pumpInFlightGuard();
        String actionId = strArg(arguments, "action_id");
        // Every act call bumps the sequence, so hints can tell "error from the last act" from an old one.
        spirelink.util.CrashGuard.nextActionSeq();
        JsonObject cached = idempotency.get(actionId);
        if (cached != null) {
            log.info("act 幂等命中: " + actionId);
            return new ToolResult(cached, !isOk(cached));
        }
        // Issues #62 / #72: check the types BEFORE anything is executed. "30" is not 30, an array is
        // not a payload, and silently coercing either makes an agent believe its version check and
        // its payload were honoured when neither was.
        String badArgument = actArgumentError(arguments);
        if (badArgument != null) {
            return rejectAct(actionId, "BAD_REQUEST", badArgument);
        }
        String type = strArg(arguments, "type");
        JsonObject args = arguments.has("payload")
                ? arguments.getAsJsonObject("payload") : new JsonObject();
        Integer requested = strictIntArg(arguments, "state_version");
        if (type == null || type.isEmpty()) {
            return rejectAct(actionId, "BAD_REQUEST", "act 必须带 type");
        }
        if (requested == null) {
            return rejectAct(actionId, "BAD_REQUEST", "act 必须带 state_version（上一次 get_state 的值）");
        }

        if (spirelink.util.CrashGuard.gameCrash() != null) {
            // A crashed render loop cannot apply anything, but the channel must stay open and honest.
            JsonObject payload = rejectedPayload(type, args, "NOT_READY",
                    "游戏渲染已崩溃（" + spirelink.util.CrashGuard.gameCrash()
                            + "），重启游戏后再继续；get_state 仍可读");
            attachPlan(payload);
            return new ToolResult(payload, true);
        }
        int current = stateBuilder.version();
        // The in-flight probe has a wall-clock side effect (it may release the 3 s guard), so it is
        // only asked when the version already matches: a stale request must not release somebody
        // else's guard. -1 means "not asked / nothing pending".
        int pending = requested.intValue() == current
                ? spirelink.util.ActionInFlight.blocked(current) : -1;
        // Issue #121 · C12-03: one function decides which refusal wins - see preflightRejectionCode.
        boolean loadingNow = spirelink.state.LoadingWindow.active();
        boolean leavingNow = spirelink.state.RunExitWindow.active();
        String refusal = preflightRejectionCode(loadingNow, leavingNow,
                requested.intValue(), current, pending >= 0);
        if ("NOT_READY".equals(refusal)) {
            // Issue #121 · C12-01: the save is loading (continue_run was already spent). Nothing can
            // execute, and telling the agent "stale version, re-read" would only loop: re-reading
            // during the load returns the same main-menu frame. NOT_READY + wait is the honest answer.
            // Issue #123 · S2: same for the abandon transition - the dungeon being described is gone.
            JsonObject payload = rejectedPayload(type, args, "NOT_READY",
                    loadingNow ? loadingMessage("act") : leavingRunMessage("act"));
            log.info("act " + type + " 被拒：" + (loadingNow
                    ? "存档正在载入（continue_run 已点，run 屏还没出现）"
                    : "正在离开本局（放弃过渡中）") + " → NOT_READY");
            AgentHud.push(AgentHud.MSG_NOT_READY);
            idempotency.put(actionId, payload);
            return new ToolResult(payload, true);
        }
        if ("STALE_VERSION".equals(refusal)) {
            JsonObject payload = rejectedPayload(type, args, "STALE_VERSION",
                    staleVersionMessage(requested.intValue(), current));
            attachPlan(payload);
            AgentHud.push("版本过期，重读");
            idempotency.put(actionId, payload);
            return new ToolResult(payload, true);
        }

        // A previous action that answered "queued" is still running. Sending another one on the same
        // state version stacks two clicks onto one dialog/animation and can freeze the render loop
        // (GremlinWheelGame, campfire). In-flight means in-flight: refuse until the version moves.
        // Wall-clock guard (never version-only): see ActionInFlight for why.
        if ("ACTION_IN_FLIGHT".equals(refusal)) {
            long retryAfterMs = spirelink.util.ActionInFlight.retryAfterMs();
            JsonObject payload = rejectedPayload(type, args, "ACTION_IN_FLIGHT",
                    "上一个动作还在执行（queued " + spirelink.util.ActionInFlight.type() + "，"
                            + "已等 " + spirelink.util.ActionInFlight.elapsedMs()
                            + "ms，state_version " + pending + "）。"
                            + "墙钟护栏 " + spirelink.util.ActionInFlight.TIMEOUT_MS
                            + "ms 到点就自动解除（还剩 " + retryAfterMs + "ms），"
                            + "期间请轮询 get_state 直到 ready 变 true 或版本推进，不要重发。");
            payload.addProperty("state_version", current);
            // Issue #56: tell the agent the number instead of making it guess from the docs.
            payload.addProperty("retry_after_ms", retryAfterMs);
            // Issue #120 · C11-01: same place as every other rejection detail (error.retry_after_ms),
            // with the top-level copy kept so one shape covers the 3 s guard and the queue miss.
            mirrorIntoError(payload, "retry_after_ms");
            AgentHud.push("动作仍在执行，请等待");
            idempotency.put(actionId, payload);
            return new ToolResult(payload, true);
        }

        // Reach first (on this thread — the render loop keeps animating), then let the render
        // thread apply the action: the hand lands before the world changes, never after.
        // The clock starts before {@code reach}: the agent waits for the pointer flight too, so that
        // is part of "how long did I still get nothing".
        // C6-01: design 5's {@code log} diffs a pre/post digest, and the digest reads live game
        // state, so the "before" one has to be taken on the game thread right before the action.
        // Issue #122 · C12-01: remember the moment *we* start a run. The game's own flags cannot mark
        // this window - MenuButton.resumeGame() creates the player object before the dungeon exists, so
        // "loadingSave && player == null" is false from the very first frame (see LoadingWindow) - so
        // the timestamp is ours. From here until the run is playable, get_state / legal_actions / act
        // all answer NOT_READY +「存档正在载入」and continue_run is no longer advertised.
        final boolean startsRun = startsRunLoad(type);
        if (startsRun) {
            spirelink.state.LoadingWindow.noteRunStart();
        }
        // Issue #123 · S2: abandoning keeps mode=GAMEPLAY and the old dungeon alive for a few frames -
        // mark the transition so those frames neither describe nor advertise the departing screens.
        final boolean startsExit = startsRunExit(type);
        if (startsExit) {
            spirelink.state.RunExitWindow.noteRunExit();
        }
        final JsonObject[] digestBefore = new JsonObject[1];
        long sentAtMs = System.currentTimeMillis();
        spirelink.actions.ActionExecutor.reach(type, args);
        MainThreadQueue.Result exec = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                digestBefore[0] = spirelink.state.CombatDigest.capture();
                return ActionExecutor.execute(type, args);
            }
        }, COMMAND_TIMEOUT_MS);
        long waitedMs = System.currentTimeMillis() - sentAtMs;

        if (exec.status == MainThreadQueue.Result.Status.TIMEOUT
                || exec.status == MainThreadQueue.Result.Status.BUSY) {
            // Issue #97 / C3-06: the old answer here was a fixed sentence ("主线程 10 秒内没有执行
            // 这条动作，可以安全重试") that lied in both directions - a BUSY queue rejects after a
            // few milliseconds, and when two acts race the other one has often already been applied,
            // so "safe to retry" invited a duplicate action on the same screen.
            // Issue #122 · C12-01: a BUSY queue rejected the command outright, so no click happened and
            // the loading window must not stay open. TIMEOUT keeps it - the main thread may still run
            // the click, and the window is bounded anyway.
            if (exec.status == MainThreadQueue.Result.Status.BUSY) {
                clearSceneWindows(startsRun, startsExit);
            }
            JsonObject payload = queueMissPayload(type, args, exec.status, waitedMs, current);
            // C7-02: BUSY is "another action is still running", so the HUD says that instead of
            // "game not ready"; TIMEOUT really is "the game did not get to it".
            AgentHud.push(exec.status == MainThreadQueue.Result.Status.BUSY
                    ? AgentHud.MSG_BUSY : AgentHud.MSG_NOT_READY);
            idempotency.put(actionId, payload);
            return new ToolResult(payload, true);
        }
        if (exec.status != MainThreadQueue.Result.Status.OK || !(exec.value instanceof ActionResult)) {
            String detail = exec.error == null ? "未知错误" : exec.error.toString();
            if (exec.error != null) {
                // Always leave a stack in the log: "INTERNAL" alone is undebuggable.
                log.warn("动作内部异常 " + type + ": " + detail + " | "
                        + spirelink.actions.ActionExecutor.firstFrames(exec.error, 8));
            }
            clearSceneWindows(startsRun, startsExit);
            JsonObject payload = rejectedPayload(type, args, "INTERNAL",
                    "执行动作时模组内部出错: " + detail);
            AgentHud.push(AgentHud.MSG_ERROR);
            idempotency.put(actionId, payload);
            return new ToolResult(payload, true);
        }

        ActionResult actionResult = (ActionResult) exec.value;
        if (!actionResult.ok) {
            // Issue #108 · C7-05: only a rejection about the *action* can be a planner/executor
            // disagreement. A parameter-level rejection (index/uuid/slot out of range, a card or
            // option that is not among the offered ones, a missing target) is the caller sending a
            // payload the advertisement never contained - the planner was right to advertise the
            // type, so warning here turned every mistyped index into a false "不一致" alarm.
            if (plannerExecutorMismatch(stillOffered(type), actionResult)) {
                log.warn("planner/executor 不一致: 仍在给 " + type
                        + "，但 executor 拒绝: " + actionResult.message);
            }
            // Issue #122 · C12-01 / #123 · S2: a rejected click means no load and no exit is coming.
            clearSceneWindows(startsRun, startsExit);
            JsonObject payload = rejectedPayload(type, args,
                    actionResult.code == null ? "ILLEGAL_ACTION" : actionResult.code,
                    actionResult.message == null ? "动作不可执行" : actionResult.message);
            attachPlan(payload);
            AgentHud.push("动作被拒");
            idempotency.put(actionId, payload);
            return new ToolResult(payload, true);
        }

        // Issue #123 · C14-01/C14-03: a run-start action builds the whole dungeon on the game thread
        // (~10 s measured by QA: continue_run 10,689 ms, embark 10,686 ms), so state_version cannot
        // move until the load is over. Sitting in the settle loop is what reported an applied click as
        // "queued" after 10 s of silence - and it also hid the loading window from the agent, because
        // the click and the load were the same blocking call. Answer at once instead: the click is
        // submitted, the payload says so, and every read during the load answers NOT_READY +
        //「存档正在载入」(with a short read timeout, so polling actually sees those frames).
        if (startsRun || startsExit) {
            // Same reasoning for the abandon transition (S2): the version cannot move while the menu
            // is being rebuilt, so waiting would just burn the 10 s budget again.
            JsonObject payload = transitionSubmittedPayload(type, args, startsRun);
            idempotency.put(actionId, payload);
            return new ToolResult(payload, false);
        }

        // Executed: wait for a new stable state (design 5 / review R6 three-state outcome).
        // An action that verified its own effect (screen really closed) counts as applied even when
        // the state signature does not move - otherwise a real effect was reported as "queued" and
        // the agent kept polling a grid that was already gone.
        long deadline = System.currentTimeMillis() + ACT_STABLE_TIMEOUT_MS;
        // Issue #123 · C14-02: choose_character / choose_ascension cannot move the version (the
        // selected character is not part of the state signature), so the loop below always burned the
        // full 10 s and then reported an applied click as "queued" (QA: 10,067 ms, 237→237). These
        // settle at submission; their fresh plan travels with the answer like any other applied one.
        boolean settled = actionResult.verified || settlesAtSubmission(type);
        while (System.currentTimeMillis() < deadline) {
            if (stateBuilder.version() != current) {
                settled = true;
                break;
            }
            try {
                Thread.sleep(ACT_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        if (!settled) {
            // Remember the version the action started from; every act until it advances (or the
            // wall-clock budget runs out) is refused.
            spirelink.util.ActionInFlight.mark(current, type);
        } else {
            spirelink.util.ActionInFlight.clear();
        }
        payload.addProperty("outcome", settled ? "applied" : "queued");
        // hints are only pushed when something went wrong or the game is still moving.
        if (settled) {
            StateBuilder.noteAcceptance();
        }
        payload.addProperty("state_version", stateBuilder.version());
        payload.add("error", JsonNull.INSTANCE);
        JsonObject action = new JsonObject();
        action.addProperty("type", type);
        action.add("payload", args);
        payload.add("action", action);
        if (settled) {
            // Take the post-action digest before the plan round trip, so it describes the state the
            // action settled into rather than one a later frame may have moved again.
            JsonObject digestAfter = captureDigest();
            attachPlan(payload);
            // C6-01: tools.md / SKILL.md / recovery.md all tell the agent to read this sentence on
            // an applied answer, and design 5 already had the builder - it was just never wired in.
            attachAppliedLog(payload, type, args, digestBefore[0], digestAfter);
        } else {
            // Issue #119 · C10-02: this used to hard-code ui "other" while get_state - served from the
            // very same cached snapshot - reported e.g. "treasure". One call must not describe two
            // screens, so the queued answer carries the snapshot's ui (and an empty action list:
            // nothing may be sent until the version moves anyway).
            payload.addProperty("ui", cachedUi(stateBuilder.cachedView()));
            payload.add("actions", new JsonArray());
        }
        if (!settled) {
            // The executor already pushed the human sentence once it really ran; this is only the
            // "not landed yet" variant, so it never repeats the action name twice.
            AgentHud.push("等刚才那步落地");
        }
        idempotency.put(actionId, payload);
        return new ToolResult(payload, false);
    }

    /**
     * C6-01: adds the documented {@code log} sentence to an <em>applied</em> answer.
     *
     * <p>{@code references/tools.md}("applied（成功并回到稳定状态，带 log）"), {@code SKILL.md}
     * ("applied → 读返回的 log") and {@code references/recovery.md} ("读 log，进入下一拍") all
     * describe this field as if it existed; the payload never had it. Design 5 even shipped the
     * builder — {@link spirelink.actions.ActionLog#describe} plus {@link spirelink.state.CombatDigest}
     * — with its own unit test; nothing ever called it. This is that call site.
     *
     * <p>The sentence therefore comes from measured data only: two digests captured on the game
     * thread (energy / hand / block / HP / round / monster HP), never from what was requested.
     * {@code ActionLog} knows nine action types; for the rest the HUD sentence is used instead of
     * inventing a second verb table here. Either way {@code log} is never empty.
     *
     * <p>Failure to build it must never fail an applied action: the fallback still tells the agent
     * which action ran.
     *
     * @param before digest captured on the game thread right before the action, may be {@code null}
     * @param after  digest captured after the action settled, may be {@code null}
     */
    void attachAppliedLog(JsonObject payload, String type, JsonObject args, JsonObject before,
                          JsonObject after) {
        if (payload == null) {
            return;
        }
        try {
            String sentence = spirelink.actions.ActionLog.describe(type, args, before, after);
            if (sentence == null || sentence.trim().isEmpty()) {
                // ActionLog 的词表只覆盖九种动作；其余类型退回 HUD 那句，而不是在这里再写一份。
                sentence = actionPhrase(type, args);
            }
            payload.addProperty("log", sentence);
        } catch (Throwable t) {
            log.warn("act 的 log 生成失败（不影响动作本身）: " + type + " " + t);
            payload.addProperty("log", "已执行 " + (type == null || type.isEmpty() ? "动作" : type));
        }
    }

    /**
     * Post-action digest for the {@code log} sentence, captured on the game thread.
     *
     * <p>{@link spirelink.state.CombatDigest#capture()} reads live game state, so it cannot run on
     * the request thread; a digest that is unavailable is simply passed as {@code null} and the
     * sentence degrades to the verb instead of guessing numbers.
     */
    private JsonObject captureDigest() {
        if (queue == null) {
            return null;
        }
        MainThreadQueue.Result result = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                return spirelink.state.CombatDigest.capture();
            }
        }, COMMAND_TIMEOUT_MS);
        return result != null && result.status == MainThreadQueue.Result.Status.OK
                && result.value instanceof JsonObject ? (JsonObject) result.value : null;
    }

    /** {@link spirelink.ui.HudPhrases#done} 的 default 分支文案：HUD 也不认识这个动作类型。 */
    private static final String HUD_UNKNOWN_PHRASE = "调整了下动作";

    /**
     * The one human sentence for an action type, from the same vocabulary the in-game HUD shows.
     *
     * <p>Reusing {@link spirelink.ui.HudPhrases#done} keeps "what the agent reads" and "what the
     * human sees" the same sentence instead of two hand-kept copies that drift. When even the HUD
     * has no line for the type, naming the action ("choose_character 已执行") beats handing back the
     * HUD's generic "调整了下动作". Never throws, never returns blank.
     */
    static String actionPhrase(String type, JsonObject args) {
        try {
            String phrase = spirelink.ui.HudPhrases.done(type, args);
            if (phrase != null && !phrase.trim().isEmpty()
                    && !HUD_UNKNOWN_PHRASE.equals(phrase.trim())) {
                return phrase;
            }
        } catch (Throwable ignored) {
            // fall through to the action name
        }
        return type == null || type.trim().isEmpty() ? "动作" : type.trim() + " 已执行";
    }

    /**
     * Issue #121 · C12-01: the continue_run loading window, in the agent's words.
     *
     * <p>Pure, so the wording can be pinned without a game. The three call sites (get_state's
     * not-ready answers, legal_actions' empty list, act's preflight) all use it: one situation, one
     * sentence, and never "the main menu is up so here are the menu buttons again".</p>
     */
    static String loadingMessage(String tool) {
        return "存档正在载入（continue_run 已点，战斗/地图还没出来），" + tool + " 这一刻没有可行动内容："
                + "这不是「没点动」，不要重发 continue_run；轮询 get_state 直到 ready:true 或 "
                + "state_version 变化。";
    }

    /**
     * Issue #121 · C12-01: the one sentence for "get_state cannot answer right now".
     *
     * @param loading whether the save-loading window is active
     *                (see {@link spirelink.state.LoadingWindow})
     * @param tool    the read tool being answered
     */
    static String notReadyMessage(boolean loading, String tool) {
        return notReadyReason(loading, false, tool);
    }

    /**
     * Issue #121 / #123: the sentence for "this read cannot answer right now".
     *
     * @param loading    the save-loading window is open (continue_run / embark just submitted)
     * @param leavingRun we just abandoned the run and the menu is not up yet (S2)
     * @param tool       the read tool being answered
     */
    static String notReadyReason(boolean loading, boolean leavingRun, String tool) {
        if (loading) {
            return loadingMessage(tool);
        }
        if (leavingRun) {
            return leavingRunMessage(tool);
        }
        return "游戏还没进入可读状态（动画未结束），稍后重试 " + tool + "。";
    }

    /**
     * Issue #123 · S2: the abandon transition, in the agent's words.
     *
     * <p>QA 看到的是一条最贵的错误：旧地图还挂着、{@code choose_map_node} 还在广告，真发出去却被
     * 「ui=main_menu 下不能执行」打回。所以这句话的重点是「上一帧的 ui/动作表已经不作数」。</p>
     */
    static String leavingRunMessage(String tool) {
        return "正在离开本局（放弃/回主菜单过渡中），" + tool + " 这一刻没有可执行内容："
                + "旧地牢的 ui 与动作表已经不作数，不要照上一帧的广告动手；"
                + "轮询 get_state 直到 ui=main_menu 且 ready:true（最长约 10 秒）。";
    }

    /**
     * Issue #121 · C12-01: how {@code legal_actions} explains an empty action list.
     *
     * @param loading   whether the save-loading window is active
     * @param ui        the screen the answer describes
     * @param gateReady whether the stability gate said "ready" (see {@link #emptyActionsMessage})
     */
    static String emptyActionsReason(boolean loading, boolean leavingRun, String ui,
            boolean gateReady) {
        if (loading) {
            return loadingMessage("legal_actions");
        }
        if (leavingRun) {
            // Issue #123 · S2: the ui reported here is the transition bucket, so the generic
            // "this screen has nothing to offer" sentence would describe a screen that is not up.
            return leavingRunMessage("legal_actions");
        }
        return emptyActionsMessage(ui, gateReady);
    }

    /**
     * Issue #121 · C12-04: the {@code ui} that has nothing to offer, read from a state body.
     *
     * <p>{@code legal_actions} has always reported this at the top level; {@code get_state} only had
     * it nested under {@code meta.blocked_ui}, so the same situation had two different addresses
     * depending on which tool answered. Both entry points now carry the top-level field.</p>
     */
    static String blockedUi(JsonObject body) {
        String ui = uiOfBody(body);
        return ui == null ? "other" : ui;
    }

    /**
     * Issue #120 · C11-03: the sentence that turns an empty action list into a wait, not a dead end.
     *
     * <p>纯函数（两个入参都由调用方给），所以两种路径的措辞可以在没有游戏的单测里钉死：
     * {@code legal_actions}'s {@code planPayload} 与 {@code get_state}'s {@code alignReady} 用同一句。</p>
     *
     * @param ui        the screen the answer describes
     * @param gateReady whether the stability gate said "ready" (i.e. the screen really is up but
     *                  offers nothing this frame) rather than "still moving"
     */
    static String emptyActionsMessage(String ui, boolean gateReady) {
        String where = ui == null || ui.trim().isEmpty() ? "当前屏" : "ui=" + ui;
        if (gateReady) {
            return where + " 这一帧还没有可执行动作：屏正在切换（或动作刚开始播放），不是「没有出路」，"
                    + "约 1 秒后重试 legal_actions 即可（ready 因此是 false）。";
        }
        return where + " 游戏还没静止（ready=false），这一帧的动作表不可信，稍后重试 legal_actions。";
    }

    /**
     * Issue #120 · C11-05: "too old" and "does not exist yet" are different mistakes.
     *
     * <p>现场：QA 故意发 {@code state_version:999999}，回包说「state_version 999999 已过期（当前 21）」
     * —— 未来的版本号不可能是「过期」，agent 按这句话去「重新取法律动作」也修不好自己的算术。措辞改成
     * 「比当前更新/还不存在」，错误码仍是 {@code STALE_VERSION}（不新增码，客户端不用改）。</p>
     *
     * <p>纯函数：两个版本号由调用方给，单测不需要游戏。</p>
     */
    static String staleVersionMessage(int requested, int current) {
        if (requested > current) {
            return "state_version " + requested + " 比当前版本（" + current + "）更新：这个版本号还不存在，"
                    + "只有 get_state / legal_actions 刚返回的那个才存在。"
                    + "按最近一次读到的 " + current + " 重发（错误码仍是 STALE_VERSION）。";
        }
        return "state_version " + requested + " 已过期（当前 " + current + "），重新取法律动作。";
    }

    /**
     * Issue #121 · C12-03: which refusal wins when several apply at once.
     *
     * <p>现场：{@code state_version} 落后几拍时发一个「这个屏上根本不存在」的动作，QA 拿到的是
     * {@code ILLEGAL_ACTION}（执行器的判断），于是「先信版本号还是先信屏」看起来像看运气。这里把顺序
     * 定死，原则是「谁离执行器越远、谁先说话」：</p>
     * <ol>
     *   <li><b>NOT_READY</b>：存档正在载入 / 渲染崩溃（后者在调用本函数之前就已拦下）。谁都执行不了，
     *       重读也读不出新东西 → 先等；</li>
     *   <li><b>STALE_VERSION</b>：调用方的快照不是当前决策点，<b>必须先重读</b>。屏是否还认识这个动作
     *       不重要 —— 「屏也换了」正是版本变了的那个意思，所以这一支永远赢过屏幕级判断；</li>
     *   <li><b>ACTION_IN_FLIGHT</b>：版本对、但我们自己上一条动作还在落地；</li>
     *   <li>{@code null}：以上都不成立，交给执行器，由它回 {@code ILLEGAL_ACTION} 之类的屏幕/参数级
     *       拒绝 —— 执行器的屏幕判断只有在「版本号已证实是当前」时才有意义。</li>
     * </ol>
     *
     * <p>纯函数（三个入参 + 一个布尔），优先级表直接单测，不需要游戏。</p>
     *
     * @return the code to answer with, or {@code null} when the action may go on to the executor
     */
    /**
     * Issue #122 · C12-01: the actions that start (or resume) a run - and therefore a load.
     *
     * <p>Both land on a screen that exists only after the dungeon is built. Pure, so the wiring test
     * can pin the exact set without touching the game.</p>
     */
    static boolean startsRunLoad(String type) {
        if (type == null) {
            return false;
        }
        return spirelink.actions.ActionTypes.CONTINUE_RUN.equals(type)
                || spirelink.actions.ActionTypes.EMBARK.equals(type);
    }

    /**
     * Issue #123 · C14-01/02/03: the answer for a submitted run-start action.
     *
     * <p>Why not {@code queued}: the docs tell the agent that {@code queued} means "not landed yet,
     * poll and do not resend" - but after 10 s of silence an agent reads that as "the click did not
     * register" and resends, which then hits {@code ACTION_IN_FLIGHT} (QA observed exactly that). The
     * click <em>was</em> applied, so the answer says {@code applied} with {@code settled:false}: it
     * landed, the world is still moving, and the loading window explains the rest.</p>
     *
     * <p>No fresh plan on purpose - the screen that is on its way out must not be advertised again
     * (that is #123 · S2's rule). {@code ui} is the cached one (request thread must not touch the game
     * while the main thread is inside the load), {@code actions} is empty, and {@code awaiting} names
     * the wait.</p>
     */
    private JsonObject transitionSubmittedPayload(String type, JsonObject args, boolean loading) {
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", true);
        payload.addProperty("outcome", "applied");
        payload.addProperty("settled", false);
        payload.addProperty("state_version", stateBuilder.version());
        payload.add("error", JsonNull.INSTANCE);
        JsonObject action = new JsonObject();
        action.addProperty("type", type);
        action.add("payload", args);
        payload.add("action", action);
        // Issue #124 · C15-01: same ui source as the reads (the cached body still says the old screen).
        payload.addProperty("ui", reportedUiOrCached());
        payload.addProperty("ready", false);
        payload.add("actions", new JsonArray());
        payload.addProperty("awaiting", loading ? "loading" : "transition");
        payload.addProperty("log", loading
                ? "已提交：" + type + " —— 存档正在载入（屏已切走）。不要重发；轮询 get_state，"
                        + "直到 ready:true 且 ui 是新屏（或 state_version 变化）。"
                : "已提交：" + type + " —— 正在离开本局（回主菜单过渡中）。不要重发；"
                        + "轮询 get_state，直到 ui=main_menu 且 ready:true。");
        AgentHud.push(AgentHud.MSG_NOT_READY);
        return payload;
    }

    /**
     * Issue #123 · C14-02: actions whose effect the state signature cannot see, so waiting for a
     * version bump is a guaranteed 10 s timeout rather than a wait for anything.
     */
    static boolean settlesAtSubmission(String type) {
        if (type == null) {
            return false;
        }
        return spirelink.actions.ActionTypes.CHOOSE_CHARACTER.equals(type)
                || spirelink.actions.ActionTypes.CHOOSE_ASCENSION.equals(type);
    }

    /**
     * Issue #122 · C12-01: the window only stays open when the click really happened.
     *
     * <p>Issue #123 · S2: the same for the abandon transition.</p>
     */
    private static void clearSceneWindows(boolean startsRun, boolean startsExit) {
        if (startsRun) {
            spirelink.state.LoadingWindow.clear();
        }
        if (startsExit) {
            spirelink.state.RunExitWindow.clear();
        }
    }

    /**
     * Issue #123 · S2: the actions that take the run away (metrics: the abandonment is a fade + menu
     * rebuild, during which the old dungeon is still in memory).
     */
    static boolean startsRunExit(String type) {
        if (type == null) {
            return false;
        }
        return spirelink.actions.ActionTypes.ABANDON_RUN.equals(type)
                || spirelink.actions.ActionTypes.ABANDON_MID_RUN.equals(type)
                || spirelink.actions.ActionTypes.CONFIRM_ABANDON.equals(type);
    }

    static String preflightRejectionCode(boolean loading, boolean leavingRun, int requestedVersion,
            int currentVersion, boolean actionInFlight) {
        if (loading || leavingRun) {
            // Issue #123 · S2: while the run is being abandoned, "stale" is technically true but the
            // useful answer is "wait, the world you are reading is gone".
            return "NOT_READY";
        }
        if (requestedVersion != currentVersion) {
            return "STALE_VERSION";
        }
        if (actionInFlight) {
            return "ACTION_IN_FLIGHT";
        }
        return null;
    }

    private ToolResult rejectAct(String actionId, String code, String message) {
        // Act-level rejections (STALE_VERSION, in-flight, ...) must refresh hints.last_error too.
        spirelink.util.CrashGuard.noteActionError(code, null);
        StateBuilder.noteRejection();
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("outcome", "rejected");
        payload.addProperty("state_version", stateBuilder.version());
        payload.add("error", error(code, message, stateBuilder.version()));
        // Issue #121 · C12-02: same keys as every other rejection (there is no plan to attach here -
        // these are the payload-level BAD_REQUESTs, caught before anything touched the game).
        attachRejectContext(payload);
        idempotency.put(actionId, payload);
        return new ToolResult(payload, true);
    }

    private JsonObject rejectedPayload(String type, JsonObject args, String code, String message) {
        spirelink.util.CrashGuard.noteActionError(code, type);
        StateBuilder.noteRejection();
        JsonObject payload = new JsonObject();
        payload.addProperty("ok", false);
        payload.addProperty("outcome", "rejected");
        payload.addProperty("state_version", stateBuilder.version());
        payload.add("error", error(code, message, stateBuilder.version()));
        JsonObject action = new JsonObject();
        action.addProperty("type", type);
        action.add("payload", args);
        payload.add("action", action);
        // Issue #121 · C12-02: every rejection answers the same keys (see attachRejectContext); the
        // callers that can afford a main-thread round trip upgrade them with a fresh plan.
        attachRejectContext(payload);
        return payload;
    }

    /**
     * Issue #121 · C12-02: the keys every rejection carries, so the two refusal paths look alike.
     *
     * <p>现场：{@code ACTION_IN_FLIGHT}(BUSY) 的回包只有 error + 三个诊断字段，没有 {@code ui} /
     * {@code actions}；而 {@code ILLEGAL_ACTION} 回包带 {@code ui} / {@code ready} / {@code actions}
     * （它是执行器拒绝的，走 {@link #attachPlan}）。同一个客户端的解析代码于是要写两套。</p>
     *
     * <p>现在所有拒签都由这里兜底：{@code ui} 取缓存快照的（请求线程不许碰游戏对象）、
     * {@code ready:false}（刚拒签，此刻确实没有可执行动作）、{@code actions:[]}。能排到主线程的时候
     * {@link #attachPlan} 会把这些换成**新鲜的** {@code ui}/{@code ready}/{@code state_version}/
     * {@code actions}（{@code STALE_VERSION}／{@code ILLEGAL_ACTION} 就是这样），所以「形状一样、
     * 值更准」——不是两套契约。</p>
     */
    void attachRejectContext(JsonObject payload) {
        // Issue #124 · C15-01: the cached body's ui is the last READY frame ("combat"), while the reads
        // of this very tick report "other" during a transition. Prefer the live value the reads use, so
        // act's rejection cannot describe a different screen than get_state / legal_actions.
        String ui = reportedUiOrCached();
        withRejectContext(payload, ui);
        // Issue #124 · C15-01: the reads carry blocked_ui + awaiting on those frames; a rejection built
        // in the same transitional moment must not answer null / null for the same two questions.
        boolean loading = spirelink.state.LoadingWindow.active();
        boolean leavingRun = spirelink.state.RunExitWindow.active();
        if (payload != null && (loading || leavingRun)) {
            if (!payload.has("blocked_ui") || payload.get("blocked_ui").isJsonNull()) {
                payload.addProperty("blocked_ui", ui);
            }
            if (!payload.has("awaiting") || payload.get("awaiting").isJsonNull()) {
                payload.addProperty("awaiting", loading ? "loading" : "transition");
            }
        }
    }

    /**
     * Issue #124 · C15-01: the ui the <b>reads</b> are reporting right now.
     *
     * <p>{@code StateBuilder.reportedUi()} is written on the main thread every frame (substitution
     * included), so this is the same value {@code get_state.meta.ui} / {@code legal_actions.ui} carry.
     * Falling back to the cached stable body keeps the old behaviour for the very first frames, when
     * nothing has been derived yet.</p>
     */
    String reportedUiOrCached() {
        String reported = spirelink.state.StateBuilder.reportedUi();
        if (reported != null && !reported.trim().isEmpty()) {
            return reported;
        }
        return cachedUi(stateBuilder.cachedView());
    }

    /**
     * The pure half of {@link #attachRejectContext}: fills only what is missing, never overwrites.
     *
     * <p>Kept separate so the shape can be pinned without a game: a rejection built by
     * {@code rejectAct} (no plan) and one built by {@code rejectedPayload} + {@code attachPlan} (fresh
     * plan) must answer the same set of keys.</p>
     *
     * @param ui the ui to report when the payload has none (from the cached snapshot)
     * @return the same object, for chaining
     */
    static JsonObject withRejectContext(JsonObject payload, String ui) {
        if (payload == null) {
            return null;
        }
        if (!payload.has("ui") || payload.get("ui").isJsonNull()) {
            payload.addProperty("ui", ui == null ? "other" : ui);
        }
        if (!payload.has("ready") || payload.get("ready").isJsonNull()) {
            payload.addProperty("ready", false);
        }
        if (!payload.has("actions") || payload.get("actions").isJsonNull()) {
            payload.add("actions", new JsonArray());
        }
        return payload;
    }

    /** Fresh legal actions for STALE_VERSION / ILLEGAL_ACTION answers (best effort). */
    private void attachPlan(JsonObject target) {
        MainThreadQueue.Result plan = queue.call(new MainThreadQueue.Task() {
            @Override
            public Object run() {
                return planPayload();
            }
        }, COMMAND_TIMEOUT_MS);
        if (plan.status == MainThreadQueue.Result.Status.OK && plan.value instanceof JsonObject) {
            JsonObject fresh = (JsonObject) plan.value;
            target.addProperty("ui", fresh.has("ui") ? fresh.get("ui").getAsString() : "other");
            target.addProperty("ready", fresh.has("ready") && fresh.get("ready").getAsBoolean());
            target.addProperty("state_version",
                    fresh.has("state_version") ? fresh.get("state_version").getAsInt() : 0);
            target.add("actions", fresh.has("actions") ? fresh.get("actions") : new JsonArray());
        }
    }

    private static boolean isOk(JsonObject payload) {
        return payload.has("ok") && !payload.get("ok").isJsonNull() && payload.get("ok").getAsBoolean();
    }

    private static boolean boolArg(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()
                || !args.get(key).isJsonPrimitive()) {
            return false;
        }
        try {
            return args.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static JsonObject forceProp(String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "boolean");
        prop.addProperty("description", description);
        return prop;
    }

    private static String strArg(JsonObject args, String key) {
        if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
            return null;
        }
        return args.get(key).getAsString();
    }

    private static Integer intArg(JsonObject args, String key) {
        if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return Integer.valueOf(args.get(key).getAsInt());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Strict variant of {@link #intArg}: a JSON number with an integral value, nothing else.
     *
     * <p>{@code intArg} goes through Gson's {@code getAsInt()}, which happily parses the string
     * {@code "30"} and truncates {@code 30.7} - that is how issue #62 let a string state_version
     * execute an action. This one refuses strings, booleans, nulls, arrays and objects.
     *
     * @return the value, or {@code null} when the argument is missing or not an integer
     */
    static Integer strictIntArg(JsonObject args, String key) {
        if (args == null || !args.has(key) || !isIntegerLiteral(args.get(key))) {
            return null;
        }
        return Integer.valueOf(args.get(key).getAsInt());
    }

    /**
     * True only for a JSON number written as a plain integer literal: {@code 30}, {@code -999}.
     *
     * <p>{@code "30"} (a string), {@code 30.0} / {@code 30.5} (a float spelling), {@code 3e2},
     * {@code true}, {@code null}, arrays and objects are all refused. Being strict about the literal
     * is deliberate: "integer" is what {@code tools/list} promises, and a caller that serialises a
     * version as {@code "30"} is exactly the case issue #62 let through.
     */
    static boolean isIntegerLiteral(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        String raw = element.getAsString().trim();
        if (raw.isEmpty()) {
            return false;
        }
        int start = raw.charAt(0) == '-' || raw.charAt(0) == '+' ? 1 : 0;
        if (start >= raw.length()) {
            return false;
        }
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                // '.', 'e'/'E', 'NaN', 'Infinity' - never an integer literal.
                return false;
            }
        }
        return true;
    }

    /**
     * The whole argument contract of {@code act}, checked before a single game object is touched.
     *
     * <p>Issues #62 / #72: the tool used to accept {@code state_version:"30"} (and execute!), treat
     * an array payload as "no payload" and swallow a null payload as an empty one. Every one of those
     * answered {@code ok:true}, so a client that sent the wrong type believed it had been understood.
     *
     * @return a human-readable BAD_REQUEST message, or {@code null} when the arguments are well typed
     */
    static String actArgumentError(JsonObject arguments) {
        if (arguments == null) {
            return "act 必须带 type 与 state_version（整数）";
        }
        if (arguments.has("type") && !arguments.get("type").isJsonNull()) {
            JsonElement type = arguments.get("type");
            if (!type.isJsonPrimitive() || !type.getAsJsonPrimitive().isString()) {
                return "act.type 必须是字符串（例：\"end_turn\"），收到 " + type
                        + "；type 只能原样取自 legal_actions.actions[].type";
            }
        }
        if (arguments.has("action_id") && !arguments.get("action_id").isJsonNull()) {
            JsonElement actionId = arguments.get("action_id");
            if (!actionId.isJsonPrimitive() || !actionId.getAsJsonPrimitive().isString()) {
                return "act.action_id 必须是字符串（客户端 UUID），收到 " + actionId;
            }
        }
        // An explicit null is a type error too: it is not "absent", and silently treating it as
        // "missing" is what made a wrong call look like a valid one (issue #72).
        if (arguments.has("state_version")
                && !isIntegerLiteral(arguments.get("state_version"))) {
            return "act.state_version 必须是整数（上一次 get_state 返回的那个数），收到 "
                    + arguments.get("state_version")
                    + "；字符串/浮点/null 一律拒绝，不会替你转换";
        }
        if (arguments.has("payload")) {
            JsonElement payload = arguments.get("payload");
            if (payload == null || payload.isJsonNull() || !payload.isJsonObject()) {
                return "act.payload 必须是 JSON 对象（例：{})，收到 " + payload
                        + "；不需要载荷时请直接省略 payload，不要传 null/数组/字符串";
            }
        }
        // Issue #122 · C13-04: a flattened payload is not a payload. Without this the call went to the
        // executor, which answered "payload.uuid 必填" - a sentence about a field the caller never knew
        // it had to create, and never a hint about the shape it actually got wrong.
        if (!arguments.has("payload")) {
            java.util.List<String> stray = strayActArguments(arguments);
            if (!stray.isEmpty()) {
                return flattenedPayloadMessage(stray);
            }
        }
        return null;
    }

    /** The only top-level keys {@code act} accepts; every other key is an action parameter. */
    static final java.util.List<String> ACT_ARGUMENTS =
            java.util.Collections.unmodifiableList(java.util.Arrays.asList(
                    "type", "payload", "state_version", "action_id"));

    /**
     * Issue #122 · C13-04: the top-level keys that are not {@code act}'s own arguments.
     *
     * <p>Non-empty means the caller wrote a flattened payload ({@code {"type":"play_card","uuid":…,
     * "index":0}}) instead of wrapping it ({@code {"type":"play_card","payload":{"uuid":…,
     * "index":0}}}). Pure, so the shape rule is testable without a game.</p>
     */
    static java.util.List<String> strayActArguments(JsonObject arguments) {
        java.util.List<String> stray = new java.util.ArrayList<String>();
        if (arguments == null) {
            return stray;
        }
        for (java.util.Map.Entry<String, JsonElement> entry : arguments.entrySet()) {
            if (!ACT_ARGUMENTS.contains(entry.getKey())) {
                stray.add(entry.getKey());
            }
        }
        java.util.Collections.sort(stray);
        return stray;
    }

    /**
     * Issue #122 · C13-04: tells the caller where those keys belong, with the shape spelled out.
     *
     * <p>Before this the only feedback was the executor's field-level complaint ({@code payload.uuid
     * 必填}), which describes a payload the caller believes it did send.</p>
     */
    static String flattenedPayloadMessage(java.util.List<String> stray) {
        StringBuilder names = new StringBuilder();
        for (String key : stray) {
            if (names.length() > 0) {
                names.append("、");
            }
            names.append(key);
        }
        String first = stray == null || stray.isEmpty() ? "…" : stray.get(0);
        return "act 的顶层只认 type / state_version / payload / action_id 四个字段，但收到了 "
                + names + " —— 这些是动作参数，必须放进 payload 对象里，不能摊平写在顶层。"
                + "正确写法：{\"type\": \"<动作类型>\", \"state_version\": <上次读到的版本>, "
                + "\"payload\": {\"" + first + "\": …}}；"
                + "legal_actions 里 actions[].payload 原样搬进 payload 即可。";
    }

    private JsonObject legalActionsDefinition() {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_LEGAL_ACTIONS);
        tool.addProperty("description",
                "列出当前界面的全部合法动作。返回 ready / state_version / screen_type / ui / actions；"
                        + "每个动作给 id、type、label、payload，payload 原样交给 act。"
                        + "ui 是唯一入口：主菜单、模式面板、选人、教程弹窗、Neow、地图、战斗各有自己的动作集合。");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject actDefinition() {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", TOOL_ACT);
        tool.addProperty("description",
                "执行一个 legal_actions 给出的动作。必须带 type 与 state_version"
                        + "（上一次 get_state/legal_actions 的值）；action_id 可选（给了就幂等：同 id 只执行一次）。"
                        + "返回 outcome 三态：applied（已执行且回到新稳定状态，带 log：一句话说明这一步"
                        + "做了什么以及实测到的数值变化，如「出牌 → 第1只（颚虫）：能量 3→2，手牌 5→4，"
                        + "第1只（颚虫） 28→22」）、"
                        + "queued（已入队但 10 秒内没静止，禁止重试，只能轮询 get_state）、"
                        + "rejected（未执行，ok=false 且带错误码；STALE_VERSION 时附新 actions）。");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        // Issue #81: the action set is finite and lives in ActionTypes - enumerate it from there so
        // the schema can never drift from the executor.
        JsonObject typeProp = enumProp("动作类型（字符串），取自 legal_actions.actions[].type",
                java.util.Arrays.asList(actionTypeNames()));
        properties.add("type", typeProp);
        properties.add("payload", objectProp("动作载荷（JSON 对象），取自 legal_actions.actions[].payload；"
                + "不需要载荷时省略该字段。数组 / 字符串 / null 一律 BAD_REQUEST"));
        properties.add("state_version", intProp("上一次 get_state / legal_actions 返回的 state_version"
                + "（整数：字符串 \"30\"、浮点 30.5、null 一律 BAD_REQUEST，不会替你转换）"));
        properties.add("action_id", stringProp("客户端生成的 UUID，可选但强烈建议：用于幂等"
                + "（同 id 只执行一次，重发不会把同一个动作再做一遍）"));
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("type");
        required.add("state_version");
        schema.add("required", required);
        tool.add("inputSchema", schema);
        return tool;
    }

    private static JsonObject stringProp(String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "string");
        prop.addProperty("description", description);
        return prop;
    }

    /**
     * A string property carrying an {@code enum} (issue #81).
     *
     * <p>The list is always passed in from a live source - {@link #NOTE_KINDS},
     * {@code DevRewardTool.KINDS}, {@code ActionTypes} - never re-typed here: a hand-kept copy is
     * exactly how the schemas drifted from the implementations in the first place.
     */
    private static JsonObject enumProp(String description, List<String> values) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "string");
        prop.addProperty("description", description);
        JsonArray allowed = new JsonArray();
        if (values != null) {
            for (String value : values) {
                allowed.add(value);
            }
        }
        prop.add("enum", allowed);
        return prop;
    }

    /**
     * Every {@code act.type} the executor knows, read straight off
     * {@link spirelink.actions.ActionTypes}'s {@code public static final String} fields.
     *
     * <p>Reflection instead of a copied list: adding an action type must not silently leave the
     * schema advertising a subset (issue #81).
     */
    static String[] actionTypeNames() {
        java.util.TreeSet<String> names = new java.util.TreeSet<String>();
        try {
            for (java.lang.reflect.Field field
                    : spirelink.actions.ActionTypes.class.getDeclaredFields()) {
                if (field.getType() != String.class) {
                    continue;
                }
                int modifiers = field.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(modifiers)
                        || !java.lang.reflect.Modifier.isFinal(modifiers)) {
                    continue;
                }
                Object value = field.get(null);
                if (value instanceof String && !((String) value).isEmpty()) {
                    names.add((String) value);
                }
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("actionTypeNames", t);
        }
        return names.toArray(new String[0]);
    }

    private static JsonObject intProp(String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "integer");
        prop.addProperty("description", description);
        return prop;
    }

    private static JsonObject objectProp(String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "object");
        prop.addProperty("description", description);
        return prop;
    }

    /**
     * Issue #120 · C11-01: rejection diagnostics belong in {@code error}, not only at the top level.
     *
     * <p>现场（QA 第 11 轮）：撞单飞队列的那次 {@code act} 把 {@code queue_status} /
     * {@code waited_ms} / {@code version_advanced} 挂在<b>回包顶层</b>，3 秒墙钟护栏把
     * {@code retry_after_ms} 也挂顶层 —— 按文档从 {@code error.*} 取的人拿到 undefined（QA 的脚本
     * 就是 {@code a.error.queue_status}）。现在两处都给：同一份值镜像进 {@code error}，顶层保留，
     * 老客户端不破。</p>
     *
     * <p>只搬运本回包<b>已经存在</b>的顶层字段，不发明值；没有 {@code error} 对象时什么也不做（永不
     * 抛）。纯函数：一个 JsonObject 进，同一个对象出。</p>
     */
    static void mirrorIntoError(JsonObject payload, String... fields) {
        if (payload == null || fields == null
                || !payload.has("error") || !payload.get("error").isJsonObject()) {
            return;
        }
        JsonObject err = payload.getAsJsonObject("error");
        for (String field : fields) {
            if (field == null || field.isEmpty() || err.has(field)) {
                continue;
            }
            JsonElement value = payload.get(field);
            if (value != null && !value.isJsonNull()) {
                err.add(field, value);
            }
        }
    }

    private static JsonObject error(String code, String message, int stateVersion) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        error.addProperty("state_version", stateVersion);
        return error;
    }

    // ------------------------------------------------------------------ issue #86: one error shape

    /**
     * Rewrites every string-shaped failure into the one documented object shape.
     *
     * <p>Before #86 the failure contract depended on the tool: {@code act} (and {@code get_state} /
     * {@code legal_actions}) answered {@code "error":{"code":…,"message":…}} while
     * {@code note_set}, {@code glossary}, {@code card_info}, {@code event_note} and every
     * {@code dev_*} answered {@code "error":"CODE"} - plus a top-level {@code message} when they
     * bothered. A client had to branch on {@code typeof error} before it could read the code, and the
     * QA harness hit exactly that.
     *
     * <p>Rules:
     * <ul>
     *   <li>a string code becomes {@code {code, message}}, the top-level {@code message} folds in;</li>
     *   <li>a missing message is replaced by {@link #defaultErrorMessage(String)}, so the key is
     *       always present (an agent that only prints the message still says something useful);</li>
     *   <li>a free-text "code" (a raw exception, e.g. {@code "java.io.IOException: …"}) is not a code:
     *       the machine code becomes {@code INTERNAL} and the text moves to the message;</li>
     *   <li>an object error is left exactly as it is (that is already the contract).</li>
     * </ul>
     *
     * @return the same payload, normalised in place (never {@code null} for a null input)
     */
    static JsonObject normalizeError(JsonObject payload) {
        if (payload == null || !payload.has("error")) {
            return payload;
        }
        JsonElement raw = payload.get("error");
        if (raw == null || raw.isJsonNull() || !raw.isJsonPrimitive()
                || !raw.getAsJsonPrimitive().isString()) {
            // Already an object (act / get_state / legal_actions), or "error": null - nothing to do.
            return payload;
        }
        String code = raw.getAsString().trim();
        String message = strArg(payload, "message");
        payload.remove("message");
        if (!isErrorCode(code)) {
            message = message == null || message.trim().isEmpty() ? code : message;
            code = "INTERNAL";
        }
        JsonObject shape = new JsonObject();
        shape.addProperty("code", code);
        shape.addProperty("message", message == null || message.trim().isEmpty()
                ? defaultErrorMessage(code) : message);
        payload.add("error", shape);
        return payload;
    }

    /** True for the machine-readable {@code UPPER_SNAKE} codes every tool is supposed to return. */
    static boolean isErrorCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{1,63}");
    }

    /** The message that goes with a code when the producing branch did not write one (issue #86). */
    static String defaultErrorMessage(String code) {
        if (code == null) {
            return "失败（未提供错误码）";
        }
        if ("BAD_REQUEST".equals(code)) {
            return "参数不合法：字段名/类型见 tools/list 里该工具的 inputSchema";
        }
        if ("NOT_READY".equals(code)) {
            return "游戏这一拍不接受该操作（动画 / 主线程忙 / 界面未就绪），稍后重试";
        }
        if ("GAME_DEAD".equals(code)) {
            return spirelink.state.GameLiveness.DEAD_HINT;
        }
        if ("DEV_TOOL_DISABLED".equals(code)) {
            return "该工具只在测试模式下可用（config.properties 里 test_mode=true）";
        }
        if ("CARD_NOT_FOUND".equals(code)) {
            return "找不到这张牌：uuid / id 不对，或不在当前可见范围";
        }
        if ("CARD_USE_FAILED".equals(code)) {
            return "卡牌 use() 失败，见回包里的 result / reason 字段";
        }
        if ("NOTE_WRITE_FAILED".equals(code)) {
            return "写 notes.json 失败（磁盘满 / 权限不足）";
        }
        if ("TERM_NOT_FOUND".equals(code)) {
            return "术语查不到：用 suggestions 里的近义词，或 glossary{query:\"前缀\"} 模糊搜";
        }
        if ("EVENT_NOT_FOUND".equals(code)) {
            return "找不到这个事件：短名与全类名都没匹配上";
        }
        if ("INTERNAL".equals(code)) {
            return "模组内部错误，细节见 mods/spirelink-data/spirelink-debug.log";
        }
        return "失败：" + code + "（该工具没有提供 message，请按 code 分支）";
    }
}
