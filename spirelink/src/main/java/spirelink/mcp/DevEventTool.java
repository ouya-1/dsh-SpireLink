package spirelink.mcp;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.rooms.EventRoom;

import java.util.List;

/**
 * Test-only escape hatch: jump straight into any event class so the whole event surface can be
 * exercised without playing to it. Only registered while {@code test_mode=true}.
 *
 * <h3>Issue #89 — the same room-switch hole as #87</h3>
 * <p>This tool used to close the current screen and swap {@code currMapNode.room} by hand, exactly
 * the way {@code dev_room} did before #87. That leaves the room it is leaving alive: a campfire's
 * {@code CampfireSmithEffect} keeps ticking in {@code AbstractDungeon.effectList}, and its finishing
 * branch casts {@code AbstractDungeon.getCurrRoom()} to {@code RestRoom} — a
 * {@code ClassCastException: ... cannot be cast to RestRoom} thrown from the game loop as soon as an
 * event room (or a shop) is mounted under it.</p>
 *
 * <p>So the switch is now <b>the same plan {@code dev_room} uses</b>: {@link DevRoomTool.RoomFacts}
 * (reads only) → {@link DevRoomTool#planSwitch} (pure, ordered steps) →
 * {@link DevRoomTool#runSwitch} (applies exactly that order, nothing else). The gate moved into the
 * pure {@link #gate}, which keeps "被拒 = 零副作用" (#64 / #87) assertable: every rejection is decided
 * from plain facts, and the busy probe — the one input with a documented side effect
 * ({@code RoomGuard.busyReason()} self-heals a stuck action queue) — is not even consulted once an
 * earlier check has rejected.</p>
 */
public final class DevEventTool {

    private static final String PREFIX = "com.megacrit.cardcrawl.events.";
    private static final String[] PACKAGES = {"exordium.", "city.", "beyond.", "shrines.", ""};

    private DevEventTool() {
    }

    // ------------------------------------------------------------ gate (pure)

    /** Either a rejection, or permission to run the apply phase. */
    static final class Gate {

        /** {@code null} when the call may proceed. */
        final String errorCode;
        final String errorMessage;

        private Gate(String errorCode, String errorMessage) {
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        static Gate pass() {
            return new Gate(null, null);
        }

        static Gate reject(String code, String message) {
            return new Gate(code, message);
        }

        boolean ok() {
            return errorCode == null;
        }
    }

    /**
     * Where "may we teleport right now?" comes from.
     *
     * <p>An interface rather than a boolean so the gate stays pure <em>and</em> so a rejected call can
     * be proven not to have asked: {@code RoomGuard.busyReason()} carries a deliberate self-heal (it
     * force-clears a stuck action queue), therefore "a rejection does not call it" is part of
     * "被拒 = 零副作用" and is asserted by the unit tests.</p>
     */
    interface BusyProbe {

        /** @return {@code null} when the teleport is allowed, otherwise a short human readable reason */
        String reasonOrNull();
    }

    /**
     * Pure: every rejection {@code dev_event} can produce, decided from its arguments only.
     *
     * <p>Order is deliberate — the cheapest facts first and the probe last, so a rejection never pays
     * for the probe's side effect. Nothing here reads or writes {@code AbstractDungeon}: the caller
     * hands in what it read ({@link #screenName()}, {@code currMapNode != null}, the resolved class).</p>
     *
     * @param id         caller supplied event id (simple name or fully qualified class name)
     * @param resolvable whether {@link #resolve} found an {@code AbstractEvent} subclass for it
     * @param testMode   {@code TestMode.enabled()} — the dev tools exist only in test mode
     * @param screen     {@code AbstractDungeon.screen.name()}, {@code null} when unreadable
     * @param hasMapNode whether {@code AbstractDungeon.currMapNode} exists (a run is in progress)
     * @param busy       busy probe, or {@code null} for {@code force=true} (used as "do not ask")
     */
    static Gate gate(String id, boolean resolvable, boolean testMode, String screen,
            boolean hasMapNode, BusyProbe busy) {
        if (!testMode) {
            return Gate.reject("DEV_TOOL_DISABLED", null);
        }
        if (id == null || id.isEmpty()) {
            return Gate.reject("EVENT_NOT_FOUND", "缺少 id");
        }
        if (!resolvable) {
            return Gate.reject("EVENT_NOT_FOUND", "找不到事件类: " + id);
        }
        if (isRunOverScreen(screen)) {
            return Gate.reject("NOT_READY", "当前在结算界面（" + screen
                    + "），不能切换事件；请先回主菜单并开始新的一局");
        }
        if (!hasMapNode) {
            return Gate.reject("NOT_READY", "当前没有地图节点（还没进地牢）");
        }
        if (busy != null) {
            String reason = busy.reasonOrNull();
            if (reason != null) {
                return Gate.reject("NOT_READY", "现在不能切事件: " + reason);
            }
        }
        return Gate.pass();
    }

