package spirelink.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.SeedHelper;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.orbs.AbstractOrb;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.relics.RunicDome;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.stances.AbstractStance;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import spirelink.util.ModLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the {@code get_state} body (design 7, contract v1.0) and caches the last stable frame.
 *
 * <p>Everything here runs on the game main thread (queue drain / frame tick). {@code state_version}
 * is a process-local monotonic counter bumped whenever a new stable state is entered; every bump
 * appends exactly one line to {@code <game dir>/mods/spirelink-data/spirelink.log}:
 * {@code version=<n> ui=<x> hp=<cur/max> hand_uuids=<csv>}, so the smoke test can cross-check the
 * hand.
 *
 * <p>P0 blocks: {@code meta}, {@code player}, {@code potions}, {@code combat}, {@code hints}.
 * {@code screen} / {@code map_here} / {@code gaze} / {@code log_tail} / {@code lesson_hits} land in
 * later milestones.
 */
public final class StateBuilder {

    /** Result of one {@code get_state} read. */
    public static final class Snapshot {
        public final boolean ready;
        public final int version;
        public final JsonObject body;
        /**
         * Issue #69: the game's own loop is gone for good (crash captured + frames frozen), so no
         * waiting state will ever clear. The payload builders use this to answer {@code GAME_DEAD}
         * instead of another "please retry" - see {@code meta.game_dead}, which carries the same fact.
         */
        public final boolean gameDead;

        Snapshot(boolean ready, int version, JsonObject body, boolean gameDead) {
            this.ready = ready;
            this.version = version;
            this.body = body;
            this.gameDead = gameDead;
        }
    }

    private final ModLog log;
    private final String modVersion;

    private int version;
    private String lastSignature;
    /** Issue #75: signature + the parts that only re-render the body (text typing progress). */
    private String lastBodyKey;
    private JsonObject lastStableBody;
    /**
     * Issue #75 round 2: a not-ready frame did <em>not</em> drop the signature any more (that made
     * every gate blip a version bump). This remembers only the one case that must still force the
     * next stable frame to count as a new decision point: the action queue was actually busy, i.e.
     * something the agent asked for was executing.
     */
    private boolean actionSettlePending;
    /** Issue #75: earliest time the cached event block may be rebuilt again (frame-budget guard). */
    private long lastBodyRefreshAt;
    /** 10 Hz is plenty for text that the agent reads: one rebuild costs a glossary scan. */
    static final long BODY_REFRESH_MIN_MS = 100L;

    public StateBuilder(ModLog log, String modVersion) {
        this.log = log;
        this.modVersion = modVersion;
    }

    public synchronized int version() {
        return version;
    }

    /**
     * Drops the cached signature so the next frame rebuilds the snapshot (and bumps the version).
     * Needed when something outside the game state changes what get_state returns - notes for one.
     */
    public synchronized void invalidate() {
        lastSignature = null;
    }

    /** Current derived {@code ui}; main thread only (used by legal_actions). */
    public String currentUiOnMainThread() {
        return deriveUi();
    }

    /**
     * Wall clock of the last real decision change, i.e. the last {@code state_version} bump.
     *
     * <p>Issue #119 · C10-05: this is the state layer's definition of "the world has moved", which is
     * what {@code hints.last_error_fresh} now needs — an error recorded before the last move is
     * history, even while no new {@code act} has been sent (a queued action that finally landed used
     * to keep the old error looking fresh for tens of seconds). Read-only and thread safe: the MCP
     * layer reads it at serve time from the request thread.</p>
     */
    public static long lastDecisionChangeAt() {
        return lastDecisionChangeAt;
    }

    /**
     * Frame tick: bumps {@code state_version} and refreshes the cached stable snapshot when a new
     * stable state is entered. Main thread only.
     */
    private static long lastDumpAt;
    /** Issue #119 · C10-05: when {@code state_version} last really moved (0 = never). */
    private static volatile long lastDecisionChangeAt;

    public synchronized void tickOnMainThread() {
        tick(false);
    }

    /**
     * @param forceBodyRefresh the get_state serve path: the answer the caller is about to read must
     *                         not sit behind the frame-budget throttle.
     */
    private synchronized void tick(boolean forceBodyRefresh) {
        StableGate.Inputs probe = StableGate.probe();
        StableGate.Decision decision = StableGate.evaluate(probe);
        if (!decision.ready) {
            // Freeze diagnostics: while the game is not at a decision point, dump who we are
            // waiting for (rate limited to one line every 4 s).
            long now = System.currentTimeMillis();
            if (now - lastDumpAt > 4000L) {
                lastDumpAt = now;
                log.info("未就绪诊断: reason=" + decision.reason
                        + " probe=" + spirelink.util.RuntimeProbe.dump());
                // An event that refuses readiness is the one case we could not see before: the
                // event-wait line explains whether it is text paging or missing options.
                spirelink.actions.ActionPlanner.logEventWait();
            }
            // Issue #75 round 2: this used to be "lastSignature = null", i.e. every not-ready frame
            // forced the next stable frame to bump the version. QA watched a Cleric screen answer
            // get_state three times with an identical body / options / ready and a version of +1
            // each time: the gate blips for single frames while text animates, and each blip became
            // a "new state". Only a busy action queue means the game really left the decision point
            // because of something the agent asked for - that one still settles into a new decision.
            if (queueBusy(probe)) {
                actionSettlePending = true;
            }
            return;
        }
        String ui = deriveUi(false);
        if ("event".equals(ui)) {
            spirelink.actions.ActionPlanner.logCustomButtonState();
        }
        String signature = computeSignature(ui);
        // Issue #75: not every body change is a decision-point change. The event text typing out is
        // the body moving while the choice stays identical, so it refreshes the cached body but must
        // not move state_version - otherwise "read once, then act" always loses the race to
        // STALE_VERSION (the QA run walked 57 -> 65 in two seconds with the same three actions).
        String bodyKey = signature + "|body=" + spirelink.state.EventPayload.progressToken();
        long now = System.currentTimeMillis();
        boolean settle = actionSettlePending;
        actionSettlePending = false;
        int move = tickDecision(signature, bodyKey, lastSignature, lastBodyKey,
                lastStableBody != null, settle);
        if (move == TICK_NOTHING) {
            return;
        }
        if (move == TICK_REFRESH_BODY) {
            // Only the event block moved. Rebuilding the whole body (memory, notes, run history) on
            // every typed word would be a visible hitch, so replace just that block - and at most ten
            // times a second, because each rebuild cleans and glossary-scans the whole page.
            if (!forceBodyRefresh && now - lastBodyRefreshAt < BODY_REFRESH_MIN_MS) {
                // Leave lastBodyKey alone: the next tick still sees "body moved" and retries.
                return;
            }
            lastBodyRefreshAt = now;
            lastBodyKey = bodyKey;
            patchEventBlock(lastStableBody, ui);
            return;
        }
        version++;
        lastDecisionChangeAt = now;
        lastStableBody = buildBody(true, ui);
        lastSignature = signature;
        lastBodyKey = bodyKey;
        lastBodyRefreshAt = now;
        appendStateLine(ui);
    }

    /** True while the game is executing something (queued actions, a card, a turn transition). */
    static boolean queueBusy(StableGate.Inputs probe) {
        return probe != null && (!probe.actionsEmpty || !probe.currentActionNull
                || !probe.cardQueueEmpty || !probe.preTurnActionsEmpty);
    }

    /** Issue #75: refresh the one block that typing progress changes, without touching the version. */
    private void patchEventBlock(JsonObject body, String ui) {
        if (body == null || !"event".equals(ui)) {
            return;
        }
        try {
            JsonObject event = EventPayload.build();
            if (event != null) {
                body.add("event", event);
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("patchEventBlock", t);
        }
    }

    // ------------------------------------------------------------------ version bump rule (#75)

    /** Nothing the agent can observe moved: keep the cached body as it is. */
    static final int TICK_NOTHING = 0;
    /** The body the agent reads moved, but the decision point did not: refresh it, keep the version. */
    static final int TICK_REFRESH_BODY = 1;
    /** The decision point / key state moved: bump {@code state_version}. */
    static final int TICK_BUMP_VERSION = 2;

    /**
     * What one frame tick should do. Pure so the rule can be pinned without a game: a change in
     * {@code signature} (ui, screen, room, options, hp, hand, action list, ...) always bumps the
     * version; a change only in {@code bodyKey} (text typing / a new story page) refreshes the cached
     * body quietly; nothing changed means do nothing at all.
     *
     * @param settlingAfterActions a not-ready stretch with a busy action queue just ended: the game
     *                             really did leave the decision point and come back, so the agent may
     *                             act again even if nothing visible differs.
     */
    static int tickDecision(String signature, String bodyKey, String lastSignature,
            String lastBodyKey, boolean hasBody, boolean settlingAfterActions) {
        // No body yet, or the signature was dropped on purpose (notes changed): the next stable frame
        // must move the version too, even if the body would look identical.
        if (!hasBody || lastSignature == null || signature == null
                || !signature.equals(lastSignature)) {
            return TICK_BUMP_VERSION;
        }
        if (settlingAfterActions) {
            return TICK_BUMP_VERSION;
        }
        if (bodyKey != null && bodyKey.equals(lastBodyKey)) {
            return TICK_NOTHING;
        }
        return TICK_REFRESH_BODY;
    }

    /**
     * {@code get_state} read. Main thread only (it is queued like every other command).
     *
     * @return fresh snapshot when the game is at rest, otherwise the last stable snapshot marked
     *         {@code meta.ready == false}.
     */
    public synchronized Snapshot view() {
        return viewWith(null, null);
    }

    /**
     * {@code get_state} with a block whitelist / blacklist.
     *
     * <p>{@code include} makes the response "meta + exactly these blocks"; {@code exclude} removes
     * blocks from the default set. Filtering happens on a copy, so the cached stable body is never
     * mutated and the next call can ask for something else.
     */
    public synchronized Snapshot viewWith(JsonArray include, JsonArray exclude) {
        java.util.Set<String> inc = namesOf(include);
        java.util.Set<String> exc = namesOf(exclude);
        includeMode = inc != null && !inc.isEmpty();
        filtersInclude = inc;
        try {
            return viewInternal(inc, exc);
        } finally {
            includeMode = false;
            filtersInclude = null;
        }
    }

    private synchronized Snapshot viewInternal(java.util.Set<String> inc, java.util.Set<String> exc) {
        // Issue #55/#69: liveness is re-read on EVERY serve, not only when a body is rebuilt. The
        // cached body was built before the crash, so its render_crashed was frozen at false - which
        // is exactly the "it says retry, but the game is gone" trap the agent fell into.
        GameLiveness.Verdict liveness = GameLiveness.sample();
        // Issue #122 · C12-01: hand the ui of THIS frame to the load window before the gate reads it,
        // so the very frame the run screen appears is already "not loading" (otherwise the window
        // would lag one poll behind and answer「存档正在载入」with a combat screen on screen).
        LoadingWindow.noteUi(deriveUi(false));
        StableGate.Decision decision = StableGate.evaluate(StableGate.probe());
        if (decision.actionsStuck && !stuckWarned) {
            stuckWarned = true;
            log.warn("动作队列卡住（" + decision.reason + "）：渲染仍在跑，get_state 仍返回快照，"
                    + "但 act 不会生效。dev 工具换房间时请先 RoomGuard.clearActions()。");
        } else if (!decision.actionsStuck) {
            stuckWarned = false;
        }
        if (decision.ready) {
            // forceBodyRefresh: the caller is about to read this body, so do not let the frame-budget
            // throttle hand back a body that is 100 ms behind the animation.
            tick(true);
            String ui = deriveUi();
            boolean fromCache = lastStableBody != null;
            JsonObject body = fromCache
                    ? copy(lastStableBody)
                    : buildBody(true, ui);
            if (inc != null && !inc.isEmpty() && !coversInclude(body, inc)) {
                // A cached body may predate the request (e.g. it was built outside a map screen).
                body = buildBody(true, ui);
                fromCache = false;
            }
            filterBlocks(body, ui, true, inc, exc);
            if (fromCache) {
                // Issue #127: a served copy must re-run the note pass, otherwise it keeps echoing
                // the "new" text of the frame that built it (a static screen polled 250 times
                // repeated the same notes 250 times).
                refreshNotes(body);
            }
            writeLiveness(body, liveness);
            return new Snapshot(true, version, body, liveness.dead);
        }
        if (lastStableBody != null) {
            JsonObject body = copy(lastStableBody);
            refreshNotes(body);
            JsonObject meta = body.getAsJsonObject("meta");
            if (meta != null) {
                meta.addProperty("ready", false);
                meta.addProperty("state_version", version);
                // Recompute the ui: the stale value would claim "combat" while a card-select
                // screen is actually waiting for the player.
                meta.addProperty("ui", deriveUi(false));
                meta.addProperty("awaiting", awaitingOf(decision.reason));
                if (decision.actionsStuck) {
                    // Read-only but not blind: the agent can see it is wedged instead of guessing.
                    meta.addProperty("actions_stuck", true);
                    meta.addProperty("actions_stuck_reason", decision.reason);
                }
            }
            JsonObject hints = body.getAsJsonObject("hints");
            if (hints != null) {
                fillProbeHints(hints, body.getAsJsonObject("meta").get("ui").getAsString());
            }
            filterBlocks(body, body.getAsJsonObject("meta").get("ui").getAsString(), false,
                    inc, exc);
            writeLiveness(body, liveness);
            return new Snapshot(false, version, body, liveness.dead);
        }
        JsonObject fresh = buildBody(false, deriveUi());
        filterBlocks(fresh, deriveUi(), false, inc, exc);
        writeLiveness(fresh, liveness);
        return new Snapshot(false, version, fresh, liveness.dead);
    }

    // ------------------------------------------------------------------ size: block whitelist

    /** {@code true} while a {@code get_state{include:[...]}} request is being served. */
    private boolean includeMode;
    private java.util.Set<String> filtersInclude;
    /** Set by {@link #noteRejection()}; cleared by the next accepted action. */
    private static volatile long rejectedAtMs;
    /** Run + act for which the briefing was last delivered. */
    private static String briefingKey;
    /** Screen for which the briefing was last delivered (main menu / death / …). */
    private static String briefingUi;

    /** Called by the executor when an action could not be applied. */
    public static void noteRejection() {
        rejectedAtMs = System.currentTimeMillis();
    }

    /** Called when an action was applied, so hints stop being pushed. */
    public static void noteAcceptance() {
        rejectedAtMs = 0L;
    }

    private static java.util.Set<String> namesOf(JsonArray array) {
        if (array == null || array.size() == 0) {
            return null;
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i) != null && !array.get(i).isJsonNull()) {
                out.add(array.get(i).getAsString());
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Names answered outside {@code buildBody}: {@code deck}/{@code *&#95;pile} are attached by
     * ToolCatalog after filtering, {@code briefing} lives inside {@code memory}. Requesting one must
     * neither force a rebuild nor invent a placeholder key (issue #58).
     */
    private static final java.util.Set<String> VIRTUAL_BLOCKS = virtualBlocks();

    /** Every name a caller may legitimately put in {@code include}/{@code exclude} (issue #58). */
    private static final java.util.Set<String> KNOWN_BLOCKS = knownBlocks();

    private static java.util.Set<String> virtualBlocks() {
        java.util.Set<String> names = new java.util.LinkedHashSet<String>();
        names.add("deck");
        names.add("discard_pile");
        names.add("exhaust_pile");
        names.add("draw_pile");
        names.add("briefing");
        return java.util.Collections.unmodifiableSet(names);
    }

    private static java.util.Set<String> knownBlocks() {
        java.util.Set<String> names = new java.util.LinkedHashSet<String>();
        // One entry per top-level key buildBody() can produce.
        names.add("meta");
        names.add("player");
        names.add("potions");
        names.add("boss_reward");
        names.add("rewards");
        names.add("map");
        names.add("card_reward");
        names.add("shop");
        names.add("combat");
        names.add("hints");
        names.add("event");
        names.add("neow");
        names.add("char_select");
        names.add("match_game");
        names.add("postmortem");
        names.add("memory");
        names.addAll(VIRTUAL_BLOCKS);
        return java.util.Collections.unmodifiableSet(names);
    }

    /** True when the cached body already contains everything the include list asks for. */
    private static boolean coversInclude(JsonObject body, java.util.Set<String> inc) {
        for (String block : inc) {
            // Unknown / virtual names are never in the body: they must not force a rebuild.
            if (!KNOWN_BLOCKS.contains(block) || VIRTUAL_BLOCKS.contains(block)) {
                continue;
            }
            if (!body.has(block)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Drops blocks the caller does not need.
     *
     * <p>Default set = what the current screen can act on. {@code include} replaces it with
     * "meta + exactly these"; {@code exclude} removes from the default set. Null / empty optional
     * blocks are dropped too: 12 permanent null keys cost ~120 characters per call.
     */
    void filterBlocks(JsonObject body, String ui, boolean ready,
            java.util.Set<String> inc, java.util.Set<String> exc) {
        java.util.Set<String> allowed = new java.util.LinkedHashSet<String>();
        if (inc != null && !inc.isEmpty()) {
            // Issue #58a: only real block names may reach the response. Unknown names used to be
            // re-added as a placeholder at the bottom of this method, so include:["nosuchblock"]
            // invented a "nosuchblock": {} key out of the caller's own input. Ignored, not an error:
            // see the note on the re-add loop below.
            for (String block : inc) {
                if (KNOWN_BLOCKS.contains(block)) {
                    allowed.add(block);
                }
            }
            allowed.add("meta");
        } else {
            allowed.add("meta");
            allowed.add("player");
            allowed.add("potions");
            allowed.add("memory");
            allowed.add("event");
            allowed.add("combat");
            allowed.add("match_game");
            if ("map".equals(ui)) {
                allowed.add("map");
            }
            if ("shop".equals(ui)) {
                allowed.add("shop");
            }
            if ("card_reward".equals(ui) || "grid".equals(ui) || "hand_select".equals(ui)) {
                allowed.add("card_reward");
            }
            if ("combat_reward".equals(ui) || "rewards".equals(ui) || "treasure".equals(ui)) {
                allowed.add("rewards");
                allowed.add("boss_reward");
                allowed.add("card_reward");
            }
            if ("boss_reward".equals(ui)) {
                allowed.add("boss_reward");
            }
            if ("char_select".equals(ui)) {
                allowed.add("char_select");
            }
            if ("neow".equals(ui)) {
                allowed.add("neow");
            }
            if ("death".equals(ui) || "victory".equals(ui)) {
                allowed.add("postmortem");
                allowed.add("rewards");
            }
        }
        // "hints" is not in the default set above: it is only delivered when it explains something
        // (not ready, a rejected action, the character select screen, or a marker that says this
        // "ready" frame is abnormal — issue #100 item 4 / C6-04). Without this line the remove loop
        // below deleted it first, so a ready response only ever carried the liveness object
        // ToolCatalog appends afterwards — and hints.char_selected/next never showed up (#49).
        if (wantHints(ui, ready, hintsBlockOf(body))) {
            allowed.add("hints");
        }
        if (exc != null && !exc.isEmpty()) {
            // exclude wins: it runs after the allow-list is complete, so exclude:["hints"] still
            // removes the block even on a screen that always carries it.
            for (String block : exc) {
                // Issue #58b: meta is the one block that is never optional - "include:[meta] +
                // exclude:[meta]" used to answer "meta": null and the caller lost ui/state_version.
                // meta.ready/state_version are how an agent knows whether its state_version is
                // still valid, so an empty response is not a legal answer.
                if (!"meta".equals(block)) {
                    allowed.remove(block);
                }
            }
        }
        java.util.List<String> present = new java.util.ArrayList<String>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : body.entrySet()) {
            present.add(entry.getKey());
        }
        for (String key : present) {
            if (!allowed.contains(key)) {
                body.remove(key);
            }
        }
        // Optional blocks that are present but empty cost characters and say nothing.
        for (String key : new String[] {"event", "combat", "rewards", "boss_reward", "card_reward",
                "shop", "neow", "char_select", "match_game", "postmortem"}) {
            if (body.has(key) && isEmptyBlock(body.get(key))) {
                body.remove(key);
            }
        }
        // An explicitly requested block must still answer (even when empty), otherwise the caller
        // cannot tell "nothing here" from "the mod ignored my include".
        //
        // Issue #58a: unknown names are IGNORED rather than rejected. Reasons: (1) include is a
        // "give me exactly these" list, and a name the mod does not know cannot be answered
        // truthfully, so null/{} placeholders are what corrupted the contract; (2) BAD_REQUEST would
        // require the error code to be raised in ToolCatalog, i.e. the block filter would have to
        // reach into the transport layer; (3) old harnesses build include lists from strings they
        // remember, and a hard failure would break them on a typo instead of degrading to meta only.
        // The legal names are documented in docs/MCP-返回格式.md.
        if (inc != null) {
            for (String block : inc) {
                if (!KNOWN_BLOCKS.contains(block) || VIRTUAL_BLOCKS.contains(block)) {
                    continue;
                }
                if (exc != null && exc.contains(block)) {
                    // "exclude wins" (see the allow-list note above): naming the same block in both
                    // lists must mean "not this one", not "give me an empty placeholder for it".
                    continue;
                }
                if (!body.has(block)) {
                    body.add(block, new JsonObject());
                }
            }
        }
        // hints are already decided by the allow-list above (wantHints gates allowed.add("hints")).
        JsonObject memory = body.has("memory") && body.get("memory").isJsonObject()
                ? body.getAsJsonObject("memory") : null;
        if (memory != null && !wantBriefing(ui, inc)) {
            // briefing + run_history are *context*, not state: both are identical on every frame of a
            // run, so they travel with the briefing instead of with every call. The count stays, so
            // the agent still knows history exists and can ask include:["memory"].
            memory.remove("briefing");
            memory.remove("run_history");
        }
    }

    private static boolean isEmptyBlock(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return true;
        }
        if (element.isJsonArray()) {
            return element.getAsJsonArray().size() == 0;
        }
        if (element.isJsonObject()) {
            return element.getAsJsonObject().entrySet().isEmpty();
        }
        return false;
    }

    /**
     * Hints travel with every call only when they explain something (not ready, or the last action
     * was rejected). Character select is the one exception: {@code hints.char_selected} / {@code
     * hints.next} answer "is the pick already made?", which is exactly what the agent guessed wrong
     * in issue #49, so they stay even on a ready screen.
     *
     * <p>Issue #100 item 4 / C6-04: "ready" is not the same as "nothing to say". A map whose {@code
     * map.reachable} is empty is a decision point by the gate (the player can sit there forever) but
     * it has no move that advances the run, and {@code hints.map_no_reachable} is the only block that
     * says so — QA only ever saw it through an explicit {@code include:["hints"]}. Same shape on
     * {@code ui=victory}/{@code death}: the postmortem's {@code hints.suggest_note} is documented to
     * arrive "同一拍", but those screens are ready, so the block was filtered away before the agent
     * could read it (C6-04). Both are carried by {@link #SELF_EXPLAINING_HINTS}.
     */
    private static boolean wantHints(String ui, boolean ready, JsonObject hints) {
        return !ready || rejectedAtMs > 0L || "char_select".equals(ui) || explainsSomething(hints);
    }

    /**
     * Hint markers that make a <em>ready</em> frame worth a hints block. Kept short on purpose: every
     * entry here is a promise that the block appears on that screen, and the block is ~15 keys.
     *
     * <ul>
     *   <li>{@code map_no_reachable} — ready, but there is no move that advances the run (#100).</li>
     *   <li>{@code suggest_note} — the "write this down" line the postmortem contract promises on the
     *       same frame as {@code postmortem} (C6-04).</li>
     *   <li>{@code unknown_ui} — invariant I2: the mod does not know this screen, the agent must.</li>
     *   <li>{@code event_missing} — an event room with no readable event: "the event is gone, use
     *       proceed", not "the read failed".</li>
     * </ul>
     */
    private static final java.util.Set<String> SELF_EXPLAINING_HINTS = selfExplainingHints();

    private static java.util.Set<String> selfExplainingHints() {
        java.util.Set<String> names = new java.util.LinkedHashSet<String>();
        names.add("map_no_reachable");
        names.add("suggest_note");
        names.add("notes_pending");
        names.add("first_encounter");
        names.add("unknown_ui");
        names.add("event_missing");
        return java.util.Collections.unmodifiableSet(names);
    }

    /** The {@code hints} object of a body, or null when the block is absent / not an object. */
    private static JsonObject hintsBlockOf(JsonObject body) {
        if (body == null || !body.has("hints") || !body.get("hints").isJsonObject()) {
            return null;
        }
        return body.getAsJsonObject("hints");
    }

    /** True when the built hints carry a marker that explains an abnormal-but-ready frame. */
    static boolean explainsSomething(JsonObject hints) {
        if (hints == null) {
            return false;
        }
        for (String key : SELF_EXPLAINING_HINTS) {
            if (hints.has(key) && !hints.get(key).isJsonNull()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The briefing is context, not state: send it on a new run, on a new act, and once for the
     * screens where a fresh context matters (menu / character select / death / victory).
     */
    private static boolean wantBriefing(String ui, java.util.Set<String> inc) {
        if (inc != null && inc.contains("briefing")) {
            return true;
        }
        String key = currentRunId() + "|" + currentAct();
        boolean special = "main_menu".equals(ui) || "char_select".equals(ui)
                || "death".equals(ui) || "victory".equals(ui) || "neow".equals(ui);
        if (special) {
            if (!ui.equals(briefingUi)) {
                briefingUi = ui;
                briefingKey = key;
                return true;
            }
            return false;
        }
        briefingUi = null;
        if (!key.equals(briefingKey)) {
            briefingKey = key;
            return true;
        }
        return false;
    }

    private static String currentAct() {
        try {
            return String.valueOf(AbstractDungeon.actNum);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** Finite vocabulary for {@code meta.awaiting} (design 7.2): never empty when not ready. */
    static String awaitingOf(String reason) {
        if (reason == null) {
            return "unknown";
        }
        switch (reason) {
            case "idle":
            case "menu":
                return "playing";
            case "screen_choice":
            case "popup":
            case "blocking_screen":
                return "screen_up";
            case "event_text_typing":
                return "event_text_typing";
            case "battle_over":
                return "battle_over";
            // Issue #121 · C12-01: a save is loading - "transition" would be read as an animation that
            // ends by itself, so the vocabulary says what it is. It is still a wait, not a decision point.
            case "loading":
                return "loading";
            // Issue #123 · S2: the run is being abandoned; the menu is not up yet, the dungeon is gone.
            case "leaving_run":
            // Issue #125 · C16-04: the combat room is up but its first turn has not been set up yet.
            case "combat_settling":
            case "fading":
            case "transition":
            case "dungeon_beaten":
                return "transition";
            case "actions_running":
            case "card_queue":
            case "preturn_actions":
                return playingSide() ? "animating" : "enemy_turn";
            default:
                // Never answer "unknown" when the engine can tell us: a live end-turn button means
                // it is the player's turn, otherwise we are waiting on the game.
                if (playingSide()) {
                    return "playing";
                }
                return screenIsUp() ? "screen_up" : "enemy_turn";
        }
    }

    /**
     * {@code meta.awaiting} for one screen (design 7.2 + issue #49).
     *
     * <p>{@code char_select} is not "playing": no run is loaded and the only way forward is to pick
     * a character and embark. Reporting "playing" there was the same class of lie as {@code
     * ui=death} reporting "playing". While the screen fades out into the run the real wait wins, so
     * "transition" is not masked.
     */
    static String awaitingFor(String ui, boolean ready, String gateReason) {
        if (!ready) {
            return awaitingOf(gateReason);
        }
        if ("char_select".equals(ui)) {
            return "char_select";
        }
        return "death".equals(ui) ? "dead" : "playing";
    }

    private static boolean screenIsUp() {
        try {
            return AbstractDungeon.isScreenUp;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Issue #119 · C10-06: the GremlinMatchGame block as the agent may see it.
     *
     * <p>{@code attempts} and {@code flip_stalled_ms} describe the <b>board</b>, so they only mean
     * something once the board accepts flips ({@code screen=PLAY}). On the INTRO / RULE_EXPLANATION
     * pages nothing is flipping yet: {@code flip_stalled_ms} grew from the moment the minigame was
     * mounted (reading as "the board is stuck") and {@code attempts} was a leftover 0. Both are
     * dropped there; {@code screen} / {@code cards} / {@code matched} still describe what is on
     * screen, which is how the agent knows flips are not accepted yet.</p>
     *
     * <p>{@code spirelink.events.MatchGame.state()} keeps the raw values (the events layer owns the
     * minigame); only the payload the agent reads is trimmed, and it is trimmed for both copies —
     * {@code match_game} and {@code event.match_game} — because they are the same object.</p>
     */
    static JsonObject matchGameForAgent(JsonObject raw) {
        if (raw == null) {
            return null;
        }
        String screen = raw.has("screen") && !raw.get("screen").isJsonNull()
                ? raw.get("screen").getAsString() : "";
        if ("PLAY".equals(screen)) {
            return raw;
        }
        JsonObject out = new JsonObject();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : raw.entrySet()) {
            if ("attempts".equals(entry.getKey()) || "flip_stalled_ms".equals(entry.getKey())) {
                continue;
            }
            out.add(entry.getKey(), entry.getValue());
        }
        return out;
    }

    /** True when the vanilla end-turn button is live, i.e. it is the player's turn. */
    private static boolean playingSide() {
        try {
            return spirelink.actions.ActionPlanner.endTurnAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Screen/queue facts an agent needs to tell "wait for animation" from "wait for me". */
    private static void fillProbeHints(JsonObject hints, String ui) {
        fillProbeHints(hints, ui, null);
    }

    /**
     * @param mapProbe the map read already taken for this payload, or null to take one right here
     *                 (the not-ready path decorates a cached body, so there is no build to share).
     */
    private static void fillProbeHints(JsonObject hints, String ui, MapProbe mapProbe) {
        try {
            hints.addProperty("act_seq", spirelink.util.CrashGuard.actionSeq());
            hints.addProperty("last_error_seq", spirelink.util.CrashGuard.lastErrorSeq());
            hints.addProperty("screen", AbstractDungeon.screen == null ? "NONE"
                    : AbstractDungeon.screen.name());
            String lastError = spirelink.util.CrashGuard.lastError();
            if (lastError != null) {
                hints.addProperty("last_error", lastError);
                long errorAt = spirelink.util.CrashGuard.lastErrorAt();
                hints.addProperty("last_error_age_ms", System.currentTimeMillis() - errorAt);
                // Issue #119 · C10-05: "fresh" = this is the error from the act you just sent, it came
                // from an action (not from a read that answered NOT_READY), and nothing has moved
                // since (a queued action that finally landed makes the old error history).
                hints.addProperty("last_error_fresh", ErrorFreshness.isFreshFor(
                        spirelink.util.CrashGuard.lastErrorSeq(),
                        spirelink.util.CrashGuard.actionSeq(), lastError, errorAt,
                        lastDecisionChangeAt));
            }
            // Lets a caller verify "choose actually selected something" without guessing.
            // Grid state is only meaningful on the grid screen; leftover values used to leak onto the
            // shop/map screens and made the agent think a selection was pending.
            com.megacrit.cardcrawl.screens.select.GridCardSelectScreen grid =
                    "grid".equals(ui) ? AbstractDungeon.gridSelectScreen : null;
            if (grid != null) {
                int selected = grid.selectedCards == null ? -1 : grid.selectedCards.size();
                hints.addProperty("grid_selected", selected);
                Object numCards = spirelink.util.FieldKit.fieldOrNull(grid, "numCards");
                if (numCards instanceof Number) {
                    hints.addProperty("grid_num", ((Number) numCards).intValue());
                }
                int wanted = -1;
                Object numToSelect = spirelink.util.FieldKit.fieldOrNull(grid, "numCardsToSelect");
                if (numToSelect instanceof Number) {
                    wanted = ((Number) numToSelect).intValue();
                    hints.addProperty("grid_num_to_select", wanted);
                }
                if (wanted < 0 && numCards instanceof Number) {
                    wanted = ((Number) numCards).intValue();
                }
                hints.addProperty("grid_wanted", wanted);
                // The upgrade grid never fills selectedCards: it only sets upgradePreviewCard, so
                // "selected" alone misled the agent. can_confirm is the honest readiness signal.
                Object preview = spirelink.util.FieldKit.fieldOrNull(grid, "upgradePreviewCard");
                String previewId = preview instanceof com.megacrit.cardcrawl.cards.AbstractCard
                        ? ((com.megacrit.cardcrawl.cards.AbstractCard) preview).cardID : null;
                boolean confirmUp = Boolean.TRUE.equals(
                        spirelink.util.FieldKit.fieldOrNull(grid, "confirmScreenUp"));
                // Mirror the planner's rule exactly: a visible/enabled confirm button, the
                // "just for confirming" grids, or a full selection all mean the agent may confirm.
                boolean justForConfirming = Boolean.TRUE.equals(
                        spirelink.util.FieldKit.fieldOrNull(grid, "isJustForConfirming"));
                Object confirmButton = spirelink.util.FieldKit.fieldOrNull(grid, "confirmButton");
                boolean buttonUsable = false;
                if (confirmButton != null) {
                    boolean disabled = Boolean.TRUE.equals(
                            spirelink.util.FieldKit.fieldOrNull(confirmButton, "isDisabled"));
                    boolean hidden = Boolean.TRUE.equals(
                            spirelink.util.FieldKit.fieldOrNull(confirmButton, "isHidden"));
                    buttonUsable = !disabled && !hidden;
                }
                hints.addProperty("grid_can_confirm",
                        (confirmUp && (previewId != null || (wanted >= 0 && selected >= wanted)))
                                || justForConfirming || buttonUsable
                                || previewId != null || selected > 0);
                if (previewId == null) {
                    hints.add("grid_preview", JsonNull.INSTANCE);
                } else {
                    hints.addProperty("grid_preview", previewId);
                }
            }
            try {
                if (safeRoom() != null
                        && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP) {
                    // #100: the same read feeds buildMap(), so map.reachable and this marker can no
                    // longer contradict each other inside one payload (以前末行有 Boss 可进时，
                    // hints 说「无可达点」而 map.reachable 里躺着 Boss 坐标)。
                    MapProbe probe = mapProbe == null ? MapProbe.probe() : mapProbe;
                    if (probe != null && probe.noWayForward()) {
                        hints.addProperty("map_no_reachable", true);
                    }
                }
            } catch (Throwable ignored) {
                // diagnostics only
            }
            hints.addProperty("gam_phase", AbstractDungeon.actionManager == null
                    ? "null" : String.valueOf(AbstractDungeon.actionManager.phase));
            long frames = spirelink.util.RuntimeProbe.frames();
            hints.addProperty("frames", frames);
            // Stall detector: MCP stays alive while the render/update loop dies, and that is exactly
            // what a bad UI write looks like. Tell the agent instead of letting it poll forever.
            if (frames != lastFrameCount) {
                lastFrameCount = frames;
                lastFrameAt = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastFrameAt > 5000L) {
                hints.addProperty("frames_stalled", true);
                hints.addProperty("frames_stalled_ms", System.currentTimeMillis() - lastFrameAt);
            }
            hints.addProperty("end_turn_enabled", AbstractDungeon.overlayMenu != null
                    && AbstractDungeon.overlayMenu.endTurnButton != null
                    && AbstractDungeon.overlayMenu.endTurnButton.enabled);
            com.megacrit.cardcrawl.monsters.MonsterGroup group =
                    safeRoom() == null ? null : AbstractDungeon.getMonsters();
            hints.addProperty("monsters_basically_dead",
                    group != null && group.areMonstersBasicallyDead());
        } catch (Throwable ignored) {
            // hints are best effort
        }
    }

    /**
     * Thread-safe read of the cached snapshot for callers that must not touch the game (e.g. the
     * HTTP thread after a timeout). Never reads {@code AbstractDungeon}.
     *
     * <p>Issue #55/#69: this is the answer a dead game gets, so it carries a freshly sampled
     * liveness verdict. Without it the "please retry" payloads kept replaying a {@code
     * render_crashed:false} that was frozen before the crash.
     */
    public synchronized Snapshot cachedView() {
        return snapshotOf(lastStableBody, version, GameLiveness.sample());
    }

    /**
     * Body of {@link #cachedView()} with the verdict passed in: package-visible so the serve-time
     * liveness flags can be unit-tested with an injected verdict (the live sampler would need a
     * running game). Reads no game state of its own - the shell body only uses the null-player path.
     */
    Snapshot snapshotOf(JsonObject stableBody, int version, GameLiveness.Verdict verdict) {
        boolean dead = verdict != null && verdict.dead;
        if (stableBody != null) {
            JsonObject body = copy(stableBody);
            JsonObject meta = body.getAsJsonObject("meta");
            if (meta != null) {
                meta.addProperty("ready", false);
                meta.addProperty("state_version", version);
            }
            writeLiveness(body, verdict);
            return new Snapshot(false, version, body, dead);
        }
        JsonObject empty = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("ready", false);
        meta.addProperty("state_version", version);
        meta.addProperty("in_game", false);
        meta.addProperty("ui", "main_menu");
        empty.add("meta", meta);
        JsonObject hints = new JsonObject();
        hints.add("keywords_unseen", new JsonArray());
        hints.add("needs_inspect", new JsonArray());
        empty.add("hints", hints);
        // Design 7.1/7.5: blocks are always present. Without these the caller sees player/potions as
        // null and cannot tell "empty belt" from "block missing".
        empty.add("player", new JsonObject());
        empty.add("potions", buildPotions(null));
        empty.add("combat", JsonNull.INSTANCE);
        writeLiveness(empty, verdict);
        return new Snapshot(false, version, empty, dead);
    }

    // ---------------------------------------------------------------- liveness (issues #55/#69)

    /**
     * Writes the crash / death facts into one {@code meta}.
     *
     * <p>{@code render_crashed} stays what the docs already promise - "the render loop has thrown" -
     * so it is sticky and must never be reported as false once a crash was captured. {@code
     * game_dead} is the terminal verdict: crash captured <em>and</em> the frame counter stopped, so
     * no amount of waiting will help and the only way out is a restart by the human.
     */
    static void writeLivenessMeta(JsonObject meta, GameLiveness.Verdict verdict) {
        if (meta == null || verdict == null) {
            return;
        }
        meta.addProperty("render_crashed", verdict.renderCrashed);
        if (verdict.renderCrashed && verdict.crash != null) {
            // The agent must know the picture is gone even though the MCP still answers.
            meta.addProperty("crash", verdict.crash);
        } else {
            meta.remove("crash");
        }
        meta.addProperty("game_dead", verdict.dead);
        if (verdict.dead) {
            meta.addProperty("dead_for_ms", verdict.stalledMs);
            meta.addProperty("dead_hint", GameLiveness.DEAD_HINT);
        } else {
            // Never leave a stale terminal signal behind if the loop came back (render exception
            // caught by vanilla, window was only paused, ...).
            meta.remove("dead_for_ms");
            meta.remove("dead_hint");
        }
    }

    /** Same facts for a whole body: {@code meta} plus the liveness hints the agent polls. */
    static void writeLiveness(JsonObject body, GameLiveness.Verdict verdict) {
        if (body == null || verdict == null) {
            return;
        }
        JsonObject meta = body.has("meta") && body.get("meta").isJsonObject()
                ? body.getAsJsonObject("meta") : null;
        if (meta == null) {
            meta = new JsonObject();
            body.add("meta", meta);
        }
        writeLivenessMeta(meta, verdict);
        JsonObject hints = body.has("hints") && body.get("hints").isJsonObject()
                ? body.getAsJsonObject("hints") : null;
        if (hints == null) {
            // hints are optional (filtered out on a quiet ready screen); never invent the block here.
            return;
        }
        if (verdict.stalledMs >= GameLiveness.STALL_MS) {
            hints.addProperty("frames_stalled", true);
            hints.addProperty("frames_stalled_ms", verdict.stalledMs);
        }
        if (verdict.dead) {
            hints.addProperty("game_dead", true);
        }
    }

    // ---------------------------------------------------------------- body blocks

    private JsonObject buildBody(boolean ready, String ui) {
        AbstractPlayer player = AbstractDungeon.player;
        JsonObject root = new JsonObject();
        root.add("meta", buildMeta(ready, ui));
        // Design 7.1: the block is always present; outside a run its content is an empty object.
        root.add("player", player == null ? new JsonObject() : buildPlayer(player));
        root.add("potions", buildPotions(player));
        // Boss relic pick: without this the agent chose blind (only "选遗物 #0" labels).
        JsonArray bossReward = buildBossReward();
        root.add("boss_reward", bossReward == null ? JsonNull.INSTANCE : bossReward);
        // Reward screen contents: the agent must know what is on offer to decide whether to skip.
        root.add("rewards", buildRewards());
        // Issue #100: one map read per payload. fillProbeHints (hints.map_no_reachable) and buildMap
        // (map.reachable) each used to call ActionPlanner.reachableNodes(): two full map scans per
        // body, two identical mapDump WARN lines, and two answers that could contradict each other.
        MapProbe mapProbe = MapProbe.probe();
        root.add("map", buildMap(mapProbe));
        root.add("card_reward", buildCardReward());
        root.add("shop", buildShop());
        JsonObject combat = buildCombat();
        root.add("combat", combat == null ? JsonNull.INSTANCE : combat);
        // Design 7.11: empty collections are still arrays, never omitted.
        JsonObject hints = new JsonObject();
        hints.add("keywords_unseen", new JsonArray());
        hints.add("needs_inspect", new JsonArray());
        fillProbeHints(hints, ui, mapProbe);
        if ("combat_reward".equals(ui) && spirelink.actions.ActionPlanner.potionSlotsFull()) {
            // Explains why no potion reward is offered on this screen.
            hints.addProperty("potions_full", true);
        }
        if (ui != null && ui.startsWith("unknown:")) {
            hints.addProperty("unknown_ui", true);
        }
        root.add("hints", hints);
        // Event text + options (null outside an event room), then everyone's notes.
        JsonObject event = EventPayload.build();
        if (event == null && "event".equals(ui)) {
            // Empty event room (event finished but room not left yet): say it is not a read failure
            // and that the exit is proceed.
            hints.addProperty("event_missing", true);
        }
        if (event == null && "neow".equals(ui)) {
            // Neow's blessings are dialog options, not an ImageEvent, so without this the agent saw
            // only "选项：…" labels and no event text at all.
            event = buildNeowEvent();
        }
        if (event == null && "event".equals(ui)) {
            // Event finished but the room is not left yet: an explicit "resolved" marker beats null,
            // which the agent read as "state unreadable" and looped on.
            JsonObject resolved = new JsonObject();
            resolved.addProperty("id", lastEventId == null ? "" : lastEventId);
            resolved.addProperty("name", lastEventName == null ? "" : lastEventName);
            resolved.addProperty("resolved", true);
            resolved.add("options", new JsonArray());
            resolved.addProperty("option_count", 0);
            event = resolved;
        }
        if (event != null && event.isJsonObject() && event.getAsJsonObject().has("id")
                && !event.getAsJsonObject().get("id").isJsonNull()) {
            lastEventId = event.getAsJsonObject().get("id").getAsString();
            if (event.getAsJsonObject().has("name")
                    && !event.getAsJsonObject().get("name").isJsonNull()) {
                lastEventName = event.getAsJsonObject().get("name").getAsString();
            }
        }
        root.add("event", event == null ? JsonNull.INSTANCE : event);
        // Neow's blessings are dialog options; expose them under their own key too (they are not an
        // "event" the agent can look up by id).
        root.add("neow", "neow".equals(ui) ? buildNeowBlock() : JsonNull.INSTANCE);
        JsonObject charSelect = buildCharSelect();
        root.add("char_select", charSelect);
        if ("char_select".equals(ui) && charSelect != null) {
            // Issue #49: say out loud whether the pick is already made. Without it the agent had to
            // infer "already selected" from which characters disappeared from legal_actions, which is
            // what produced the A→B→A jumping.
            String picked = charSelect.has("selected") && !charSelect.get("selected").isJsonNull()
                    ? charSelect.get("selected").getAsString() : null;
            hints.addProperty("char_selected", picked);
            hints.addProperty("next", picked == null
                    ? spirelink.actions.ActionTypes.CHOOSE_CHARACTER
                    : spirelink.actions.ActionTypes.EMBARK);
        }
        // GremlinMatchGame board: the minigame has no dialog, so its cards are the whole state.
        // Issue #119 · C10-06: only the PLAY screen gets attempts / flip_stalled_ms (see below).
        JsonObject matchGame = matchGameForAgent(spirelink.events.MatchGame.state());
        // Present whenever the minigame is mounted (INTRO / RULE_EXPLANATION included): its `screen`
        // field is how the agent knows flips are not accepted yet.
        root.add("match_game", matchGame == null ? JsonNull.INSTANCE : matchGame);
        if (matchGame != null) {
            hints.addProperty("match_game", true);
            if (event != null && event.isJsonObject()) {
                event.getAsJsonObject().add("match_game", matchGame);
            }
        }
        // 0.1.7 局中触发：第一次遇到的怪 / 事件 / 牌，还没有便签就点一下名。
        // "首次遭遇"是这个游戏里最贵的信息 —— 死过一次才知道的规律，别等第二次。
        if ("combat".equals(ui) || "event".equals(ui)) {
            com.google.gson.JsonArray fresh = firstEncounters(ui);
            if (fresh != null && fresh.size() > 0) {
                hints.add("first_encounter", fresh);
            }
        }
        NoteAnnotator.annotate(root);
        // Issue #124 · C15-02: one sentence per body explaining that those notes are history, so the
        // marker is readable without opening the docs.
        NoteAnnotator.explainHistory(root);
        // C: passive "you already have a note about this" line (deduped inside NoteHud, 60 s/key).
        triggerNotesOnEntry(ui);
        trackLastCombat(ui);
        // Postmortem first: it is what records the finished run into history.json, and memory must
        // already see that record on the very same frame.
        root.add("postmortem", buildPostmortem(ui));
        root.add("memory", buildMemory(ui));
        if ("death".equals(ui) || "victory".equals(ui)) {
            String suggestion = suggestNote(ui);
            if (suggestion != null && !suggestion.isEmpty()
                    && spirelink.util.NotesDedupe.claim("suggest:" + suggestion, suggestion)) {
                hints.addProperty("suggest_note", suggestion);
            }
            // 0.1.7 硬约束：这一屏不给 proceed，先写两条便签。pending 每帧都给（不进 dedupe），
            // 否则 agent 拒绝一次就再也看不见该写什么了。
            NotesGate.arm(killedBy());
            java.util.List<String> pending = NotesGate.pending();
            if (!pending.isEmpty()) {
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (String item : pending) {
                    arr.add(item);
                }
                hints.add("notes_pending", arr);
            }
        }
        return root;
    }

    // ------------------------------------------------------------------ memory / postmortem (M1-M3)

    private static long lastFrameCount = -1L;
    private static long lastFrameAt = System.currentTimeMillis();
    private static final java.util.List<String> LAST_COMBAT_MONSTERS = new java.util.ArrayList<>();
    private static int lastCombatTurns;
    private static String recordedRunKey;
    /** One WARN per stuck episode, not one per frame. */
    private static boolean stuckWarned;

    /** Keeps the last fight's monsters/turns so a death report can name the killer. */
    private static void trackLastCombat(String ui) {
        try {
            if (!"combat".equals(ui)) {
                return;
            }
            AbstractRoom combatRoom = safeRoom();
            java.util.List<AbstractMonster> monsters = combatRoom == null
                    ? null : AbstractDungeon.getMonsters() == null
                            ? null : AbstractDungeon.getMonsters().monsters;
            if (monsters == null) {
                return;
            }
            java.util.List<String> names = new java.util.ArrayList<>();
            for (AbstractMonster monster : monsters) {
                if (monster != null && monster.name != null) {
                    names.add(monster.name);
                }
            }
            if (!names.isEmpty()) {
                LAST_COMBAT_MONSTERS.clear();
                LAST_COMBAT_MONSTERS.addAll(names);
            }
            if (AbstractDungeon.actionManager != null && AbstractDungeon.actionManager.turn > 0) {
                lastCombatTurns = AbstractDungeon.actionManager.turn;
            }
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    /** M1: aggregated notes + run history, so the agent never has to scrape items every frame. */
    /**
     * Stable identity of the current run for note dedupe: character + max HP + the first card's
     * uuid. Deck size and HP change during a run, so those must not be part of it.
     */
    private static String currentRunId() {
        try {
            AbstractPlayer player = AbstractDungeon.player;
            if (player == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(player.chosenClass == null ? "?" : player.chosenClass.name());
            sb.append('|').append(player.maxHealth);
            if (player.masterDeck != null && player.masterDeck.group != null
                    && !player.masterDeck.group.isEmpty()
                    && player.masterDeck.group.get(0) != null) {
                sb.append('|').append(player.masterDeck.group.get(0).uuid);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static JsonObject buildMemory(String ui) {
        // Memory must NEVER be null: it is the visible proof that the agent is accumulating. Each
        // section degrades on its own and records its failure in "errors" instead of nuking the block.
        JsonObject out = new JsonObject();
        spirelink.util.NotesDedupe.observeRun(currentRunId());
        if (AbstractDungeon.currMapNode == null) {
            // Startup / main menu: no room at all, and every room helper would NPE. Notes and run
            // history are still readable, so answer those and stop.
            try {
                out.add("context_notes", new JsonArray());
                out.add("recent_notes", buildRecentNotes());
                out.add("counts", buildNoteCounts());
                out.add("run_history", tail(spirelink.util.RunHistory.records(), 5));
                out.add("briefing", buildBriefing(false));
                out.addProperty("notes_omitted_this_run", spirelink.util.NotesDedupe.omitted());
            } catch (Throwable t) {
                spirelink.util.CrashGuard.report("buildMemory(noRoom)", t);
            }
            return out;
        }
        JsonArray errors = new JsonArray();
        try {
            JsonArray context = new JsonArray();
            if ("event".equals(ui)) {
                addNoteIfAny(context, "event", spirelink.state.EventPayload.currentEventId());
            }
            AbstractPlayer player = AbstractDungeon.player;
            if (player != null) {
                if (player.potions != null) {
                    for (com.megacrit.cardcrawl.potions.AbstractPotion potion : player.potions) {
                        if (!spirelink.state.PotionSerializer.isPlaceholder(potion)) {
                            addNoteIfAny(context, "potion", potion.ID);
                        }
                    }
                }
                if (player.relics != null) {
                    for (com.megacrit.cardcrawl.relics.AbstractRelic relic : player.relics) {
                        if (relic != null) {
                            addNoteIfAny(context, "relic", relic.relicId);
                        }
                    }
                }
            }
            AbstractRoom combatRoom = safeRoom();
            java.util.List<AbstractMonster> monsters = combatRoom == null
                    ? null : AbstractDungeon.getMonsters() == null
                            ? null : AbstractDungeon.getMonsters().monsters;
            if (monsters != null) {
                for (AbstractMonster monster : monsters) {
                    if (monster != null && !monster.isDeadOrEscaped()) {
                        addNoteIfAny(context, "monster", monster.id);
                    }
                }
            }
            out.add("context_notes", context);

            // recent notes: newest first, 8 at most. Dedupe + ordering live in RecentNotes so the
            // main-menu path (buildRecentNotes) can never drift back to pushing the same note again.
            JsonObject all = spirelink.util.Notes.list(null);
            JsonObject counts = new JsonObject();
            // Build-time list is a peek (claim=false): the body may be cached, and only the response
            // actually served may consume the per-run dedupe window (see freshRecentNotes).
            out.add("recent_notes", RecentNotes.build(all, counts, false));
            out.add("counts", counts);
            out.addProperty("total", all.entrySet().size());
            out.addProperty("notes_omitted_this_run", spirelink.util.NotesDedupe.omitted());
            try {
                out.add("run_history", tail(spirelink.util.RunHistory.records(), 5));
            } catch (Throwable t) {
                errors.add("run_history: " + t);
            }
            // Always present: on the very first frame the agent read briefing=null and assumed it had
            // no accumulated notes at all.
            try {
                out.add("briefing", buildBriefing(false));
            } catch (Throwable t) {
                errors.add("briefing: " + t);
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("buildMemory", t);
            errors.add("fatal: " + t);
        }
        if (errors.size() > 0) {
            out.add("errors", errors);
        }
        if (!out.has("context_notes")) {
            out.add("context_notes", new JsonArray());
        }
        if (!out.has("recent_notes")) {
            out.add("recent_notes", new JsonArray());
        }
        if (!out.has("counts")) {
            out.add("counts", new JsonObject());
        }
        if (!out.has("run_history")) {
            out.add("run_history", new JsonArray());
        }
        return out;
    }

    /**
     * Newest notes first (max 8), used by the no-room memory path and the full one.
     *
     * <p>Same rules as the full path ({@link RecentNotes}): newest first, 8 at most, one delivery per
     * note version per run. Building the body is a peek ({@code claim=false}) because the body may be
     * cached; the per-run window is consumed once per served response by
     * {@link #freshRecentNotes()} (issue #3). {@code force_notes:true} still dumps everything once,
     * because NotesDedupe handles the bypass.
     */
    private static JsonArray buildRecentNotes() {
        try {
            return RecentNotes.build(spirelink.util.Notes.list(null), null, false);
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("buildRecentNotes", t);
            return new JsonArray();
        }
    }

    /**
     * {@code get_state} 发出去之前重算一次 recent_notes（用 claim=true 真正走一次去重）。
     *
     * <p>理由：{@code viewInternal} 在签名没变时直接返回缓存的 body，主菜单上签名几乎不动，
     * 于是构建期算好的 recent_notes 会被一遍遍原样发出去（issue #3）。这里和 briefing 一样
     * 每次响应重建一次，去重窗口就刚好按「响应」推进：同一局同一版本只出现一次，
     * {@code force_notes:true} 时 NotesDedupe 全量放行。
     */
    public static JsonArray freshRecentNotes() {
        try {
            return RecentNotes.build(spirelink.util.Notes.list(null), null, true);
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("freshRecentNotes", t);
            return new JsonArray();
        }
    }

    /** {@code {kind: count}} over every stored note. */
    private static JsonObject buildNoteCounts() {
        JsonObject counts = new JsonObject();
        try {
            java.util.Map<String, Integer> byKind = new java.util.LinkedHashMap<>();
            for (String key : spirelink.util.Notes.list(null).entrySet().stream()
                    .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toList())) {
                int split = key.indexOf(':');
                byKind.merge(split > 0 ? key.substring(0, split) : "general", 1, Integer::sum);
            }
            for (java.util.Map.Entry<String, Integer> entry : byKind.entrySet()) {
                counts.addProperty(entry.getKey(), entry.getValue().intValue());
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("buildNoteCounts", t);
        }
        return counts;
    }

    private static long updatedAtOf(com.google.gson.JsonElement element) {
        try {
            com.google.gson.JsonObject object = element.getAsJsonObject();
            return object.has("updated_at") ? object.get("updated_at").getAsLong() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void addNoteIfAny(JsonArray out, String kind, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        String text = spirelink.util.Notes.get(kind, id);
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        // Same per-run window as recent_notes: context notes stop repeating once delivered.
        if (!spirelink.util.NotesDedupe.claim(kind + ":" + id + "@"
                + spirelink.util.Notes.updatedAt(kind, id), text)) {
            return;
        }
        JsonObject item = new JsonObject();
        item.addProperty("kind", kind);
        item.addProperty("id", id);
        item.addProperty("text", text);
        out.add(item);
    }

    /** Newest {@code max} finished runs, read live from RunHistory (never a snapshot copy). */
    public static JsonArray recentRunRecords(int max) {
        try {
            return tail(spirelink.util.RunHistory.records(), max);
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("recentRunRecords", t);
            return new JsonArray();
        }
    }

    private static JsonArray tail(JsonArray source, int max) {
        JsonArray out = new JsonArray();
        int from = Math.max(0, source.size() - max);
        for (int i = from; i < source.size(); i++) {
            out.add(source.get(i));
        }
        return out;
    }

    /** Fresh copy of the briefing (used by ToolCatalog to refresh cached snapshots). */
    public static JsonObject freshBriefing() {
        try {
            return buildBriefing(true);
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("freshBriefing", t);
            return new JsonObject();
        }
    }

    /** M3: what the agent should know before this run starts. */
    /**
     * @param claim true 才会从按局去重窗口里消耗 general_notes（只给真正要发出去的响应）；
     *              构建 body 时传 false，否则缓存快照会把还没发出去的笔记白丢
     */
    /** 当前角色的便签（{@code character:<角色名>}），开局随 briefing 一起送到。 */
    private static JsonArray characterNotes() {
        JsonArray out = new JsonArray();
        String me = currentCharacter();
        if (me == null) {
            return out;
        }
        com.google.gson.JsonObject notes = spirelink.util.Notes.list("character");
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : notes.entrySet()) {
            String key = entry.getKey();
            if (!key.equals("character:" + me)) {
                continue;
            }
            com.google.gson.JsonObject item = new com.google.gson.JsonObject();
            item.addProperty("id", me);
            item.addProperty("text", entry.getValue().getAsJsonObject().get("text").getAsString());
            out.add(item);
        }
        return out;
    }

    /** 当前角色的稳定名字（IRONCLAD / THE_SILENT / DEFECT / WATCHER），读不到给 null。 */
    private static String currentCharacter() {
        try {
            if (AbstractDungeon.player == null || AbstractDungeon.player.chosenClass == null) {
                return null;
            }
            return AbstractDungeon.player.chosenClass.name();
        } catch (Throwable t) {
            return null;
        }
    }

    private static JsonObject buildBriefing(boolean claim) {
        JsonObject out = new JsonObject();
        // "How much have I accumulated?" - the first thing a returning player checks.
        out.addProperty("notes_total", noteTotal());
        out.add("notes_by_kind", buildNoteCounts());
        JsonArray deaths = new JsonArray();
        JsonArray history = spirelink.util.RunHistory.records();
        for (int i = history.size() - 1; i >= 0 && deaths.size() < 5; i--) {
            JsonObject run = history.get(i).getAsJsonObject();
            JsonObject item = new JsonObject();
            item.addProperty("run", run.has("run") ? run.get("run").getAsInt() : i + 1);
            item.addProperty("character", run.has("character") ? run.get("character").getAsString() : "");
            item.addProperty("act", run.has("act") ? run.get("act").getAsInt() : 0);
            item.addProperty("floor", run.has("floor") ? run.get("floor").getAsInt() : 0);
            item.addProperty("result", run.has("result") ? run.get("result").getAsString() : "");
            item.addProperty("killed_by", run.has("killed_by") ? run.get("killed_by").getAsString() : "");
            deaths.add(item);
        }
        out.add("recent_runs", deaths);
        JsonArray general = new JsonArray();
        // 0.1.8: general（旧名）与 global（新名）是同一个桶，两条都在这份里，跨角色通用。
        for (String kind : new String[] {"general", "global"}) {
            com.google.gson.JsonObject notes = spirelink.util.Notes.list(kind);
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : notes.entrySet()) {
                String text = entry.getValue().getAsJsonObject().get("text").getAsString();
                if (claim && !spirelink.util.NotesDedupe.claim(entry.getKey() + "@"
                        + updatedAtOf(entry.getValue()), text)) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("id", entry.getKey().substring(kind.length() + 1));
                item.addProperty("kind", kind);
                item.addProperty("text", text);
                general.add(item);
            }
        }
        out.add("general_notes", general);
        // 0.1.8: 角色便签 —— 只在本角色开局时送到。跨角色的教训混在一起时，
        // 观者会照着铁甲战士的血线规则去打，等于带错剧本。
        out.add("character_notes", characterNotes());
        out.add("death_causes", buildDeathCauses(history));
        return out;
    }

    /**
     * Same-cause rollup over the last three finished runs: "died to 乐加维林 twice" is the kind of
     * pattern a human notices and a per-run list hides.
     */
    private static JsonArray buildDeathCauses(JsonArray history) {
        JsonArray out = new JsonArray();
        try {
            java.util.Map<String, int[]> stats = new java.util.LinkedHashMap<>();
            int looked = 0;
            for (int i = history.size() - 1; i >= 0 && looked < 3; i--) {
                JsonObject run = history.get(i).getAsJsonObject();
                String result = run.has("result") ? run.get("result").getAsString() : "";
                if (!"death".equals(result)) {
                    continue;
                }
                String killedBy = run.has("killed_by") ? run.get("killed_by").getAsString() : "";
                if (killedBy.isEmpty()) {
                    killedBy = "unknown";
                }
                int runNo = run.has("run") ? run.get("run").getAsInt() : i + 1;
                int[] slot = stats.get(killedBy);
                if (slot == null) {
                    stats.put(killedBy, new int[] {1, runNo});
                } else {
                    slot[0] = slot[0] + 1;
                    slot[1] = Math.max(slot[1], runNo);
                }
                looked++;
            }
            for (java.util.Map.Entry<String, int[]> entry : stats.entrySet()) {
                JsonObject item = new JsonObject();
                item.addProperty("killed_by", entry.getKey());
                item.addProperty("count", entry.getValue()[0]);
                item.addProperty("last_run", entry.getValue()[1]);
                out.add(item);
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("buildDeathCauses", t);
        }
        return out;
    }

    private static int noteTotal() {
        try {
            return spirelink.util.Notes.list(null).entrySet().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** M2: how the run ended, rich enough to write a lesson without re-reading everything. */
    private static JsonObject buildPostmortem(String ui) {
        if (!"death".equals(ui) && !"victory".equals(ui)) {
            return null;
        }
        try {
            AbstractPlayer player = AbstractDungeon.player;
            JsonObject out = new JsonObject();
            boolean win = !"death".equals(ui);
            out.addProperty("result", win ? "victory" : "death");
            // A won run has no killer; carrying the last enemy name there reads as a death.
            String killedBy = win ? "" : (LAST_COMBAT_MONSTERS.isEmpty()
                    ? encounterId() : String.join(", ", LAST_COMBAT_MONSTERS));
            out.addProperty("killed_by", killedBy);
            out.addProperty("act", AbstractDungeon.actNum);
            out.addProperty("floor", AbstractDungeon.floorNum);
            out.addProperty("hp", player == null ? 0 : player.currentHealth);
            JsonArray deck = new JsonArray();
            int deckSize = 0;
            if (player != null && player.masterDeck != null) {
                deckSize = player.masterDeck.size();
                for (com.megacrit.cardcrawl.cards.AbstractCard card : player.masterDeck.group) {
                    if (card == null) {
                        continue;
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("id", card.cardID);
                    item.addProperty("upgraded", card.upgraded);
                    deck.add(item);
                }
            }
            out.addProperty("deck_size", deckSize);
            out.add("deck", deck);
            JsonArray relics = new JsonArray();
            if (player != null && player.relics != null) {
                for (com.megacrit.cardcrawl.relics.AbstractRelic relic : player.relics) {
                    if (relic != null) {
                        relics.add(relic.relicId);
                    }
                }
            }
            out.add("relics", relics);
            JsonArray potions = new JsonArray();
            if (player != null && player.potions != null) {
                for (com.megacrit.cardcrawl.potions.AbstractPotion potion : player.potions) {
                    if (!spirelink.state.PotionSerializer.isPlaceholder(potion)) {
                        potions.add(potion.ID);
                    }
                }
            }
            out.add("potions", potions);
            JsonObject lastCombat = new JsonObject();
            JsonArray names = new JsonArray();
            for (String name : LAST_COMBAT_MONSTERS) {
                names.add(name);
            }
            lastCombat.add("monsters", names);
            lastCombat.addProperty("turns", lastCombatTurns);
            out.add("last_combat", lastCombat);
            recordRunIfNeeded(out, player);
            return out;
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("buildPostmortem", t);
            return null;
        }
    }

    private static String encounterId() {
        try {
            Object key = com.megacrit.cardcrawl.dungeons.AbstractDungeon.lastCombatMetricKey;
            return key == null ? "unknown" : String.valueOf(key);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Appends this run to history.json exactly once. */
    private static void recordRunIfNeeded(JsonObject postmortem, AbstractPlayer player) {
        try {
            String character = player == null || player.chosenClass == null
                    ? "UNKNOWN" : player.chosenClass.name();
            // The key must identify THIS run, not just this floor/result: dying twice on floor 6 in
            // different runs would otherwise look like a duplicate and the second death would never
            // reach history.json.
            String key = character + "#" + postmortem.get("floor").getAsInt() + "#"
                    + postmortem.get("result").getAsString() + "#" + runToken(character);
            if (key.equals(recordedRunKey)) {
                return;
            }
            recordedRunKey = key;
            JsonObject record = new JsonObject();
            record.addProperty("run", spirelink.util.RunHistory.count() + 1);
            record.addProperty("character", character);
            record.addProperty("act", postmortem.get("act").getAsInt());
            record.addProperty("floor", postmortem.get("floor").getAsInt());
            record.addProperty("result", postmortem.get("result").getAsString());
            record.addProperty("killed_by", postmortem.get("killed_by").getAsString());
            record.addProperty("hp", postmortem.get("hp").getAsInt());
            record.addProperty("deck_size", postmortem.get("deck_size").getAsInt());
            record.addProperty("notes", spirelink.util.Notes.list(null).entrySet().size());
            record.addProperty("finished_at", System.currentTimeMillis() / 1000L);
            spirelink.util.RunHistory.append(record);
            spirelink.util.Diag.info("run_history: 记录第 " + record.get("run").getAsInt()
                    + " 局（" + postmortem.get("result").getAsString() + "，" + character
                    + " floor " + postmortem.get("floor").getAsInt() + "）");
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("recordRunIfNeeded", t);
        }
    }

    /**
     * Identity of the current run for recording purposes: the deck's card uuids plus the player's HP
     * are stable within a run and differ between runs, so two identical-looking deaths still record.
     */
    private static String runToken(String character) {
        AbstractPlayer player = AbstractDungeon.player;
        StringBuilder sb = new StringBuilder(character);
        try {
            sb.append('|').append(player == null ? -1 : player.maxHealth);
            if (player != null && player.masterDeck != null
                    && player.masterDeck.group != null) {
                sb.append('|').append(player.masterDeck.group.size());
                for (com.megacrit.cardcrawl.cards.AbstractCard card : player.masterDeck.group) {
                    if (card != null) {
                        sb.append(',').append(card.uuid);
                    }
                }
            }
        } catch (Throwable ignored) {
            // a weaker token is still better than none
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    /** A concrete note suggestion (the agent decides whether to write it). */
    /** 第一次遇到、还没有便签的怪或事件（0.1.7 局中提示）。 */
    private static com.google.gson.JsonArray firstEncounters(String ui) {
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        try {
            if ("combat".equals(ui)) {
                java.util.List<com.megacrit.cardcrawl.monsters.AbstractMonster> monsters =
                        AbstractDungeon.getMonsters() == null ? null
                                : AbstractDungeon.getMonsters().monsters;
                if (monsters != null) {
                    for (com.megacrit.cardcrawl.monsters.AbstractMonster monster : monsters) {
                        if (monster == null || monster.isDeadOrEscaped() || monster.id == null) {
                            continue;
                        }
                        if (spirelink.util.Notes.get("monster", monster.id) == null
                                && !out.contains(new com.google.gson.JsonPrimitive(
                                        "monster:" + monster.id))) {
                            out.add("monster:" + monster.id);
                        }
                    }
                }
            } else {
                String id = EventPayload.currentEventId();
                if (id != null && spirelink.util.Notes.get("event", id) == null) {
                    out.add("event:" + id);
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return out;
    }

    /** 这局最后一战的怪物（读不到就给遭遇 id），给 NotesGate 用。 */
    private static String killedBy() {
        try {
            if (!LAST_COMBAT_MONSTERS.isEmpty()) {
                return LAST_COMBAT_MONSTERS.get(LAST_COMBAT_MONSTERS.size() - 1);
            }
            return encounterId();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String suggestNote(String ui) {
        try {
            String killedBy = LAST_COMBAT_MONSTERS.isEmpty() ? encounterId()
                    : LAST_COMBAT_MONSTERS.get(LAST_COMBAT_MONSTERS.size() - 1);
            int floor = AbstractDungeon.floorNum;
            String id = "run" + (spirelink.util.RunHistory.count() + 1) + "-lesson";
            String hint = "death".equals(ui)
                    ? "这局死了（第 " + floor + " 层，死于 " + killedBy + "）"
                    : "这局赢了（第 " + floor + " 层）";
            return hint + "，建议 note_set{kind:\"general\", id:\"" + id
                    + "\", text:\"下次要注意什么\"}";
        } catch (Throwable t) {
            return "这局结束了，建议写一条 general 笔记总结经验";
        }
    }

    /** Shows at most one {@code 有笔记 …} line when the agent already noted what it just met. */
    private static void triggerNotesOnEntry(String ui) {
        try {
            if ("event".equals(ui)) {
                String id = spirelink.state.EventPayload.currentEventId();
                String note = id == null ? null : spirelink.util.Notes.get("event", id);
                if (note != null) {
                    spirelink.ui.NoteHud.seen("event", id, note);
                }
                return;
            }
            if ("combat".equals(ui)) {
                AbstractRoom lastRoom = safeRoom();
                java.util.List<AbstractMonster> monsters = lastRoom == null
                        ? null : AbstractDungeon.getMonsters() == null
                                ? null : AbstractDungeon.getMonsters().monsters;
                if (monsters != null) {
                    for (AbstractMonster monster : monsters) {
                        if (monster == null || monster.isDeadOrEscaped() || monster.id == null) {
                            continue;
                        }
                        String note = spirelink.util.Notes.get("monster", monster.id);
                        if (note != null) {
                            spirelink.ui.NoteHud.seen("monster", monster.id, note);
                            return;
                        }
                    }
                }
                return;
            }
            AbstractPlayer player = AbstractDungeon.player;
            if (player == null) {
                return;
            }
            if (player.potions != null) {
                for (com.megacrit.cardcrawl.potions.AbstractPotion potion : player.potions) {
                    if (spirelink.state.PotionSerializer.isPlaceholder(potion)) {
                        continue;
                    }
                    String note = spirelink.util.Notes.get("potion", potion.ID);
                    if (note != null) {
                        spirelink.ui.NoteHud.seen("potion", potion.ID, note);
                        return;
                    }
                }
            }
            if (player.relics != null) {
                for (com.megacrit.cardcrawl.relics.AbstractRelic relic : player.relics) {
                    if (relic == null || relic.relicId == null) {
                        continue;
                    }
                    String note = spirelink.util.Notes.get("relic", relic.relicId);
                    if (note != null) {
                        spirelink.ui.NoteHud.seen("relic", relic.relicId, note);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            // HUD only: never let this affect the state
        }
    }

    /** {@code meta.character} plus the field it came from (issue #49). */
    static final class CharacterMeta {
        final String name;
        final String source;

        CharacterMeta(String name, String source) {
            this.name = name;
            this.source = source;
        }
    }

    /**
     * Which character {@code meta.character} may report.
     *
     * <p>On the character select screen {@code AbstractDungeon.player} still points at the PREVIOUS
     * run, so reading it first made {@code meta.character} contradict {@code char_select.selected}
     * (issue #49: the agent saw DEFECT while THE_SILENT was highlighted and kept switching). The
     * screen wins whenever it has an answer; {@code character_source} says which field answered so a
     * surprising value can be traced without a screenshot.
     *
     * <p>Pure on purpose: the decision is the bug, the field reads are not.
     */
    static CharacterMeta resolveCharacter(String ui, String playerClass, boolean screenKnown,
            String screenSelected) {
        if ("char_select".equals(ui) && screenKnown) {
            // Nothing selected yet must stay null: the previous run's class is not an answer.
            return new CharacterMeta(screenSelected, "char_select");
        }
        if (playerClass != null) {
            return new CharacterMeta(playerClass, "player");
        }
        if (screenSelected != null) {
            // Before the run starts there is no player yet: report the highlighted character instead
            // of null, otherwise the agent can only guess from action labels.
            return new CharacterMeta(screenSelected, "char_select");
        }
        return new CharacterMeta(null, "player");
    }

    private JsonObject buildMeta(boolean ready, String ui) {
        JsonObject meta = new JsonObject();
        // While an action is still in flight (queued) the world is NOT settled for the agent, even if
        // the game looks idle: reporting ready=true there is what made the agent believe a skip had
        // been applied. The guard is wall-clock bounded so it can never deadlock.
        boolean inFlight = spirelink.util.ActionInFlight.blocked(version) >= 0;
        ready = ready && !inFlight;
        meta.addProperty("ready", ready);
        if (inFlight) {
            meta.addProperty("in_flight", true);
        }
        meta.addProperty("test_mode", spirelink.util.TestMode.enabled());
        meta.addProperty("speed", spirelink.util.TestMode.speed());
        // Hard rule: ready=false must always say who we are waiting for.
        // 死亡界面 ready 也是 true，但 "playing" 是谎话（试玩 agent 报「ui=death 却 awaiting=playing」）：
        // 这一屏是等人点「继续」，不是等玩家出牌。选人界面同理（#49）。
        // Lazy: probing the gate is only needed when we are not ready.
        String awaitingNow = awaitingFor(ui, ready,
                ready ? null : StableGate.evaluate(StableGate.probe()).reason);
        meta.addProperty("awaiting", awaitingNow);
        meta.addProperty("state_version", version);
        meta.addProperty("in_game", inGame());

        Long seed = Settings.seed;
        if (seed == null) {
            meta.add("seed", JsonNull.INSTANCE);
            meta.add("seed_raw", JsonNull.INSTANCE);
        } else {
            meta.addProperty("seed", safeSeedString(seed.longValue()));
            meta.addProperty("seed_raw", seed.longValue());
        }

        AbstractPlayer player = AbstractDungeon.player;
        String playerCharacter = player == null || player.chosenClass == null
                ? null : player.chosenClass.name();
        // On the character select screen AbstractDungeon.player still points at the PREVIOUS run, so
        // reading it first made meta.character (DEFECT) contradict char_select.selected (THE_SILENT)
        // and the agent kept switching characters to "fix" the contradiction (issue #49).
        JsonObject charSelect = buildCharSelect();
        String screenCharacter = charSelect == null || !charSelect.has("selected")
                || charSelect.get("selected").isJsonNull()
                        ? null : charSelect.get("selected").getAsString();
        CharacterMeta characterMeta = resolveCharacter(ui, playerCharacter,
                charSelect != null, screenCharacter);
        meta.addProperty("character", characterMeta.name);
        // Which field answered: "char_select" or "player". New key (contract only grows).
        meta.addProperty("character_source", characterMeta.source);
        // Issue #77: on the character-select screen the chosen level lives on that screen; vanilla
        // only copies it into AbstractDungeon.ascensionLevel inside the screen's own confirm handler.
        // Reporting the dungeon field there made meta.ascension stay 0 right after choose_ascension,
        // although tools.md tells the caller to verify the setting with meta.ascension.
        meta.addProperty("ascension", ascensionForUi(ui));
        meta.addProperty("act", AbstractDungeon.actNum);
        meta.addProperty("floor", AbstractDungeon.floorNum);

        AbstractDungeon.CurrentScreen screen = AbstractDungeon.screen;
        meta.addProperty("screen_type", screen == null ? "NONE" : screen.name());

        AbstractRoom room = safeRoom();
        // While the map is open the current room object is still the PREVIOUS room (vanilla does not
        // swap it until a node is picked), so reporting it made the map look like an EventRoom.
        boolean onMap = "map".equals(ui) || screen == AbstractDungeon.CurrentScreen.MAP;
        if (room == null) {
            meta.add("room_phase", JsonNull.INSTANCE);
            meta.add("room_type", JsonNull.INSTANCE);
        } else if (onMap) {
            meta.addProperty("room_phase", "NONE");
            meta.addProperty("room_type", "MapRoom");
        } else {
            meta.addProperty("room_phase", room.phase == null ? null : room.phase.name());
            meta.addProperty("room_type", room.getClass().getSimpleName());
        }
        // Issue #42: "the boss is down, only proceed is left" as a boolean too, so an agent that
        // only looks at meta (and not at legal_actions) can still tell "fight over" from "nothing
        // happened". Same predicate as ui=boss_cleared, so the two can never disagree.
        meta.addProperty("room_cleared", room != null && !onMap
                && bossRoomCleared(room.getClass().getSimpleName(), room.phase));

        // Issue #55/#69: one writer for the whole life-cycle. buildMeta runs when a body is rebuilt;
        // writeLiveness() re-applies the very same facts on every serve, because the cached body was
        // built *before* the crash and its render_crashed would otherwise stay frozen at false.
        writeLivenessMeta(meta, GameLiveness.sample());
        meta.addProperty("ui", ui);
        // Stable grouping across versions: "shop_room" (entered, not opened) and "shop" (merchant
        // screen open) deliberately stay distinct, and ui_group says they belong together.
        meta.addProperty("ui_group", "shop_room".equals(ui) ? "shop" : ui);
        // Soft hint for the harness (M0 has nothing to suggest yet).
        meta.add("suggest_include", new JsonArray());
        meta.addProperty("mod_version", modVersion);
        // Language always follows the runtime setting; never Locale.getDefault().
        meta.addProperty("language", Settings.language == null ? null : Settings.language.name());
        return meta;
    }

    /**
     * Ascension level the next run would start at, for the screen currently up.
     *
     * <p>Issue #77: {@code AbstractDungeon.ascensionLevel} is only written when the run actually
     * starts, so the character-select screen was the one place where the field could not be used to
     * check a fresh {@code choose_ascension}. There the screen itself is the source of truth
     * ({@code isAscensionMode} says whether a level is in effect at all).
     */
    static int ascensionForUi(String ui) {
        return ascensionFrom("char_select".equals(ui), charSelectAscension(), dungeonAscension());
    }

    /** {@code AbstractDungeon.ascensionLevel}, or 0 when the game classes are not loadable. */
    private static int dungeonAscension() {
        try {
            return AbstractDungeon.ascensionLevel;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Pure form of {@link #ascensionForUi}, so the rule is unit-tested without a game. */
    static int ascensionFrom(boolean charSelectScreen, Integer screenLevel, int dungeonLevel) {
        if (charSelectScreen && screenLevel != null) {
            return screenLevel.intValue();
        }
        return dungeonLevel;
    }

    /** Level armed on the character-select screen, or null when that screen is not up. */
    static Integer charSelectAscension() {
        try {
            com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen menu = CardCrawlGame.mainMenuScreen;
            com.megacrit.cardcrawl.screens.charSelect.CharacterSelectScreen screen =
                    menu == null ? null : menu.charSelectScreen;
            if (screen == null) {
                return null;
            }
            // Normal run: the screen keeps the last level it showed, but it is not in effect.
            return Integer.valueOf(screen.isAscensionMode ? Math.max(0, screen.ascensionLevel) : 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private JsonObject buildPlayer(AbstractPlayer player) {
        JsonObject json = new JsonObject();
        json.addProperty("current_hp", player.currentHealth);
        // Alias with the same meaning; "hp" is the word agents reach for first.
        json.addProperty("hp", player.currentHealth);
        json.addProperty("max_hp", player.maxHealth);
        json.addProperty("block", player.currentBlock);
        // Energy only means something while fighting: outside combat the panel keeps the last
        // combat's numbers, which made map/shop screens look like the player still had energy.
        AbstractRoom currentRoom = safeRoom();
        boolean inCombat = currentRoom != null
                && currentRoom.phase == AbstractRoom.RoomPhase.COMBAT;
        if (inCombat) {
            json.addProperty("energy", EnergyPanel.getCurrentEnergy());
            json.addProperty("energy_max", player.energy == null ? 0 : player.energy.energy);
        } else {
            json.add("energy", JsonNull.INSTANCE);
            json.add("energy_max", JsonNull.INSTANCE);
            // Issue #124 · C15-04: null with no word at all reads as "field missing / maybe a bug".
            // A number would be worse (the vanilla panel keeps the last combat's values outside combat,
            // which is why this is null in the first place), so say which it is.
            json.addProperty("energy_note",
                    "非战斗态：能量只在战斗房里有意义（原版能量面板会保留上一战的数字，照抄会像"
                            + "「你还有 3 费」）；进战斗后 energy / energy_max 会回来。");
        }
        json.addProperty("gold", player.gold);

        AbstractStance stance = player.stance;
        if (stance == null || "Neutral".equals(stance.ID)) {
            json.add("stance", JsonNull.INSTANCE);
        } else {
            JsonObject stanceJson = new JsonObject();
            stanceJson.addProperty("id", stance.ID);
            stanceJson.addProperty("name", stance.name);
            json.add("stance", stanceJson);
        }

        // 充能球：槽位数、聚焦、每一格（空格也占一个 index，agent 才知道还剩几格、第一个会被激发的是谁）
        JsonArray orbs = new JsonArray();
        int fitted = 0;
        if (player.orbs != null) {
            for (int i = 0; i < player.orbs.size(); i++) {
                AbstractOrb orb = player.orbs.get(i);
                JsonObject orbJson = new JsonObject();
                orbJson.addProperty("index", i);
                if (orb == null || orb.ID == null) {
                    orbJson.addProperty("empty", true);
                    orbJson.add("id", JsonNull.INSTANCE);
                    orbJson.add("name", JsonNull.INSTANCE);
                } else {
                    fitted++;
                    orbJson.addProperty("empty", false);
                    orbJson.addProperty("id", orb.ID);
                    orbJson.addProperty("name", orb.name);
                    orbJson.addProperty("passive", orb.passiveAmount);
                    orbJson.addProperty("evoke", orb.evokeAmount);
                }
                orbs.add(orbJson);
            }
        }
        json.add("orbs", orbs);
        json.addProperty("orb_slots", player.maxOrbs);
        json.addProperty("orbs_filled", fitted);
        // 激发顺序按槽位从左到右，第一个非空的就是下一次激发的那颗。
        json.addProperty("orb_evoke_next", firstOrbIndex(player));
        json.addProperty("focus", focusAmount(player));
        json.add("potions", buildPlayerBelt(player));

        // 只在战斗里输出 powers：战斗结束回到地图后，player.powers 还挂着上一场的力量/敏捷/腐化，
        // 试玩 agent 实测「地图界面 state.powers 仍显示上一场的残留」，会让人以为 Buff 还在。
        AbstractRoom powersRoom = safeRoom();
        boolean inCombatNow = powersRoom != null
                && powersRoom.phase == AbstractRoom.RoomPhase.COMBAT;
        JsonArray powers = new JsonArray();
        if (inCombatNow && player.powers != null) {
            java.util.List<PowerSerializer.PowerView> views =
                    new java.util.ArrayList<PowerSerializer.PowerView>();
            for (AbstractPower power : player.powers) {
                if (power != null) {
                    views.add(PowerSerializer.fromPower(power));
                }
            }
            // Buffs first, then debuffs: a player scans their own buffs before their problems.
            java.util.Collections.sort(views, PowerSerializer.PowerView.BUFFS_FIRST);
            for (PowerSerializer.PowerView view : views) {
                powers.add(PowerSerializer.toJson(view));
            }
        }
        json.add("powers", powers);

        // M0 便利字段：权威来源是 §7.1 的独立 relics 区块（P1 拆出去）。
        JsonArray relics = new JsonArray();
        if (player.relics != null) {
            for (AbstractRelic relic : player.relics) {
                if (relic == null) {
                    continue;
                }
                JsonObject relicJson = new JsonObject();
                relicJson.addProperty("id", relic.relicId);
                relicJson.addProperty("name", relic.name);
                // counter == -1 means "this relic has no counter" (design 7.6).
                if (relic.counter >= 0) {
                    relicJson.addProperty("counter", relic.counter);
                }
                // C3-01: relic text goes through the same cleaner as cards / potions / event prose.
                // It used to be handed to the glossary raw, so the renderer's colour codes reached
                // the agent ("回复 #b6 点生命").
                RelicText.putText(relicJson, relic.description);
                relics.add(relicJson);
            }
        }
        json.add("relics", relics);
        return json;
    }

    /**
     * Potions (design 7.5 v1.1): fixed belt slots, empty slots kept so indices never drift.
     * The game fills unused slots with a {@code PotionSlot} placeholder — that is an empty slot,
     * not a potion, and is reported as {@code {"slot": n, "empty": true}} only.
     */
    private JsonObject buildPotions(AbstractPlayer player) {
        int slotCount = player == null ? 0 : Math.max(player.potionSlots, 0);
        AbstractPotion[] bySlot = new AbstractPotion[slotCount];
        List<AbstractPotion> leftover = new ArrayList<>();
        if (player != null && player.potions != null) {
            for (AbstractPotion potion : player.potions) {
                if (PotionSerializer.isPlaceholder(potion)) {
                    // Unused belt slot: leave the array entry null so it renders as empty.
                    continue;
                }
                if (potion.slot >= 0 && potion.slot < slotCount && bySlot[potion.slot] == null) {
                    bySlot[potion.slot] = potion;
                } else {
                    leftover.add(potion);
                }
            }
            for (AbstractPotion potion : leftover) {
                for (int i = 0; i < slotCount; i++) {
                    if (bySlot[i] == null) {
                        bySlot[i] = potion;
                        break;
                    }
                }
            }
        }

        JsonArray slots = new JsonArray();
        for (int i = 0; i < slotCount; i++) {
            AbstractPotion potion = bySlot[i];
            if (potion == null) {
                slots.add(PotionSerializer.toJson(PotionSerializer.emptySlot(i)));
                continue;
            }
            PotionSerializer.PotionView view = PotionSerializer.fromPotion(potion, i);
            view.canUse = Boolean.valueOf(safeCanUse(potion));
            view.canDiscard = Boolean.valueOf(safeCanDiscard(potion));
            slots.add(PotionSerializer.toJson(view));
        }

        JsonObject potions = new JsonObject();
        potions.addProperty("slot_count", slotCount);
        potions.add("slots", slots);
        return potions;
    }

    /** Belt as seen from the player block: only real potions, with slot and usability. */
    private JsonArray buildPlayerBelt(AbstractPlayer player) {
        JsonArray belt = new JsonArray();
        try {
            if (player == null || player.potions == null) {
                return belt;
            }
            for (AbstractPotion potion : player.potions) {
                if (PotionSerializer.isPlaceholder(potion)) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("slot", potion.slot);
                item.addProperty("id", potion.ID);
                item.addProperty("name", potion.name);
                item.addProperty("can_use", safeCanUse(potion));
                item.addProperty("can_discard", safeCanDiscard(potion));
                spirelink.util.Glossary.addMarked(item, "description",
                        cleanUp(potion.description));
                belt.add(item);
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("buildPlayerBelt", t);
        }
        return belt;
    }

    /** {@code include:["deck"]} block: the full master deck (main thread only). */
    public static JsonObject buildDeckBlock() {
        AbstractPlayer player = AbstractDungeon.player;
        return cardsBlock(player == null ? null : player.masterDeck);
    }

    /**
     * {@code include:["discard_pile"]} / {@code "exhaust_pile"} / {@code "draw_pile"}: the full list,
     * in the same shape as the deck block.
     */
    public static JsonObject buildPileBlock(String kind) {
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null) {
            return cardsBlock(null);
        }
        if ("discard".equals(kind)) {
            return cardsBlock(player.discardPile);
        }
        if ("exhaust".equals(kind)) {
            return cardsBlock(player.exhaustPile);
        }
        if ("draw".equals(kind)) {
            return cardsBlock(player.drawPile);
        }
        return cardsBlock(null);
    }

    private static JsonObject cardsBlock(com.megacrit.cardcrawl.cards.CardGroup group) {
        JsonObject out = new JsonObject();
        JsonArray cards = new JsonArray();
        try {
            if (group != null) {
                for (com.megacrit.cardcrawl.cards.AbstractCard card : group.group) {
                    if (card == null) {
                        continue;
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("uuid", String.valueOf(card.uuid));
                    item.addProperty("id", card.cardID);
                    item.addProperty("name", card.name);
                    item.addProperty("type", card.type == null ? "" : card.type.name());
                    item.addProperty("rarity", card.rarity == null ? "" : card.rarity.name());
                    item.addProperty("cost", card.cost);
                    item.addProperty("upgraded", card.upgraded);
                    // "升级之后会变成什么样" —— 玩家在牌组里点开一张牌就是想知道这个。
                    // 给 agent 一句话，也给人类一个能核对的文本（界面上另有放大预览）。
                    if (!card.upgraded) {
                        try {
                            if (card.canUpgrade()) {
                                com.megacrit.cardcrawl.cards.AbstractCard up = card.makeCopy();
                                up.upgrade();
                                item.addProperty("upgraded_name", up.name);
                                item.addProperty("upgraded_text",
                                        spirelink.state.CardText.describe(up));
                            }
                        } catch (Throwable ignored) {
                            // a card that cannot preview still lists normally
                        }
                    }
                    cards.add(item);
                }
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("cardsBlock", t);
        }
        out.addProperty("count", cards.size());
        out.add("cards", cards);
        return out;
    }

    /** Merchant stock, or null when the player is not in a shop room. */
    private static JsonObject buildShop() {
        try {
            boolean shopRoom = safeRoom() instanceof com.megacrit.cardcrawl.rooms.ShopRoom;
            if (!shopRoom && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.SHOP) {
                return null;
            }
            com.megacrit.cardcrawl.shop.ShopScreen screen = AbstractDungeon.shopScreen;
            if (screen == null) {
                return null;
            }
            JsonObject out = new JsonObject();
            out.addProperty("open", screen.isActive
                    && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SHOP);
            int gold = AbstractDungeon.player == null ? 0 : AbstractDungeon.player.gold;
            JsonArray cards = new JsonArray();
            java.util.List<com.megacrit.cardcrawl.cards.AbstractCard> cardList =
                    spirelink.actions.ActionPlanner.shopCards(screen);
            for (int i = 0; i < cardList.size(); i++) {
                com.megacrit.cardcrawl.cards.AbstractCard card = cardList.get(i);
                if (card == null || card.price <= 0) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("index", i);
                item.addProperty("id", card.cardID);
                item.addProperty("name", card.name);
                item.addProperty("price", card.price);
                item.addProperty("affordable", gold >= card.price);
                cards.add(item);
            }
            out.add("cards", cards);
            JsonArray relics = new JsonArray();
            java.util.List<?> relicList = spirelink.actions.ShopAccess.relics(screen);
            for (int i = 0; i < relicList.size(); i++) {
                com.megacrit.cardcrawl.shop.StoreRelic store =
                        (com.megacrit.cardcrawl.shop.StoreRelic) relicList.get(i);
                if (store == null || store.isPurchased || store.relic == null) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("index", i);
                item.addProperty("id", store.relic.relicId);
                item.addProperty("name", store.relic.name);
                item.addProperty("price", store.price);
                item.addProperty("affordable", gold >= store.price);
                relics.add(item);
            }
            out.add("relics", relics);
            JsonArray potions = new JsonArray();
            java.util.List<?> potionList = spirelink.actions.ShopAccess.potions(screen);
            for (int i = 0; i < potionList.size(); i++) {
                com.megacrit.cardcrawl.shop.StorePotion store =
                        (com.megacrit.cardcrawl.shop.StorePotion) potionList.get(i);
                if (store == null || store.isPurchased || store.potion == null) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("index", i);
                item.addProperty("id", store.potion.ID);
                item.addProperty("name", store.potion.name);
                item.addProperty("price", store.price);
                item.addProperty("affordable", gold >= store.price);
                potions.add(item);
            }
            out.add("potions", potions);
            JsonObject purge = new JsonObject();
            purge.addProperty("available", screen.purgeAvailable);
            purge.addProperty("cost", com.megacrit.cardcrawl.shop.ShopScreen.actualPurgeCost);
            purge.addProperty("affordable", gold >= com.megacrit.cardcrawl.shop.ShopScreen.actualPurgeCost);
            out.add("purge", purge);
            // Issue #126 · C17-03: legal_actions advertises purge_card, but the price only lived two
            // levels deep in shop.purge.cost - an agent reading get_state.shop could not tell what the
            // purge it is being offered costs. Same source as purge.available / purge.cost.
            out.addProperty("purge_cost", com.megacrit.cardcrawl.shop.ShopScreen.actualPurgeCost);
            out.addProperty("purge_available", screen.purgeAvailable);
            out.addProperty("gold", gold);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The three card-reward offers, or null when no card reward screen is up. */
    private static JsonObject buildCardReward() {
        try {
            if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.CARD_REWARD
                    || AbstractDungeon.cardRewardScreen == null) {
                return null;
            }
            com.megacrit.cardcrawl.screens.CardRewardScreen screen = AbstractDungeon.cardRewardScreen;
            if (screen.rewardGroup == null || screen.rewardGroup.isEmpty()) {
                return null;
            }
            JsonArray cards = new JsonArray();
            int index = 0;
            for (com.megacrit.cardcrawl.cards.AbstractCard card : screen.rewardGroup) {
                if (card == null) {
                    index++;
                    continue;
                }
                JsonObject item = new JsonObject();
                // take_card is index-based; without this index the agent had to guess.
                item.addProperty("index", index);
                index++;
                item.addProperty("uuid", String.valueOf(card.uuid));
                item.addProperty("id", card.cardID);
                item.addProperty("name", card.name);
                item.addProperty("type", card.type == null ? "" : card.type.name());
                item.addProperty("rarity", card.rarity == null ? "" : card.rarity.name());
                item.addProperty("cost", card.cost);
                item.addProperty("upgraded", card.upgraded);
                // Tokens such as !D!/!M! need the live numbers; give both.
                // -1 means "this card has no such number"; reporting it looked like a real value.
                if (card.damage >= 0) {
                    item.addProperty("damage", card.damage);
                }
                if (card.block >= 0) {
                    item.addProperty("block", card.block);
                }
                if (card.magicNumber >= 0) {
                    item.addProperty("magic", card.magicNumber);
                }
                spirelink.util.Glossary.addMarked(item, "text",
                        spirelink.state.CardText.describe(card));
                item.addProperty("raw_text", card.rawDescription == null ? "" : card.rawDescription);
                cards.add(item);
            }
            JsonObject out = new JsonObject();
            out.addProperty("count", cards.size());
            out.add("cards", cards);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Map rows from the current row up (the rest is unreachable), so the agent can see the boss node
     * even though it has no outgoing edges. Null outside a run.
     */
    /**
     * One map node: {@code {x, y, symbol, room, taken?, edges:[[x,y]]}}.
     *
     * <p>Everything else the old node carried (row, column, column_label, room_zh, edge_count,
     * edges_in, empty, unresolved) is derivable from these fields; together they were ~60% of a
     * 28 kB {@code get_state}.
     */
    static JsonObject mapNodeJson(int x, int y, String symbol, String roomCode, boolean taken,
            JsonArray edges) {
        JsonObject item = new JsonObject();
        item.addProperty("x", x);
        item.addProperty("y", y);
        item.addProperty("symbol", symbol);
        item.addProperty("room", roomCode);
        if (taken) {
            item.addProperty("taken", true);
        }
        item.add("edges", edges == null ? new JsonArray() : edges);
        return item;
    }

    /**
     * One reading of "where can I go from here", shared by everything in a single payload ({@code
     * map.reachable} and {@code hints.map_no_reachable}).
     *
     * <p>Issue #100: the two consumers called {@code ActionPlanner.reachableNodes()} separately, so
     * one body cost two full map scans - and while the map had no reachable node each scan wrote a
     * multi-hundred-byte {@code mapDump()} WARN. Two answers to one question also let the payload
     * contradict itself: on the last row with the boss reachable, {@code map.reachable} listed the
     * boss icon while {@code hints.map_no_reachable} claimed there was nowhere to go.
     */
    static final class MapProbe {
        final java.util.List<com.megacrit.cardcrawl.map.MapRoomNode> reachable;
        final boolean bossAvailable;
        final boolean bossPresent;

        /** Package-visible so the "boss counts as a way forward" rule can be pinned without a game. */
        MapProbe(java.util.List<com.megacrit.cardcrawl.map.MapRoomNode> reachable,
                boolean bossAvailable, boolean bossPresent) {
            this.reachable = reachable == null
                    ? java.util.Collections.<com.megacrit.cardcrawl.map.MapRoomNode>emptyList()
                    : reachable;
            this.bossAvailable = bossAvailable;
            this.bossPresent = bossPresent;
        }

        /**
         * True when this frame really has no way forward: no walkable node <em>and</em> no boss to
         * enter. That is the state {@code hints.map_no_reachable} documents; "no node but the boss
         * is clickable" is reachable - via the boss icon - and must not be reported as stuck.
         */
        boolean noWayForward() {
            return reachable.isEmpty() && !bossAvailable;
        }

        /** @return null when the planner threw; callers degrade instead of inventing a map. */
        static MapProbe probe() {
            try {
                return new MapProbe(spirelink.actions.ActionPlanner.reachableNodes(),
                        spirelink.actions.ActionPlanner.bossAvailable(),
                        spirelink.actions.ActionPlanner.bossPresent());
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static JsonObject buildMap(MapProbe mapProbe) {
        try {
            if (AbstractDungeon.map == null || AbstractDungeon.map.isEmpty()) {
                return null;
            }
            // The whole act is visible to a player, so the whole act is visible here: a planner has to
            // see the lines above the current row to route around elites or towards a shop.
            // Layout is rows of nodes, one character of JSON per pixel of screen: row/column/room_zh/
            // edges_in/edge_count are all derivable from x/y/edges, and they were 60% of every call.
            JsonArray rows = new JsonArray();
            for (int y = 0; y < AbstractDungeon.map.size(); y++) {
                JsonArray row = new JsonArray();
                for (com.megacrit.cardcrawl.map.MapRoomNode node : AbstractDungeon.map.get(y)) {
                    if (node == null) {
                        continue;
                    }
                    String roomName = node.room == null ? "" : node.room.getClass().getSimpleName();
                    // 地图上的「?」节点在生成时是 EventRoom，但进去以后游戏可能换成怪物/商店/宝箱
                    // （试玩 agent 实测：标 event 进去是 MonsterRoom / ShopRoom / TreasureRoom）。
                    // 只在地图这一层把它报成 unknown，别让人以为「?」= 事件。
                    // room 为 null（还没生成具体房间）的节点也报 unknown：'' 会让 agent 以为「没有房间类型」。
                    String roomCode = ("EventRoom".equals(roomName) || roomName.isEmpty())
                            ? "unknown" : UiNames.roomCode(roomName);
                    JsonArray edges = new JsonArray();
                    for (com.megacrit.cardcrawl.map.MapEdge mapEdge : edgesOfNode(node)) {
                        JsonArray link = new JsonArray();
                        link.add(mapEdge.dstX);
                        link.add(mapEdge.dstY);
                        edges.add(link);
                    }
                    row.add(mapNodeJson(node.x, node.y, mapSymbolOf(roomName), roomCode,
                            node.taken, edges));
                }
                rows.add(row);
            }
            JsonObject map = new JsonObject();
            // The boss is DungeonMap.bossHb, not a node: expose it explicitly so the agent can see it.
            // #100: boss availability and the reachable list come from the one read buildBody()
            // already took; asking the planner again here doubled the map scan and its WARNs.
            MapProbe probe = mapProbe == null ? MapProbe.probe() : mapProbe;
            if (probe == null) {
                // The planner could not answer at all; the block would be a lie without reachable.
                return null;
            }
            boolean bossAvailable = probe.bossAvailable;
            map.addProperty("boss_available", bossAvailable);
            map.addProperty("boss_present", probe.bossPresent);
            if (bossAvailable || probe.bossPresent) {
                JsonObject boss = new JsonObject();
                boss.addProperty("x", -1);
                boss.addProperty("y", AbstractDungeon.map.size());
                boss.addProperty("room", "MonsterRoomBoss");
                map.add("boss", boss);
            }
            map.addProperty("act", AbstractDungeon.actNum);
            map.addProperty("floor", AbstractDungeon.floorNum);
            map.addProperty("rows", AbstractDungeon.map.size());
            // One coordinate system everywhere: node y is the absolute map row (0-based from the
            // bottom), reachable uses the same absolute values, and current says where we stand.
            JsonObject here2 = new JsonObject();
            com.megacrit.cardcrawl.map.MapRoomNode standing = AbstractDungeon.currMapNode;
            if (standing != null) {
                here2.addProperty("x", standing.x);
                here2.addProperty("y", standing.y);
            } else {
                here2.add("x", JsonNull.INSTANCE);
                here2.add("y", JsonNull.INSTANCE);
            }
            map.add("current", here2);
            map.addProperty("from_row_start", mapRowOf(standing));
            map.add("from_row", rows);
            JsonArray reachable = new JsonArray();
            for (com.megacrit.cardcrawl.map.MapRoomNode node : probe.reachable) {
                JsonObject item = new JsonObject();
                item.addProperty("x", node.x);
                item.addProperty("y", node.y);
                reachable.add(item);
            }
            // Boss 不是 MapRoomNode（它是 DungeonMap.bossHb），所以 reachableNodes() 里永远没有它：
            // 到了末行 map.reachable 会是空数组，agent 只能靠 actions 里的「前往 Boss」猜。
            // 这里把 boss 坐标（x=-1, y=行数）补进 reachable，和 choose_map_node 的 payload 对齐。
            if (bossAvailable) {
                JsonObject bossNode = new JsonObject();
                bossNode.addProperty("x", -1);
                bossNode.addProperty("y", AbstractDungeon.map.size());
                reachable.add(bossNode);
            }
            map.add("reachable", reachable);
            return map;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Char-select view: which character is currently selected and what the alternatives are. */
    private static JsonObject buildCharSelect() {
        try {
            if (com.megacrit.cardcrawl.core.CardCrawlGame.mainMenuScreen == null
                    || com.megacrit.cardcrawl.core.CardCrawlGame.mainMenuScreen.charSelectScreen
                            == null) {
                return null;
            }
            com.megacrit.cardcrawl.screens.charSelect.CharacterSelectScreen screen =
                    com.megacrit.cardcrawl.core.CardCrawlGame.mainMenuScreen.charSelectScreen;
            if (screen.options == null) {
                return null;
            }
            JsonObject out = new JsonObject();
            JsonArray options = new JsonArray();
            String selected = null;
            int selectedIndex = -1;
            for (int i = 0; i < screen.options.size(); i++) {
                com.megacrit.cardcrawl.screens.charSelect.CharacterOption option =
                        screen.options.get(i);
                if (option == null) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("index", i);
                item.addProperty("id", option.c == null || option.c.chosenClass == null
                        ? null : option.c.chosenClass.name());
                item.addProperty("name", option.name);
                item.addProperty("selected", option.selected);
                item.addProperty("locked", option.locked);
                options.add(item);
                if (option.selected && selected == null) {
                    selected = option.c == null || option.c.chosenClass == null
                            ? null : option.c.chosenClass.name();
                    selectedIndex = i;
                }
            }
            out.add("options", options);
            if (selected == null) {
                out.add("selected", JsonNull.INSTANCE);
            } else {
                out.addProperty("selected", selected);
            }
            out.addProperty("selected_index", selectedIndex);
            out.addProperty("selected_name", selectedIndex >= 0
                    && screen.options.get(selectedIndex) != null
                    ? screen.options.get(selectedIndex).name : null);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Neow's blessings as an event-shaped block (title + option text). */
    private static JsonObject buildNeowEvent() {
        try {
            JsonObject out = new JsonObject();
            out.addProperty("id", "NeowEvent");
            com.megacrit.cardcrawl.rooms.AbstractRoom room = safeRoom();
            Object rawName = room == null || room.event == null
                    ? null : spirelink.util.FieldKit.fieldOrNull(room.event, "name");
            out.addProperty("name", rawName instanceof String && !((String) rawName).isEmpty()
                    ? (String) rawName : "涅奥");
            // Neow's text lives in the generic event dialog, not on the event object: without this the
            // block had a name and options but an empty text.
            // Issue #107: that dialog is static (one page for the whole process, still holding the
            // previous run's last event when a run ends without disposing it), so it may only answer
            // when this room's own event provably displayed that page. Otherwise "" - a Neow body made
            // of BigFish's lines is worse than no body at all.
            // Round 8 (QA 3/3 again) showed the round-7 proof was not one: GenericEventDialog's own
            // constructor is `ldc ""; putfield title`, so a freshly built event already carries a
            // String title, and NeowEvent never touches imageEventText at all (its bytecode only uses
            // its own roomEventText). Both facts are recorded in EventPayload.dialogTextFor; do not
            // reintroduce a "title is non-null" ownership test here. The only per-instance fallback
            // left is this event's own body field, which cannot be a previous run's page.
            String body = spirelink.state.EventPayload.dialogTextFor(
                    room == null ? null : room.event);
            if (body == null || body.trim().isEmpty()) {
                Object rawBody = room == null || room.event == null
                        ? null : spirelink.util.FieldKit.fieldOrNull(room.event, "body");
                body = rawBody instanceof String ? (String) rawBody : null;
            }
            out.addProperty("text", body == null ? "" : spirelink.ui.NoteHud.cleanMarkup(body));
            JsonArray options = new JsonArray();
            java.util.List<com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton> buttons =
                    spirelink.actions.ActionPlanner.dialogOptions();
            if (buttons != null) {
                for (int i = 0; i < buttons.size(); i++) {
                    com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton button =
                            buttons.get(i);
                    if (button == null) {
                        continue;
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("index", i);
                    Object rawMsg = spirelink.util.FieldKit.fieldOrNull(button, "msg");
                    item.addProperty("text", spirelink.ui.NoteHud.cleanMarkup(
                            rawMsg instanceof String ? (String) rawMsg : ""));
                    item.addProperty("disabled", button.isDisabled);
                    options.add(item);
                }
            }
            out.add("options", options);
            out.addProperty("option_count", options.size());
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code count} field of a serialized pile, or 0. */
    private static int countOf(com.google.gson.JsonElement pile) {
        try {
            if (pile != null && pile.isJsonObject() && pile.getAsJsonObject().has("count")) {
                return pile.getAsJsonObject().get("count").getAsInt();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return 0;
    }

    /** Human-visible map icon for a node room type. */
    private static String mapSymbolOf(String roomClass) {
        if (roomClass == null) {
            return "?";
        }
        switch (roomClass) {
            case "MonsterRoom":
                return "M";
            case "MonsterRoomElite":
                return "E";
            case "EventRoom":
                return "?";
            case "RestRoom":
                return "R";
            case "ShopRoom":
                return "$";
            case "TreasureRoom":
            case "TreasureRoomBoss":
                return "T";
            case "MonsterRoomBoss":
            case "VictoryRoom":
                return "B";
            default:
                return "?";
        }
    }

    /** Absolute row index of a map node (0 when unknown), so from_row offsets are unambiguous. */
    private static int mapRowOf(com.megacrit.cardcrawl.map.MapRoomNode node) {
        return node == null ? 0 : node.y;
    }

    /** {@code BossRelicSelectScreen.relics} as a list, or null when that screen is not up. */
    private static JsonArray buildBossReward() {
        try {
            if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.BOSS_REWARD
                    || AbstractDungeon.bossRelicScreen == null) {
                return null;
            }
            Object raw = spirelink.util.FieldKit.fieldOrNull(
                    AbstractDungeon.bossRelicScreen, "relics");
            if (!(raw instanceof java.util.List)) {
                return null;
            }
            java.util.List<?> relics = (java.util.List<?>) raw;
            JsonArray out = new JsonArray();
            for (int i = 0; i < relics.size(); i++) {
                Object entry = relics.get(i);
                if (!(entry instanceof com.megacrit.cardcrawl.relics.AbstractRelic)) {
                    continue;
                }
                com.megacrit.cardcrawl.relics.AbstractRelic relic =
                        (com.megacrit.cardcrawl.relics.AbstractRelic) entry;
                JsonObject item = new JsonObject();
                item.addProperty("index", i);
                item.addProperty("id", relic.relicId);
                item.addProperty("name", relic.name);
                item.addProperty("tier", relic.tier == null ? "" : relic.tier.name());
                RelicText.putText(item, relic.description);
                item.addProperty("description", RelicText.describe(relic.description));
                out.add(item);
            }
            return out;
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("buildBossReward", t);
            return null;
        }
    }

    private static String lastEventId;
    private static String lastEventName;

    /** {@code neow: {options:[{index,label}]}} while the blessing dialog is up. */
    private static JsonObject buildNeowBlock() {
        try {
            java.util.List<com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton> options =
                    spirelink.actions.ActionPlanner.dialogOptions();
            JsonArray list = new JsonArray();
            if (options != null) {
                for (int i = 0; i < options.size(); i++) {
                    com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton option = options.get(i);
                    if (option == null) {
                        continue;
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("index", i);
                    item.addProperty("label", option.msg == null ? "" : cleanUp(option.msg));
                    list.add(item);
                }
            }
            JsonObject out = new JsonObject();
            out.add("options", list);
            out.addProperty("option_count", list.size());
            return out;
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("buildNeowBlock", t);
            return null;
        }
    }

    /**
     * Renderer markup out of any prose field (potions, relics, option labels).
     *
     * <p>B2-18: this used to be a local {@code replaceAll("#.", "")}, which also ate a legitimate
     * character after a '#' and never repaired the CJK gaps the removal left. It is now the same
     * {@link ProseClean} pipeline cards and event text use.
     */
    private static String cleanUp(String text) {
        return ProseClean.clean(text);
    }

    /** Outgoing edges of a map node; never null. */
    private static java.util.List<com.megacrit.cardcrawl.map.MapEdge> edgesOfNode(
            com.megacrit.cardcrawl.map.MapRoomNode node) {
        java.util.List<com.megacrit.cardcrawl.map.MapEdge> out =
                new java.util.ArrayList<com.megacrit.cardcrawl.map.MapEdge>();
        if (node == null) {
            return out;
        }
        try {
            java.util.ArrayList<com.megacrit.cardcrawl.map.MapEdge> edges = node.getEdges();
            if (edges != null) {
                out.addAll(edges);
            }
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("edgesOfNode", t);
        }
        return out;
    }

    /** Combat reward list, or null when no reward screen is up. */
    private static JsonObject buildRewards() {
        try {
            if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.COMBAT_REWARD) {
                return null;
            }
            com.megacrit.cardcrawl.screens.CombatRewardScreen screen =
                    AbstractDungeon.combatRewardScreen;
            if (screen == null || screen.rewards == null) {
                return null;
            }
            // Issue #123 · C14-06: the per-item flag exists, but on this screen the agent reads the
            // action list, not rewards.items[] - so the "you must drop a potion first" rule has to be
            // stated once, at screen level.
            boolean beltFull = spirelink.actions.ActionPlanner.potionSlotsFull();
            AbstractPlayer rewardPlayer = AbstractDungeon.player;
            int beltUsed = rewardPlayer == null || rewardPlayer.potions == null
                    ? 0 : rewardPlayer.potions.size();
            int beltSlots = rewardPlayer == null ? 0 : Math.max(rewardPlayer.potionSlots, 0);
            JsonArray list = new JsonArray();
            for (int i = 0; i < screen.rewards.size(); i++) {
                com.megacrit.cardcrawl.rewards.RewardItem reward = screen.rewards.get(i);
                if (reward == null) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("index", i);
                item.addProperty("type", reward.type == null ? "UNKNOWN" : reward.type.name());
                item.addProperty("text", reward.text == null ? "" : reward.text);
                if (reward.goldAmt + reward.bonusGold > 0) {
                    item.addProperty("gold", reward.goldAmt + reward.bonusGold);
                }
                if (reward.potion != null) {
                    JsonObject potion = new JsonObject();
                    potion.addProperty("id", reward.potion.ID);
                    potion.addProperty("name", reward.potion.name);
                    item.add("potion", potion);
                    // 药水槽满时原版也不给拿（点了没反应），planner 就不会给 take_reward。
                    // 以前 rewards.items 照列，agent 按 index 去取必被拒（试玩 agent 实测）。
                    // 判据必须和 planner 用同一个（ActionPlanner.potionSlotsFull），
                    // 否则会出现「state 说拿不了、actions 却给了 take_reward」这种自相矛盾。
                    item.addProperty("takeable", !reward.isDone && !beltFull);
                    if (beltFull) {
                        item.addProperty("blocked_reason", "药水槽已满，先丢掉一瓶才能拿");
                    }
                }
                if (reward.relic != null) {
                    JsonObject relic = new JsonObject();
                    relic.addProperty("id", reward.relic.relicId);
                    relic.addProperty("name", reward.relic.name);
                    item.add("relic", relic);
                }
                if (reward.cards != null && !reward.cards.isEmpty()) {
                    com.megacrit.cardcrawl.cards.AbstractCard card = reward.cards.get(0);
                    if (card != null) {
                        JsonObject cardJson = new JsonObject();
                        cardJson.addProperty("uuid", String.valueOf(card.uuid));
                        cardJson.addProperty("id", card.cardID);
                        cardJson.addProperty("name", card.name);
                        item.add("card", cardJson);
                    }
                }
                list.add(item);
            }
            JsonObject rewards = new JsonObject();
            rewards.addProperty("count", list.size());
            rewards.add("items", list);
            if (beltFull) {
                // Issue #123 · C14-06: 药水满时这一屏只广告 discard_potion + proceed，而 proceed 的
                // 标签是「继续（放弃剩余奖励）」—— 没有一句话说明「丢一瓶就能拿」，agent 很容易当成
                // 只能放弃。screen 级写明规则，take_reward 会在这行字变成真的之后重新出现。
                rewards.addProperty("potion_blocked", potionBlockedSentence(beltUsed, beltSlots));
            }
            return rewards;
        } catch (Throwable t) {
            return null;
        }
    }

    private JsonObject buildCombat() {
        AbstractRoom room = safeRoom();
        MonsterGroup monsters = room == null ? null : AbstractDungeon.getMonsters();
        boolean inCombatRoom = room != null && room.phase == AbstractRoom.RoomPhase.COMBAT;
        boolean hasMonsters = monsters != null && monsters.monsters != null
                && !monsters.monsters.isEmpty();
        // Outside a combat room the monster list is the PREVIOUS fight (vanilla keeps it around), so
        // serving it made "combat" look alive when the room was already over.
        if (!inCombatRoom) {
            return null;
        }
        if (!hasMonsters) {
            return null;
        }

        AbstractPlayer player = AbstractDungeon.player;
        JsonObject combat = new JsonObject();
        combat.addProperty("round", GameActionManager.turn);
        // Alias: "turn" is the word humans use; both stay so old agents keep working.
        combat.addProperty("turn", GameActionManager.turn);

        JsonArray hand = new JsonArray();
        if (player != null && player.hand != null && player.hand.group != null) {
            int index = 0;
            for (AbstractCard card : player.hand.group) {
                hand.add(cardJson(card, index++, true));
            }
        }
        combat.add("hand", hand);
        // Issue #123 · C14-05: energy lives in the `player` block, so get_state{include:["combat"]}
        // had no number to reconcile "play_card is missing" against - QA had to infer "energy 0" from
        // the absence of an action. Both numbers are repeated here, plus how many cards in hand can
        // actually be played right now and a sentence when the answer is zero.
        int energy = EnergyPanel.getCurrentEnergy();
        int energyMax = player == null || player.energy == null ? 0 : player.energy.energy;
        combat.addProperty("energy", energy);
        combat.addProperty("energy_max", energyMax);
        int playableNow = 0;
        if (player != null && player.hand != null && player.hand.group != null) {
            for (AbstractCard card : player.hand.group) {
                try {
                    if (card != null && card.hasEnoughEnergy()) {
                        playableNow++;
                    }
                } catch (Throwable ignored) {
                    // A card whose cost cannot be computed is not counted as playable.
                }
            }
        }
        combat.addProperty("playable_cards", playableNow);
        int handSize = player == null || player.hand == null || player.hand.group == null
                ? 0 : player.hand.group.size();
        // Issue #124 · C15-05: the field is ALWAYS present in a combat block - a sentence when nothing
        // can be played, JSON null when nothing is blocked. QA reported "play_blocked: """ next to
        // "playable_cards: 10"; the empty string came from a probe doing (x or ""), but a stable shape
        // ("null = nothing blocked") removes the ambiguity for good.
        String blocked = playableNow == 0 && handSize > 0
                ? playBlockedSentence(energy, energyMax, handSize) : null;
        combat.add("play_blocked", blocked == null || blocked.trim().isEmpty()
                ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(blocked));

        JsonObject piles = new JsonObject();
        piles.add("draw", pile(player == null ? null : player.drawPile));
        piles.add("discard", pile(player == null ? null : player.discardPile));
        piles.add("exhaust", pile(player == null ? null : player.exhaustPile));
        piles.add("limbo", pile(player == null ? null : player.limbo));
        combat.add("piles", piles);
        // Flat counts next to the nested block: cheap for an agent to read, and the nested shape stays
        // for compatibility.
        JsonObject pilesSummary = new JsonObject();
        pilesSummary.addProperty("draw", countOf(piles.get("draw")));
        pilesSummary.addProperty("discard", countOf(piles.get("discard")));
        pilesSummary.addProperty("exhaust", countOf(piles.get("exhaust")));
        combat.add("piles_summary", pilesSummary);

        JsonArray monsterArray = new JsonArray();
        if (monsters != null && monsters.monsters != null) {
            boolean hideIntent = player != null && player.hasRelic(RunicDome.ID);
            for (int i = 0; i < monsters.monsters.size(); i++) {
                AbstractMonster monster = monsters.monsters.get(i);
                if (monster == null) {
                    continue;
                }
                monsterArray.add(MonsterSerializer.toJson(
                        MonsterSerializer.fromMonster(monster, i, hideIntent)));
            }
        }
        combat.add("monsters", monsterArray);
        combat.add("enemies", monsterArray);
        return combat;
    }

    private JsonObject pile(CardGroup group) {
        JsonObject json = new JsonObject();
        json.addProperty("count", group == null ? 0 : group.size());
        // Counts only in P0; full pile bodies are the P2 / include=deck block.
        json.add("cards", new JsonArray());
        return json;
    }

    /** Index of the orb that the next evoke would take (leftmost non-empty slot), or -1. */
    private static int firstOrbIndex(AbstractPlayer player) {
        if (player == null || player.orbs == null) {
            return -1;
        }
        for (int i = 0; i < player.orbs.size(); i++) {
            AbstractOrb orb = player.orbs.get(i);
            if (orb != null && orb.ID != null) {
                return i;
            }
        }
        return -1;
    }

    /** Focus (聚焦) modifier: it is already baked into passive/evoke, but the agent wants the number. */
    private static int focusAmount(AbstractPlayer player) {
        try {
            if (player == null) {
                return 0;
            }
            com.megacrit.cardcrawl.powers.AbstractPower focus = player.getPower("Focus");
            return focus == null ? 0 : focus.amount;
        } catch (Throwable t) {
            return 0;
        }
    }

    private JsonObject cardJson(AbstractCard card, int index, boolean inCombat) {
        boolean playable;
        String reason = null;
        if (inCombat) {
            // damage / block / magicNumber must be the post-applyPowers values (design 6).
            try {
                card.applyPowers();
            } catch (Throwable t) {
                log.warn("applyPowers 失败 card=" + card.cardID + ": " + t);
            }
            playable = canPlay(card);
            if (!playable) {
                reason = unplayableReason(card);
            }
        } else {
            playable = true;
        }

        CardSerializer.CardView view = CardSerializer.fromCard(card);
        view.playable = inCombat ? Boolean.valueOf(playable) : null;
        view.unplayableReason = reason;
        view.index = Integer.valueOf(index);
        // P0 has no gaze / seen-tracking yet; keep the field so the schema is stable.
        view.needsInspect = false;
        return CardSerializer.toJson(view);
    }

    private boolean canPlay(AbstractCard card) {
        try {
            return card.canUse(AbstractDungeon.player, null);
        } catch (Throwable t) {
            log.warn("canUse 失败 card=" + card.cardID + ": " + t);
            return false;
        }
    }

    private String unplayableReason(AbstractCard card) {
        if (card.costForTurn == -2) {
            return "unplayable";
        }
        if (card.costForTurn >= 0 && card.costForTurn > EnergyPanel.getCurrentEnergy()) {
            return "energy";
        }
        if (card.target == AbstractCard.CardTarget.ENEMY && !hasLivingEnemy()) {
            return "target";
        }
        if (card.type == AbstractCard.CardType.STATUS || card.type == AbstractCard.CardType.CURSE) {
            return "unplayable";
        }
        return "other";
    }

    private boolean hasLivingEnemy() {
        AbstractRoom room = safeRoom();
        MonsterGroup monsters = room == null ? null : AbstractDungeon.getMonsters();
        if (monsters == null || monsters.monsters == null) {
            return false;
        }
        for (AbstractMonster monster : monsters.monsters) {
            if (monster != null && !monster.isDeadOrEscaped()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- state log

    private void appendStateLine(String ui) {
        AbstractPlayer player = AbstractDungeon.player;
        String hp = "-/-";
        StringBuilder handUuids = new StringBuilder();
        if (player != null) {
            hp = player.currentHealth + "/" + player.maxHealth;
            if (player.hand != null && player.hand.group != null) {
                boolean first = true;
                for (AbstractCard card : player.hand.group) {
                    if (!first) {
                        handUuids.append(',');
                    }
                    handUuids.append(card.uuid == null ? "null" : card.uuid.toString());
                    first = false;
                }
            }
        }
        log.stateLine("version=" + version + " ui=" + ui + " hp=" + hp
                + " hand_uuids=" + handUuids);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Deep copy of a JSON tree. Gson 2.8.5 keeps {@code JsonElement.deepCopy()} non-public on the
     * concrete types, so round-trip through the parser instead.
     */
    /**
     * Issue #127: re-run note delivery on a body handed out again (cache hit).
     *
     * <p>The cached body was annotated once, when it was built. Serving that copy verbatim repeats
     * {@code note_state:"new"} with the full text on every poll of a static screen - exactly the
     * repetition the agent complained about. Re-annotating claims the versions again, so only the
     * first serve carries the text and the rest say {@code "seen"}.</p>
     */
    static void refreshNotes(JsonObject body) {
        if (body == null) {
            return;
        }
        try {
            JsonObject hints = body.getAsJsonObject("hints");
            if (hints != null) {
                hints.remove("note_history");
            }
            NoteAnnotator.annotate(body);
            NoteAnnotator.explainHistory(body);
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("refreshNotes", t);
        }
    }

    private static JsonObject copy(JsonObject source) {
        return new com.google.gson.JsonParser().parse(source.toString()).getAsJsonObject();
    }

    /**
     * Signature of everything the harness can observe: the derived {@code ui}, the main-menu screen
     * and the current legal action list. Any change here must bump {@code state_version}, otherwise
     * the version gate would accept a stale {@code act} (see P1 review).
     */
    private String computeSignature(String ui) {
        StringBuilder sb = new StringBuilder(200);
        sb.append(CardCrawlGame.mode).append('|');
        sb.append(ui).append('|');
        sb.append(mainMenuScreenName()).append('|');
        sb.append(AbstractDungeon.screen).append('|');
        sb.append(AbstractDungeon.isScreenUp).append('|');
        AbstractRoom room = safeRoom();
        sb.append(room == null || room.phase == null ? "-" : room.phase.name()).append('|');
        sb.append(AbstractDungeon.actNum).append('|').append(AbstractDungeon.floorNum).append('|');

        AbstractPlayer player = AbstractDungeon.player;
        if (player != null) {
            sb.append(player.currentHealth).append('/').append(player.maxHealth).append('|');
            sb.append(player.currentBlock).append('|');
            sb.append(player.gold).append('|');
            sb.append(EnergyPanel.getCurrentEnergy()).append('|');
            sb.append(player.stance == null ? "-" : player.stance.ID).append('|');
            if (player.hand != null && player.hand.group != null) {
                for (AbstractCard card : player.hand.group) {
                    sb.append(card.uuid).append(',');
                }
            }
            sb.append('|');
            sb.append(player.relics == null ? 0 : player.relics.size()).append('|');
            sb.append(player.powers == null ? 0 : player.powers.size()).append('|');
            sb.append(player.drawPile == null ? 0 : player.drawPile.size()).append('|');
            sb.append(player.discardPile == null ? 0 : player.discardPile.size()).append('|');
            sb.append(player.exhaustPile == null ? 0 : player.exhaustPile.size()).append('|');
            sb.append(player.orbs == null ? 0 : player.orbs.size()).append('|');
            // Potions are observable: drinking/discarding one must bump the version even when no
            // other tracked value changes.
            if (player.potions != null) {
                for (com.megacrit.cardcrawl.potions.AbstractPotion potion : player.potions) {
                    sb.append(potion == null ? "-" : potion.ID).append(',');
                }
            }
            sb.append('|');
        }
        sb.append(com.megacrit.cardcrawl.actions.GameActionManager.turn).append('|');
        if (room != null) {
            MonsterGroup monsters = AbstractDungeon.getMonsters();
            if (monsters != null && monsters.monsters != null) {
                for (AbstractMonster monster : monsters.monsters) {
                    if (monster == null) {
                        continue;
                    }
                    sb.append(monster.id).append(':').append(monster.currentHealth).append(':')
                            .append(monster.currentBlock).append(':')
                            .append(monster.intent == null ? "-" : monster.intent.name()).append(',');
                }
            }
        }
        // Legal actions are part of the observable state: if the list changes (a character gets
        // selected, a Neow option appears, a map node opens), the version must move.
        sb.append("|actions:")
                .append(spirelink.actions.ActionSpec.digest(spirelink.actions.ActionPlanner.actionsFor(ui)));
        // Issue #75 round 2: only the event's DECISION facts are part of the version (event id, the
        // option set, which options are disabled). The body text, the typing animation and a story
        // page change are body-only: they refresh the cached event block through bodyKey and must
        // never move state_version. progressToken() (text included) is exactly what caused the QA
        // 28 -> 29 -> 30 walk on one Bonfire page change - it is now body-key material only.
        sb.append("|evt=").append(spirelink.state.EventPayload.decisionToken());
        // GremlinMatchGame: a flip can leave text and options untouched, so track it explicitly.
        if (spirelink.events.MatchGame.active()) {
            sb.append("|mg=").append(spirelink.events.MatchGame.digest());
        }
        // Card reward: the three offers and the deck size, so taking a card is observable.
        try {
            com.megacrit.cardcrawl.screens.CardRewardScreen cr = AbstractDungeon.cardRewardScreen;
            if (cr != null && cr.rewardGroup != null) {
                sb.append("|cr=").append(cr.rewardGroup.size());
                for (com.megacrit.cardcrawl.cards.AbstractCard card : cr.rewardGroup) {
                    sb.append(',').append(String.valueOf(card.uuid));
                }
            }
            if (AbstractDungeon.player != null && AbstractDungeon.player.masterDeck != null) {
                sb.append("|deck=").append(AbstractDungeon.player.masterDeck.size());
            }
        } catch (Throwable ignored) {
            // signature is best effort
        }
        return sb.toString();
    }

    private static String mainMenuScreenName() {
        com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen menu =
                CardCrawlGame.mainMenuScreen;
        if (menu == null || menu.screen == null) {
            return "-";
        }
        return menu.screen.name();
    }

    /**
     * Derived {@code ui} per design 7.2 v1.0 (fixed priority: in_game → screen → room → other).
     * Values are lowercase identifiers; unmapped screens log one line and fall back to
     * {@code other} instead of guessing.
     */
    private String deriveUi() {
        return deriveUi(true);
    }

    /**
     * Issue #124 · C15-01: the ui the <b>reads</b> are reporting right now, substitution included.
     *
     * <p>Rejections are built on the request thread and must not touch the game, so they used to fall
     * back to the cached stable body - whose ui is the last <em>ready</em> frame ("combat") and therefore
     * contradicted the same-tick {@code get_state.meta.ui} / {@code legal_actions.ui} ("other") during a
     * transition. This accessor is written on the main thread by {@link #deriveUi(boolean)} and read
     * anywhere; {@code null} means "no frame derived yet".</p>
     */
    public static String reportedUi() {
        return lastReportedUi;
    }

    /** Written on the main thread in {@link #deriveUi(boolean)}, read by request-thread rejections. */
    private static volatile String lastReportedUi;

    /**
     * Issue #122 · C12-01: the load window ends when a run screen is up, and the ui is the only
     * signal that says that without lying (mode turns GAMEPLAY one frame before the screen switches,
     * which is exactly the frame that still has to answer「存档正在载入」). So every derived ui is
     * handed to {@link LoadingWindow}, which owns the "we clicked continue_run N ms ago" marker.
     */
    private String noteUi(String ui) {
        LoadingWindow.noteUi(ui);
        return ui;
    }

    /**
     * @param logUnmapped log a warning for screens we do not map (skip it on the per-frame
     *                    signature path to avoid spamming the log)
     */
    private String deriveUi(boolean logUnmapped) {
        String raw = deriveUiRaw(logUnmapped);
        noteUi(raw);
        // Issue #123 · S2: while we are leaving the run, the dungeon that is still on screen is not a
        // decision point - mode is still GAMEPLAY and the old battle/map data is intact, so reporting
        // it would advertise actions for a screen that is on its way out (QA saw ui=idle +「打开地图」,
        // then ui=map + choose_map_node, and the action was then rejected because the menu was up).
        RunExitWindow.noteUi(raw);
        String reported = RunExitWindow.active() ? "other" : raw;
        // Issue #124 · C15-01: remember what the reads report, so a rejection built on the request
        // thread can answer the SAME ui instead of digging the stale one out of the cached body
        // (QA saw act's rejection say ui="combat" while get_state/legal_actions said "other").
        lastReportedUi = reported;
        return reported;
    }

    private String deriveUiRaw(boolean logUnmapped) {
        // Tutorial / FTUE tip blocks everything else (P1 opening chain).
        if (isTutorialTipUp()) {
            return "tutorial_tip";
        }
        if (!inGame()) {
            return mainMenuUi();
        }
        AbstractDungeon.CurrentScreen screen = AbstractDungeon.screen;
        if (spirelink.ui.DeckPeek.active()
                && isPeekViewScreen(screen == null ? null : screen.name())) {
            // A peek is not a decision point: keep reporting the screen the agent is deciding on, so
            // legal_actions stays exactly the same while the human sees the list.
            // Issue #115: this used to match MASTER_DECK_VIEW only, so the three pile peeks
            // (include:["discard_pile"] / ["exhaust_pile"] / ["draw_pile"]) fell through to
            // UiNames' tutorial_tip for the whole 4 s hold - legal_actions shrank to [dismiss_tip]
            // while play_card / end_turn (and the shop's buy) still ran. All four views the peek
            // opens are the same read-only overlay, so all four report the ui underneath.
            String underlying = spirelink.ui.DeckPeek.underlyingUi();
            if (underlying != null) {
                return underlying;
            }
        }
        if (screen != null && screen != AbstractDungeon.CurrentScreen.NONE) {
            String mapped = mapScreen(screen);
            if (mapped != null) {
                return mapped;
            }
            if (logUnmapped) {
                log.warn("ui: 未映射的 screen_type=" + screen + "，暴露成 unknown:<screen>");
            }
            return "unknown:" + screen.name();
        }

        AbstractRoom room = safeRoom();
        if (room == null) {
            if (logUnmapped) {
                log.warn("ui: screen_type=NONE 但取不到当前房间，按设计回退 other");
            }
            return "other";
        }
        // Victory is a room, not necessarily a screen: on the winning frame AbstractDungeon.screen is
        // still NONE while the player is already inside VictoryRoom. Keying the ui off screen_type
        // alone made the end of a won run look like "idle" with no executable action.
        if (room instanceof com.megacrit.cardcrawl.rooms.VictoryRoom
                || AbstractDungeon.screen == AbstractDungeon.CurrentScreen.VICTORY
                || AbstractDungeon.victoryScreen != null) {
            return "victory";
        }
        if (room.phase == AbstractRoom.RoomPhase.COMBAT) {
            return "combat";
        }
        String roomType = room.getClass().getSimpleName();
        // Neow blessing: own ui so the harness knows the opening is not finished yet.
        if ("NeowRoom".equals(roomType) || room.event instanceof com.megacrit.cardcrawl.neow.NeowEvent) {
            return "neow";
        }
        if ("EventRoom".equals(roomType)) {
            return "event";
        }
        if ("RestRoom".equals(roomType)) {
            return "rest";
        }
        if ("TreasureRoom".equals(roomType) || "TreasureRoomBoss".equals(roomType)) {
            // The act boss treasure room holds the BossChest that grants the boss relic; it must never
            // look like plain "idle", otherwise the only action was a bare proceed that walked past.
            return "treasure";
        }
        if ("ShopRoom".equals(roomType)) {
            // After a purge the screen returns to NONE while the merchant is still active: keep ui=shop
            // so buy/purge stay available without a second open_shop.
            // Only a real SHOP screen counts as ui=shop; an active merchant with screen=NONE (right
            // after a purge) stays shop_room, which keeps meta.screen_type and ui consistent.
            return AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SHOP
                    ? "shop" : "shop_room";
        }
        // Issue #42: the boss is dead, the map is not open yet and the only action left is proceed.
        // Reporting "idle" there told the agent nothing had happened, which breaks the "every screen
        // has a name" rule. TreasureRoomBoss (the boss-chest reward screen) is handled above and is
        // deliberately left alone.
        if (bossRoomCleared(roomType, room.phase)) {
            return UI_BOSS_CLEARED;
        }
        return "idle";
    }

    /**
     * Issue #123 · C14-05: why {@code play_card} disappeared, in one sentence.
     *
     * <p>QA hit "hand has 3 cards, {@code play_card} is gone, {@code awaiting} still says playing, no
     * explanation anywhere" and could only infer "energy 0" from the absence of an action. Pure, so the
     * wording is pinned by a unit test.</p>
     */
    static String playBlockedSentence(int energy, int energyMax, int handSize) {
        return "能量不足（energy=" + energy + "/" + energyMax + "）：手牌 " + handSize
                + " 张这回合都打不出，只能 end_turn 或先用药水；play_card 因此不在 legal_actions 里，"
                + "不是接口出错。";
    }

    /**
     * Issue #123 · C14-06: why a potion reward cannot be taken, in one sentence.
     *
     * <p>Without it the reward screen only advertised {@code discard_potion} + {@code proceed}, whose
     * label is 「继续（放弃剩余奖励）」 - an agent reads that as "the only way forward".</p>
     */
    static String potionBlockedSentence(int used, int slots) {
        return "药水槽已满（" + used + "/" + slots + "）：药水奖励要先用 discard_potion 丢一瓶才能领，"
                + "丢完再读 legal_actions 就会出现 take_reward；现在直接 proceed 等于放弃这份奖励"
                + "（含钥匙/遗物）。";
    }

    /** {@code ui} for "boss fight over, nothing left but proceed" (issue #42). */
    static final String UI_BOSS_CLEARED = "boss_cleared";

    /**
     * True when the current room is a finished boss fight (issue #42).
     *
     * <p>Boss-only on purpose: a plain {@code MonsterRoom} that reaches {@code COMPLETE} still has
     * its reward screen (and keeps its old ui), and the {@code TreasureRoom} / {@code
     * TreasureRoomBoss} reward screens keep their own logic.
     */
    static boolean bossRoomCleared(String roomType, AbstractRoom.RoomPhase phase) {
        return "MonsterRoomBoss".equals(roomType) && phase == AbstractRoom.RoomPhase.COMPLETE;
    }

    /** Main-menu family: mode panel / character select get their own {@code ui} (P1). */
    private String mainMenuUi() {
        com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen menu =
                com.megacrit.cardcrawl.core.CardCrawlGame.mainMenuScreen;
        if (menu != null && menu.screen != null) {
            return UiNames.ofMenu(menu.screen.name());
        }
        return "main_menu";
    }

    /** True when a tutorial tip is on screen and must be dismissed before anything else. */
    public static boolean isTutorialTipUp() {
        return AbstractDungeon.ftue != null
                && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.FTUE;
    }

    private static String mapScreen(AbstractDungeon.CurrentScreen screen) {
        return UiNames.ofScreen(screen.name());
    }

    /**
     * The four read-only card views {@code DeckPeek} opens (deck / discard / exhaust / draw).
     *
     * <p>Issue #115: {@code deriveUi} may only report "the ui underneath" while the screen on top is
     * one of these <b>and</b> the peek says it put it there — otherwise the peek's own view screen
     * would mask the real decision point. String-based and pure so the list is unit tested without a
     * game (see {@code DismissTipTest}, which pins the same four names for #50).</p>
     */
    static boolean isPeekViewScreen(String screenName) {
        return "MASTER_DECK_VIEW".equals(screenName)
                || "DISCARD_VIEW".equals(screenName)
                || "EXHAUST_VIEW".equals(screenName)
                || "GAME_DECK_VIEW".equals(screenName);
    }

    private static boolean inGame() {
        return CardCrawlGame.mode == CardCrawlGame.GameMode.GAMEPLAY
                && AbstractDungeon.player != null;
    }

    /**
     * Room lookup that never touches {@code AbstractDungeon.getCurrRoom()} when there is no node:
     * that method dereferences {@code currMapNode} (line 809) and throws NPE on the character-select
     * screen, which is how the whole {@code memory} block kept coming back null.
     */
    private static AbstractRoom safeRoom() {
        try {
            if (AbstractDungeon.currMapNode == null) {
                return null;
            }
            return AbstractDungeon.getCurrRoom();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean safeCanUse(AbstractPotion potion) {
        try {
            return potion.canUse();
        } catch (Throwable t) {
            log.warn("potion.canUse 失败 id=" + potion.ID + ": " + t);
            return false;
        }
    }

    private boolean safeCanDiscard(AbstractPotion potion) {
        try {
            return potion.canDiscard();
        } catch (Throwable t) {
            log.warn("potion.canDiscard 失败 id=" + potion.ID + ": " + t);
            return false;
        }
    }

    private String safeSeedString(long seed) {
        try {
            return SeedHelper.getString(seed);
        } catch (Throwable t) {
            return Long.toString(seed);
        }
    }
}