    /** A run-over screen keeps the old room mounted; switching the room under it is meaningless. */
    static boolean isRunOverScreen(String screen) {
        return "DEATH".equals(screen) || "VICTORY".equals(screen) || "CREDITS".equals(screen);
    }

    // ------------------------------------------------------------ run progress (issue #117)

    /**
     * {@code act} argument for "not specified": mount under whatever act the run is in, and do not
     * compare it to anything (issue #117). {@code ToolCatalog#devEvent} passes this when the caller
     * leaves {@code act} out, so a plain {@code dev_event{id:"BigFish"}} never logs a mismatch that
     * nobody asked for.
     */
    static final int ACT_UNSPECIFIED = -1;

    /**
     * Which act the event is mounted under: <b>always the act the run is really in</b>.
     *
     * <p>Issue #117: this tool used to write {@code AbstractDungeon.actNum = act} in its swap step,
     * and the {@code act} argument defaults to 1 ({@code ToolCatalog#devEvent}), so calling
     * {@code dev_event{id:"BigFish"}} on the act-2 map made {@code meta.act} report 1 over a map that
     * is still the act-2 one. act / floor / the map are <em>run progress</em>: a test hatch may swap
     * the room, never the act. The requested act therefore changes nothing — the event class is
     * instantiated directly, which never needed the act number.</p>
     *
     * <p>Pure so the rule is asserted without a game.</p>
     *
     * @param requested the {@code act} argument (kept for the caller, never applied); an act that is
     *                  not the current one is only reported, see {@link #actMismatchNote(int, int)}
     * @param currentAct {@code AbstractDungeon.actNum} as read, read-only
     * @return the act the event actually sits in
     */
    static int eventAct(int requested, int currentAct) {
        return currentAct;
    }

    /**
     * The WARN text for「请求的幕和当前幕不一致」, or {@code null} when they agree.
     *
     * <p>Issue #117 chose "只换房间" over "拒绝": a cross-act {@code dev_event} still mounts the event
     * (that is what the tool is for), it just never moves the run's act. Pure, so the wording is
     * pinned: it has to say both numbers out loud or a QA reader cannot tell why nothing changed.</p>
     */
    static String actMismatchNote(int requested, int currentAct) {
        if (requested == ACT_UNSPECIFIED || requested == currentAct) {
            return null;
        }
        return "dev_event: 请求的幕 " + requested + " 与当前幕 " + currentAct
                + " 不一致 —— 只换房间，不改 act/floor/地图推进状态（issue #117）";
    }

    private static String screenName() {
        try {
            return AbstractDungeon.screen == null ? null : AbstractDungeon.screen.name();
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev_event: 读取屏幕失败: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------ tool

    public static JsonObject enter(String id, int act) {
        return enter(id, act, false);
    }

    /**
     * @param force when true the busy check is skipped and any lingering action queue is dropped first
     *              (test mode only); this is the recovery path after a stuck action
     */
    public static JsonObject enter(String id, int act, final boolean force) {
        // ---- gate: every rejection is decided here, before a single byte is written (#64 / #87)
        final Class<?> clazz = resolve(id);
        Gate gate = gate(id, clazz != null, spirelink.util.TestMode.enabled(), screenName(),
                AbstractDungeon.currMapNode != null, force ? null : new BusyProbe() {
                    @Override
                    public String reasonOrNull() {
                        return spirelink.util.RoomGuard.busyReason();
                    }
                });
        if (!gate.ok()) {
            return err(gate.errorCode, gate.errorMessage);
        }
        // ---- apply phase: only reachable when the gate said yes
        if (force) {
            spirelink.util.RoomGuard.forceClear();
            spirelink.util.Diag.warn("dev_event force=true：已清空动作队列");
        }
        // Issue #117: read the run's act, never write it. Everything below is "one room, same act".
        final int currentAct = AbstractDungeon.actNum;
        final int mountedAct = eventAct(act, currentAct);
        final String actNote = actMismatchNote(act, currentAct);
        if (actNote != null) {
            spirelink.util.Diag.warn(actNote);
        }
        final com.megacrit.cardcrawl.rooms.AbstractRoom previousRoom =
                AbstractDungeon.currMapNode.room;
        // Issue #89: the room we are leaving is torn down with #87's plan (screen, stale effect
        // queues, campfire handles, grid leftovers, action queue) before the event room is mounted.
        final DevRoomTool.RoomFacts facts = DevRoomTool.RoomFacts.capture(previousRoom);
        final List<DevRoomTool.SwitchStep> steps = DevRoomTool.planSwitch(true, facts);
        final EventRoom[] mounted = new EventRoom[1];
        try {
            // Shared with dev_room: both tools move the player, so they share one cooldown clock.
            spirelink.util.RoomGuard.noteSwitch();
            String done = DevRoomTool.runSwitch(steps, previousRoom, new DevRoomTool.RoomMount() {
                @Override
                public void swap() throws Throwable {
                    // Issue #117: NO progress state here. act / floor / the map belong to the run;
                    // this tool only replaces the room. (The old line was
                    // "AbstractDungeon.actNum = act", with act defaulting to 1, so a dev_event on the
                    // act-2 map rewrote meta.act to 1 and left the run describing a dungeon it was
                    // not in.)
                    // Some events (SecretPortal) teleport into MonsterRoomBoss, whose onPlayerEntry
                    // does bossList.remove(0); an empty list would kill the render loop.
                    spirelink.util.BossGuard.ensureBossList();
                    EventRoom room = new EventRoom();
                    safeRoom = room;
                    mounted[0] = room;
                    AbstractDungeon.currMapNode.room = room;
                }

                @Override
                public void open() throws Throwable {
                    mountEvent(mounted[0], clazz);
                }
            });
            spirelink.util.Diag.info("dev_event: 切房步骤=" + steps + " 执行=" + done
                    + " 现场[" + facts.describe() + "]");
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("id", id);
            out.addProperty("class", clazz.getName());
            out.addProperty("room", mounted[0] == null ? "EventRoom"
                    : mounted[0].getClass().getSimpleName());
            // Issue #117: report the act the event really sits in, plus what was asked for, so a
            // caller can see that the act argument no longer moves the run.
            out.addProperty("act", mountedAct);
            if (act != ACT_UNSPECIFIED) {
                out.addProperty("act_requested", act);
            }
            if (actNote != null) {
                out.addProperty("act_note", actNote);
            }
            spirelink.ui.AgentHud.push("调试事件：" + simpleName(id));
            return out;
        } catch (Throwable t) {
            // An EventRoom with a null event is a time bomb: AbstractRoom.update() dereferences it on
            // the very next frame. Always leave a working event behind before reporting the failure.
            ensureSafeEvent();
            return err("NOT_READY", "进入事件失败: " + t);
        }
    }

    /**
     * Mounts the requested event on the room the {@code SWAP_ROOM} step just installed.
     *
     * <p>Never calls {@code room.onPlayerEntry()}: it runs {@code AbstractDungeon.generateEvent()},
     * which consumes the event pool and can return null (after ~20 jumps), leaving
     * {@code room.event == null} — exactly the NPE that killed {@code AbstractRoom.update()} one
     * frame later.</p>
     */
    private static void mountEvent(EventRoom room, Class<?> clazz) throws Exception {
        if (room == null) {
            throw new IllegalStateException("房间没挂上（切房计划没有执行 SWAP_ROOM）");
        }
        room.phase = com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.EVENT;
        if (AbstractDungeon.overlayMenu != null
                && AbstractDungeon.overlayMenu.proceedButton != null) {
            AbstractDungeon.overlayMenu.proceedButton.hide();
        }
        // optionList is static: clear it so only the target event's options survive.
        new com.megacrit.cardcrawl.events.RoomEventDialog().clear();
        AbstractEvent instance = (AbstractEvent) clazz.getDeclaredConstructor().newInstance();
        room.event = instance;
        instance.onEnterRoom();
    }

    private static EventRoom safeRoom;

    /** Guarantees the room currently mounted has a non-null event. */
    private static void ensureSafeEvent() {
        try {
            com.megacrit.cardcrawl.rooms.AbstractRoom current = AbstractDungeon.getCurrRoom();
            if (!(current instanceof EventRoom)) {
                return;
            }
            EventRoom room = (EventRoom) current;
            if (room.event != null) {
                return;
            }
            new com.megacrit.cardcrawl.events.RoomEventDialog().clear();
            room.event = new com.megacrit.cardcrawl.events.exordium.BigFish();
            room.phase = com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.EVENT;
            room.event.onEnterRoom();
            spirelink.util.Diag.warn("dev_event: 事件为空，已塞入安全事件 BigFish 避免 update() 空指针");
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev_event: 安全事件兜底失败: " + t);
        }
    }

    private static Class<?> resolve(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (id.indexOf('.') > 0) {
            try {
                Class<?> clazz = Class.forName(id);
                return AbstractEvent.class.isAssignableFrom(clazz) ? clazz : null;
            } catch (Throwable t) {
                return null;
            }
        }
        for (String pkg : PACKAGES) {
            try {
                Class<?> clazz = Class.forName(PREFIX + pkg + id);
                if (AbstractEvent.class.isAssignableFrom(clazz)) {
                    return clazz;
                }
            } catch (Throwable ignored) {
                // try the next package
            }
        }
        return null;
    }

    static JsonObject err(String code, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", code);
        if (message != null) {
            out.addProperty("message", message);
        }
        return out;
    }

    private static String simpleName(String id) {
        if (id == null) {
            return "?";
        }
        int dot = id.lastIndexOf('.');
        return dot < 0 ? id : id.substring(dot + 1);
    }
}
