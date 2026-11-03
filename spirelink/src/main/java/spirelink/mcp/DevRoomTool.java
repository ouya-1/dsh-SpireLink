package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test-only: jump into a room type so every room-shaped ui can be exercised.
 *
 * <h3>Issue #64 — plan first, apply second</h3>
 * <p>QA hit an {@code Exordium.generateExclusions()} AIOOBE that killed the run right after a series
 * of <b>rejected</b> {@code dev_room} calls. The requirement that came out of it: a refused
 * {@code dev_room} must change <b>nothing</b> — not the room, not {@code nextRoom}, not the vanilla
 * monster/boss pools.</p>
 *
 * <p>So every decision that can end in a rejection is taken in {@link #plan} (pure: no
 * {@code AbstractDungeon} write, no reflection beyond a class-existence lookup) before the apply
 * phase runs. Things that used to sneak a mutation in ahead of the verdict:</p>
 * <ul>
 *   <li>{@code BossGuard.ensureBossList()} mutated {@code AbstractDungeon.bossList} and could then
 *       still answer NOT_READY — moved into the apply phase;</li>
 *   <li>{@code boss_reward} edited the room, the chest and the screen and answered
 *       <em>ok:false</em> when the screen did not settle — that path now rolls back first.</li>
 * </ul>
 *
 * <h3>Issue #87 — leaving the old room is a plan too</h3>
 * <p>#64 was about the monster pool; #87 is the same family one level up: a dev teleport replaced
 * {@code currMapNode.room} while the <em>old</em> room still had live objects that tick every frame
 * and cast {@code AbstractDungeon.getCurrRoom()} to their own room type. Venue was a campfire:
 * {@code dev_room{type:"rest"}} → smith → grid → cancel_grid → proceed → map →
 * {@code dev_room{type:"shop"}} produced
 * {@code ClassCastException: ShopRoom cannot be cast to RestRoom} from the render loop.</p>
 *
 * <p>So "what does the old room still have alive?" is now answered by {@link RoomFacts} (reads only),
 * turned into an ordered {@link SwitchStep} list by the pure {@link #planSwitch}, and executed by
 * {@link #runSwitch} — the same plan/apply split #64 gave the target selection, now for the teardown
 * of the room we are leaving.</p>
 */
public final class DevRoomTool {

    private static final String BASE = "com.megacrit.cardcrawl.rooms.";

    /** dev_room 所有失败分支统一的开头（本文件的 catch 分支都用它）。 */
    static final String FAILURE_PREFIX = "进入房间失败: ";

    /** DevEncounterTool 自己的失败开头（含 issue #45 里贴出来的旧文案），dev_room 看到时要换掉。 */
    private static final String[] ENCOUNTER_FAILURE_PREFIXES = {
            "进入遭遇失败: ", "跳转失败: ", "进房间失败: ",
    };

    private DevRoomTool() {
    }

    // ------------------------------------------------------------ plan phase (pure)

    /** True when {@code simpleName} names a real {@code AbstractRoom} subclass. */
    interface RoomLookup {
        boolean exists(String simpleName);
    }

    /** What a {@code type} maps to. Built by {@link #plan}, consumed by {@link #enter}. */
    static final class RoomPlan {

        static final String KIND_VICTORY = "victory";
        static final String KIND_BOSS_REWARD = "boss_reward";
        static final String KIND_ENCOUNTER = "encounter";
        static final String KIND_ROOM = "room";

        /** One of the {@code KIND_*} constants, or {@code null} when the plan is a rejection. */
        final String kind;
        /** Room class simple name for {@link #KIND_ROOM}. */
        final String simpleName;
        /** Echo of the caller's {@code type} (used by the success payload). */
        final String type;
        final String errorCode;
        final String errorMessage;

        private RoomPlan(String kind, String simpleName, String type, String errorCode,
                String errorMessage) {
            this.kind = kind;
            this.simpleName = simpleName;
            this.type = type;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        static RoomPlan of(String kind, String simpleName, String type) {
            return new RoomPlan(kind, simpleName, type, null, null);
        }

        static RoomPlan reject(String code, String message) {
            return new RoomPlan(null, null, null, code, message);
        }

        boolean ok() {
            return errorCode == null;
        }

        boolean isEncounter() {
            return KIND_ENCOUNTER.equals(kind);
        }
    }

    /**
     * Pure: which concrete thing does {@code type} ask for, and may it be done right now?
     *
     * <p>Reads no {@code AbstractDungeon} state and writes nothing, so "rejected ⇒ zero side
     * effects" is a property of the design and not of a lucky ordering.</p>
     *
     * @param type       caller supplied room type
     * @param hasMapNode whether a {@code currMapNode} exists (the caller reads it)
     * @param lookup     class-existence oracle (the game's class loader in production)
     */
    static RoomPlan plan(String type, boolean hasMapNode, RoomLookup lookup) {
        if (type == null || type.trim().isEmpty()) {
            return RoomPlan.reject("BAD_REQUEST", "缺少 type");
        }
        String key = type.trim().toLowerCase(Locale.ROOT);
        if ("victory".equals(key) || "true_victory".equals(key) || "winscreen".equals(key)) {
            return hasMapNode ? RoomPlan.of(RoomPlan.KIND_VICTORY, null, type)
                    : RoomPlan.reject("NOT_READY", "当前没有地图节点（还在主菜单）");
        }
        if ("boss_reward".equals(key) || "bossrelic".equals(key) || "boss_relic".equals(key)) {
            return hasMapNode ? RoomPlan.of(RoomPlan.KIND_BOSS_REWARD, null, type)
                    : RoomPlan.reject("NOT_READY", "当前没有地图节点（还在主菜单）");
        }
        // Vanilla names are irregular: there is no EliteRoom / BossRoom.
        if ("monster".equals(key) || "fight".equals(key)) {
            // Same code path as dev_encounter: one implementation of "start a fight".
            return hasMapNode ? RoomPlan.of(RoomPlan.KIND_ENCOUNTER, null, type)
                    : RoomPlan.reject("NOT_READY", "当前没有地图节点（还在主菜单）");
        }
        String simple;
        if ("elite".equals(key)) {
            simple = "MonsterRoomElite";
        } else if ("boss".equals(key)) {
            simple = "MonsterRoomBoss";
        } else {
            String name = key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
            simple = name + (name.endsWith("Room") ? "" : "Room");
        }
        if (lookup == null || !lookup.exists(simple)) {
            return RoomPlan.reject("ROOM_NOT_FOUND",
                    "找不到房间类型: " + type + "（试过 " + simple + "）");
        }
        if (!hasMapNode) {
            return RoomPlan.reject("NOT_READY", "当前没有地图节点（还在主菜单）");
        }
        return RoomPlan.of(RoomPlan.KIND_ROOM, simple, type);
    }

    private static RoomLookup runtimeLookup() {
        return new RoomLookup() {
            @Override
            public boolean exists(String simpleName) {
                return find(BASE, simpleName, AbstractRoom.class, "") != null;
            }
        };
    }

    // ------------------------------------------------------------ #87 room switch plan (pure)

    /**
     * One ordered step of "leave the old room, then enter the new one".
     *
     * <p><b>Issue #87.</b> {@code dev_room} used to close the current screen and swap
     * {@code currMapNode.room} — nothing else. Vanilla does considerably more when it leaves a room
     * ({@code AbstractDungeon.nextRoomTransition(SaveFile)}, bytecode offsets 335-456 of
     * {@code lib/desktop-1.0.jar}): clear {@code previousScreen}, hide the banner, close the map,
     * call {@code closeCurrentScreen()}, then <b>empty every effect queue</b>
     * ({@code effectList.clear()}, {@code effectsQueue.clear()}, {@code topLevelEffectsQueue.clear()}
     * and {@code topLevelEffects} minus {@code ObtainKeyEffect}).</p>
     *
     * <p>Skipping the last part is what crashed the game: the campfire's smith animation
     * ({@code CampfireSmithEffect}, added to {@code AbstractDungeon.effectList} by the smith action)
     * survives the swap, and its {@code update()} ends with
     * {@code ((RestRoom) AbstractDungeon.getCurrRoom()).cutFireSound()} whenever
     * {@code CampfireUI.hidden} is set. With a shop mounted under it that is exactly
     * {@code ClassCastException: ShopRoom cannot be cast to RestRoom}. The same unguarded cast sits
     * in {@code AbstractCampfireOption.update()} and in {@code CampfireSmithEffect.update()}'s
     * {@code fadeIn()} branch, so the whole "old room is still alive" family is torn down here.</p>
     *
     * <p>The steps and their order are the contract; {@link #planSwitch} decides which ones apply and
     * {@link #runSwitch} executes exactly that order.</p>
     */
    enum SwitchStep {

        /**
         * 旧房间留下的奖励屏收干净（issue #108 · C7-13）。
         *
         * <p>必须是第一步：{@code AbstractDungeon.closeCurrentScreen()} 关 {@code GRID} 时有一句
         * 「{@code combatRewardScreen.rewards} 非空就把 {@code previousScreen} 记成 COMBAT_REWARD」
         * （vanilla 字节码 382-403）。清理晚一步，关屏这一步自己就会把已经走掉的奖励屏重新挂回来。</p>
         */
        DROP_STALE_REWARDS,

        /** screen 归位：{@code previousScreen=null} + {@code closeCurrentScreen()} + banner/map 收起。 */
        CLOSE_SCREENS,

        /** 旧房间的选牌网格状态（{@code selectedCards} / {@code upgradePreviewCard} / forXxx）清干净。 */
        CLEAR_GRID_PENDING,

        /** 清空旧房间遗留的特效队列（vanilla 376-433）——会强转房间的营火特效在这里消失。 */
        DROP_STALE_EFFECTS,

        /** 把旧房间的营火界面摘掉：{@code CampfireUI.hidden} 复位、{@code campfireUI} 置空、火声淡出。 */
        DETACH_CAMPFIRE_UI,

        /** 旧房间排队的动作全部丢弃（{@link spirelink.util.RoomGuard#clearActions()}）。 */
        CLEAR_ACTION_QUEUE,

        /** {@code currMapNode.room = 新房间}。 */
        SWAP_ROOM,

        /** 新房间 {@code onPlayerEntry()}。 */
        OPEN_NEW_ROOM
    }

    /**
     * Read-only snapshot of "what the room we are leaving still has alive".
     *
     * <p>{@link #capture} is the only place that touches {@code AbstractDungeon}; {@link #planSwitch}
     * works on plain values, which is what makes the plan unit-testable without a game and what keeps
     * "被拒 = 零副作用" (#64) true: a rejection happens before a single fact is acted upon.</p>
     */
    static final class RoomFacts {

        /** {@code AbstractDungeon.CurrentScreen.GRID.name()} — the grid screen owns its own state. */
        static final String GRID_SCREEN = "GRID";
        /** Simple class name vanilla gives the campfire room. */
        static final String REST_ROOM = "RestRoom";

        /** Simple class name of the room being left; {@code null} when there is none. */
        final String oldRoom;
        /** {@code AbstractDungeon.screen.name()}, {@code null} when unreadable. */
        final String screen;
        final boolean screenUp;
        /** Old room is a {@code RestRoom} <b>and</b> it still holds a {@code CampfireUI}. */
        final boolean campfireUi;
        /** {@code CampfireUI.hidden} — the static switch the smith effect's last cast depends on. */
        final boolean campfireHidden;
        /** Items in {@code effectList + effectsQueue + topLevelEffects + topLevelEffectsQueue}. */
        final int effectCount;
        /** {@code gridSelectScreen.selectedCards.size()}. */
        final int gridSelected;
        /** {@code forUpgrade || forTransform || forPurge || confirmScreenUp || upgradePreviewCard != null}. */
        final boolean gridFlags;
        /** {@code currentAction + actions + preTurnActions}. */
        final int queuedActions;
        /** {@code AbstractDungeon.combatRewardScreen.rewards.size()} — the screen's own copy（C7-13）. */
        final int rewardScreenItems;
        /** {@code oldRoom.rewards.size()} — the room's copy, re-copied to the screen by {@code open()}. */
        final int roomRewards;

        private RoomFacts(String oldRoom, String screen, boolean screenUp, boolean campfireUi,
                boolean campfireHidden, int effectCount, int gridSelected, boolean gridFlags,
                int queuedActions, int rewardScreenItems, int roomRewards) {
            this.oldRoom = oldRoom;
            this.screen = screen;
            this.screenUp = screenUp;
            this.campfireUi = campfireUi;
            this.campfireHidden = campfireHidden;
            this.effectCount = effectCount;
            this.gridSelected = gridSelected;
            this.gridFlags = gridFlags;
            this.queuedActions = queuedActions;
            this.rewardScreenItems = Math.max(0, rewardScreenItems);
            this.roomRewards = Math.max(0, roomRewards);
        }

        /** For unit tests (and for callers that already know the facts): no game classes involved. */
        static RoomFacts of(String oldRoom, String screen, boolean screenUp, boolean campfireUi,
                boolean campfireHidden, int effectCount, int gridSelected, boolean gridFlags,
                int queuedActions) {
            return of(oldRoom, screen, screenUp, campfireUi, campfireHidden, effectCount,
                    gridSelected, gridFlags, queuedActions, 0, 0);
        }

        /** Same, plus what a leftover reward screen still holds (issue #108 · C7-13). */
        static RoomFacts of(String oldRoom, String screen, boolean screenUp, boolean campfireUi,
                boolean campfireHidden, int effectCount, int gridSelected, boolean gridFlags,
                int queuedActions, int rewardScreenItems, int roomRewards) {
            return new RoomFacts(oldRoom, screen, screenUp, campfireUi, campfireHidden, effectCount,
                    gridSelected, gridFlags, queuedActions, rewardScreenItems, roomRewards);
        }

        /** Same facts with the reward-screen counts filled in (keeps existing call sites short). */
        RoomFacts withRewards(int rewardScreenItems, int roomRewards) {
            return new RoomFacts(oldRoom, screen, screenUp, campfireUi, campfireHidden, effectCount,
                    gridSelected, gridFlags, queuedActions, rewardScreenItems, roomRewards);
        }

        /** Reads the live game state; never throws, never writes. */
        static RoomFacts capture(AbstractRoom oldRoom) {
            String oldRoomName = oldRoom == null ? null : oldRoom.getClass().getSimpleName();
            String screen = null;
            boolean screenUp = false;
            boolean campfireUi = false;
            boolean campfireHidden = false;
            int effectCount = 0;
            int gridSelected = 0;
            boolean gridFlags = false;
            int queuedActions = 0;
            int rewardScreenItems = 0;
            int roomRewards = 0;
            try {
                screen = AbstractDungeon.screen == null ? null : AbstractDungeon.screen.name();
                screenUp = AbstractDungeon.isScreenUp;
                effectCount = sizeOf(AbstractDungeon.effectList)
                        + sizeOf(AbstractDungeon.effectsQueue)
                        + sizeOf(AbstractDungeon.topLevelEffects)
                        + sizeOf(AbstractDungeon.topLevelEffectsQueue);
                com.megacrit.cardcrawl.screens.select.GridCardSelectScreen grid =
                        AbstractDungeon.gridSelectScreen;
                if (grid != null) {
                    gridSelected = grid.selectedCards == null ? 0 : grid.selectedCards.size();
                    gridFlags = grid.forUpgrade || grid.forTransform || grid.forPurge
                            || grid.confirmScreenUp || grid.upgradePreviewCard != null;
                }
                com.megacrit.cardcrawl.actions.GameActionManager am = AbstractDungeon.actionManager;
                if (am != null) {
                    queuedActions = sizeOf(am.actions) + sizeOf(am.preTurnActions)
                            + (am.currentAction != null ? 1 : 0);
                }
                if (oldRoom instanceof com.megacrit.cardcrawl.rooms.RestRoom) {
                    campfireUi = ((com.megacrit.cardcrawl.rooms.RestRoom) oldRoom).campfireUI != null;
                }
                campfireHidden = com.megacrit.cardcrawl.rooms.CampfireUI.hidden;
                // C7-13: the reward screen keeps its own copy of the list (CombatRewardScreen.open()
                // -> setupItemReward() copies getCurrRoom().rewards), and that copy is what outlives a
                // room switch - see dropStaleRewards().
                com.megacrit.cardcrawl.screens.CombatRewardScreen rewardScreen =
                        AbstractDungeon.combatRewardScreen;
                if (rewardScreen != null) {
                    rewardScreenItems = sizeOf(rewardScreen.rewards);
                }
                if (oldRoom != null) {
                    roomRewards = sizeOf(oldRoom.rewards);
                }
            } catch (Throwable t) {
                spirelink.util.Diag.warn("dev: 读取切房现场失败: " + t);
            }
            return new RoomFacts(oldRoomName, screen, screenUp, campfireUi, campfireHidden,
                    effectCount, gridSelected, gridFlags, queuedActions, rewardScreenItems,
                    roomRewards);
        }

        /** True when a grid (or a half-finished upgrade pick) belongs to the room being left. */
        boolean gridBusy() {
            return GRID_SCREEN.equals(screen) || gridSelected > 0 || gridFlags;
        }

        /**
         * True when the old room can still be ticked and cast the current room to {@code RestRoom}:
         * a live {@code CampfireUI}, the static {@code hidden} switch the smith effect reads, or the
         * room simply being a {@code RestRoom}.
         */
        boolean campfireAlive() {
            return REST_ROOM.equals(oldRoom) || campfireUi || campfireHidden;
        }

        /**
         * True when a reward screen we did not finish (or the list behind it) is still around.
         *
         * <p><b>Issue #108 · C7-13.</b> {@code dev_reward} adds to {@code room.rewards} and calls
         * {@code combatRewardScreen.open()}, which <em>copies</em> that list into
         * {@code combatRewardScreen.rewards}. Switching rooms only closes the screen; the copy stays,
         * and vanilla's {@code closeCurrentScreen()} turns a non-empty copy into
         * {@code previousScreen = COMBAT_REWARD} whenever a {@code GRID} closes (bytecode 382-403).
         * That is how the reward screen "回魂" in an unrelated room: {@code ui=combat_reward} with no
         * {@code rewards} block (the new room has none) and only {@code proceed} on offer.</p>
         */
        boolean staleRewards() {
            return rewardScreenItems > 0 || roomRewards > 0 || "COMBAT_REWARD".equals(screen);
        }

        /** One line for the log, so a real-machine run shows exactly why a step list was chosen. */
        String describe() {
            return "room=" + oldRoom + " screen=" + screen + " screenUp=" + screenUp
                    + " campfire=" + campfireUi + "/hidden=" + campfireHidden
                    + " effects=" + effectCount + " grid=" + gridSelected + "/" + gridFlags
                    + " actions=" + queuedActions
                    + " rewards=" + rewardScreenItems + "/" + roomRewards;
        }
    }

    /**
     * Pure: the ordered switch steps for an <b>accepted</b> plan.
     *
     * <p>Reads nothing but {@code facts}. A rejected plan yields an empty list — that is the
     * "被拒 = 零副作用" rule of issue #64 expressed as a property of the plan: nothing to run means
     * not one byte is written.</p>
     *
     * @param accepted whether the gate (test mode + {@link #plan} + the busy guard) said yes
     * @param facts    what the old room still has alive
     */
    static List<SwitchStep> planSwitch(boolean accepted, RoomFacts facts) {
        List<SwitchStep> steps = new ArrayList<SwitchStep>();
        if (!accepted || facts == null) {
            return steps;
        }
        // Order is deliberate: drop the leftover reward list first (closing a GRID would otherwise
        // resurrect the reward screen from that very list - C7-13), then the old screen (it must not
        // paint against the new room), then everything that would be ticked one more time, then the
        // swap itself.
        if (facts.staleRewards()) {
            steps.add(SwitchStep.DROP_STALE_REWARDS);
        }
        steps.add(SwitchStep.CLOSE_SCREENS);
        if (facts.gridBusy()) {
            steps.add(SwitchStep.CLEAR_GRID_PENDING);
        }
        if (facts.effectCount > 0) {
            steps.add(SwitchStep.DROP_STALE_EFFECTS);
        }
        if (facts.campfireAlive()) {
            steps.add(SwitchStep.DETACH_CAMPFIRE_UI);
        }
        steps.add(SwitchStep.CLEAR_ACTION_QUEUE);
        steps.add(SwitchStep.SWAP_ROOM);
        steps.add(SwitchStep.OPEN_NEW_ROOM);
        return steps;
    }

    /** What the caller owns: putting the new room in place, then letting vanilla enter it. */
    interface RoomMount {

        void swap() throws Throwable;

        void open() throws Throwable;
    }

    /**
     * Applies the plan in order. Each step is individually guarded: a step that fails is logged and
     * skipped, so a broken {@code DynamicBanner} can never stop the room switch itself.
     *
     * @return a short log line ("screens grid effects=2 campfire actions swap open")
     */
    static String runSwitch(List<SwitchStep> steps, AbstractRoom oldRoom, RoomMount mount)
            throws Throwable {
        StringBuilder log = new StringBuilder();
        if (steps == null || steps.isEmpty()) {
            return log.toString();
        }
        for (SwitchStep step : steps) {
            switch (step) {
                case DROP_STALE_REWARDS:
                    log.append("rewards=").append(dropStaleRewards(oldRoom)).append(' ');
                    break;
                case CLOSE_SCREENS:
                    closeScreens();
                    log.append("screens ");
                    break;
                case CLEAR_GRID_PENDING:
                    clearGridPending();
                    log.append("grid ");
                    break;
                case DROP_STALE_EFFECTS:
                    log.append("effects=").append(dropStaleEffects()).append(' ');
                    break;
                case DETACH_CAMPFIRE_UI:
                    detachCampfireUi(oldRoom);
                    log.append("campfire ");
                    break;
                case CLEAR_ACTION_QUEUE:
                    spirelink.util.RoomGuard.clearActions();
                    log.append("actions ");
                    break;
                case SWAP_ROOM:
                    if (mount != null) {
                        mount.swap();
                    }
                    log.append("swap ");
                    break;
                case OPEN_NEW_ROOM:
                    if (mount != null) {
                        mount.open();
                    }
                    log.append("open ");
                    break;
                default:
                    break;
            }
        }
        return log.toString().trim();
    }

    /**
     * Vanilla's screen teardown before a room change ({@code nextRoomTransition}:335-358).
     *
     * <p>{@code previousScreen} is cleared <em>before</em> {@code closeCurrentScreen()} on purpose:
     * that makes the screen land on {@code NONE} instead of "the screen the old room was opened
     * from", which is what vanilla does and what stops a stale {@code MAP}/{@code MASTER_DECK_VIEW}
     * from being restored behind the new room (the #51 family).</p>
     */
    private static void closeScreens() {
        try {
            AbstractDungeon.previousScreen = null;
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 清空 previousScreen 失败: " + t);
        }
        try {
            AbstractDungeon.closeCurrentScreen();
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: closeCurrentScreen 失败: " + t);
        }
        try {
            if (AbstractDungeon.dynamicBanner != null) {
                AbstractDungeon.dynamicBanner.hide();
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 收起 banner 失败: " + t);
        }
        try {
            // closeInstantly() dereferences map (DungeonMap.hideInstantly), and map is null before a
            // run has a map - same guard RoomRollback uses when it re-opens the map.
            if (AbstractDungeon.dungeonMapScreen != null
                    && AbstractDungeon.dungeonMapScreen.map != null) {
                AbstractDungeon.dungeonMapScreen.closeInstantly();
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 收起地图失败: " + t);
        }
    }

    /**
     * Drops the grid state the old room left behind.
     *
     * <p>{@code CampfireSmithEffect.update()} reads {@code gridSelectScreen.selectedCards} and
     * {@code forUpgrade} <em>every frame</em>, and {@code upgradePreviewCard} is what vanilla clears
     * on a room transition (offset 335). A grid opened by the old room must never be able to apply
     * itself to the new one.</p>
     */
    private static void clearGridPending() {
        try {
            com.megacrit.cardcrawl.screens.select.GridCardSelectScreen grid =
                    AbstractDungeon.gridSelectScreen;
            if (grid == null) {
                return;
            }
            if (grid.selectedCards != null) {
                grid.selectedCards.clear();
            }
            grid.upgradePreviewCard = null;
            grid.confirmScreenUp = false;
            grid.forUpgrade = false;
            grid.forTransform = false;
            grid.forPurge = false;
            spirelink.util.FieldKit.setFieldQuiet(grid, "hoveredCard", null);
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 清理选牌网格状态失败: " + t);
        }
    }

    /** Empties the four effect queues exactly like {@code nextRoomTransition}:376-433 does. */
    private static int dropStaleEffects() {
        int dropped = 0;
        try {
            dropped += sizeOf(AbstractDungeon.effectList);
            AbstractDungeon.effectList.clear();
            dropped += sizeOf(AbstractDungeon.effectsQueue);
            AbstractDungeon.effectsQueue.clear();
            dropped += sizeOf(AbstractDungeon.topLevelEffectsQueue);
            AbstractDungeon.topLevelEffectsQueue.clear();
            dropped += dropTopLevelEffectsExceptKeys();
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 清理旧房间特效失败: " + t);
        }
        return dropped;
    }

    /**
     * Empties the reward screen a room switch leaves behind (issue #108 · C7-13).
     *
     * <p>Both copies have to go, because vanilla keeps two of them and only one is on screen:</p>
     * <ol>
     *   <li>{@code combatRewardScreen.rewards} — copied from the room by
     *       {@code CombatRewardScreen.open()} → {@code setupItemReward()} ({@code new ArrayList}(
     *       {@code getCurrRoom().rewards})). <b>This</b> is the copy that resurrects the screen:
     *       {@code closeCurrentScreen()} sets {@code previousScreen = COMBAT_REWARD} for a closing
     *       {@code GRID} whenever this list is non-empty (vanilla bytecode 382-403), and
     *       {@code openPreviousScreen()} then re-opens the reward screen - in a room that has no
     *       rewards at all, so the agent sees {@code ui=combat_reward} with no {@code rewards} block
     *       and only {@code proceed} to press;</li>
     *   <li>{@code oldRoom.rewards} — the source list. It is cleared too so the same list can never be
     *       copied back out, and {@code rewardTime} is reset because vanilla reads it in the same
     *       {@code closeCurrentScreen()} tail (offset 636-648).</li>
     * </ol>
     *
     * <p>Snapshotted by {@link DevEncounterTool.RoomRollback}, so a dev call that ends in a rejection
     * still leaves the reward screen exactly as it found it (issue #64's rule).</p>
     *
     * @return how many reward entries were dropped (the larger of the two copies)
     */
    static int dropStaleRewards(AbstractRoom oldRoom) {
        int dropped = 0;
        try {
            com.megacrit.cardcrawl.screens.CombatRewardScreen screen =
                    AbstractDungeon.combatRewardScreen;
            if (screen != null && screen.rewards != null) {
                dropped += screen.rewards.size();
                screen.rewards.clear();
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 清空奖励屏列表失败: " + t);
        }
        try {
            if (oldRoom != null) {
                if (oldRoom.rewards != null) {
                    dropped = Math.max(dropped, oldRoom.rewards.size());
                    oldRoom.rewards.clear();
                }
                oldRoom.rewardTime = false;
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 清空旧房间奖励列表失败: " + t);
        }
        return dropped;
    }

    /** {@code topLevelEffects} keeps only {@code ObtainKeyEffect} — vanilla's own filter. */
    private static int dropTopLevelEffectsExceptKeys() {
        java.util.ArrayList<com.megacrit.cardcrawl.vfx.AbstractGameEffect> effects =
                AbstractDungeon.topLevelEffects;
        if (effects == null || effects.isEmpty()) {
            return 0;
        }
        int before = effects.size();
        java.util.Iterator<com.megacrit.cardcrawl.vfx.AbstractGameEffect> it = effects.iterator();
        while (it.hasNext()) {
            com.megacrit.cardcrawl.vfx.AbstractGameEffect effect = it.next();
            if (!(effect instanceof com.megacrit.cardcrawl.vfx.ObtainKeyEffect)) {
                it.remove();
            }
        }
        return before - effects.size();
    }

    private static int sizeOf(java.util.List<?> list) {
        return list == null ? 0 : list.size();
    }

    /**
     * Takes the campfire UI of the room we are leaving out of the game loop.
     *
     * <p>Three unguarded casts make a live campfire fatal once {@code currMapNode.room} is something
     * else: {@code AbstractCampfireOption.update()} (first statement),
     * {@code CampfireSmithEffect.update()} ({@code fadeIn()} branch) and its finishing
     * {@code cutFireSound()} branch. All three read through {@code AbstractDungeon.getCurrRoom()},
     * so they are only reachable while a campfire object is still ticked or still in an effect
     * queue. This step removes the last handles:</p>
     * <ol>
     *   <li>{@code CampfireUI.hidden = false} — the switch the smith effect's finishing cast is
     *       gated on ({@code CampfireUI.reopen()} does the same);</li>
     *   <li>the old room's {@code campfireUI} is emptied and detached, so nothing can tick its
     *       buttons (each of which casts {@code getCurrRoom()} to {@code RestRoom});</li>
     *   <li>the fire loop is faded the way vanilla fades it when leaving a room
     *       ({@code nextRoomTransition}:279-296 uses {@code RestRoom.lastFireSoundId} — it does
     *       <em>not</em> go through {@code cutFireSound()}).</li>
     * </ol>
     */
    private static void detachCampfireUi(AbstractRoom oldRoom) {
        try {
            if (oldRoom instanceof com.megacrit.cardcrawl.rooms.RestRoom) {
                com.megacrit.cardcrawl.rooms.RestRoom rest =
                        (com.megacrit.cardcrawl.rooms.RestRoom) oldRoom;
                Object ui = rest.campfireUI;
                if (ui != null) {
                    Object buttons = spirelink.util.FieldKit.fieldOrNull(ui, "buttons");
                    if (buttons instanceof java.util.List) {
                        ((java.util.List<?>) buttons).clear();
                    }
                    spirelink.util.FieldKit.setFieldOrNull(ui, "somethingSelected", Boolean.FALSE);
                    spirelink.util.FieldKit.setFieldOrNull(ui, "touchOption", null);
                }
                rest.campfireUI = null;
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 摘除营火界面失败: " + t);
        }
        try {
            com.megacrit.cardcrawl.rooms.CampfireUI.hidden = false;
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 复位 CampfireUI.hidden 失败: " + t);
        }
        try {
            if (com.megacrit.cardcrawl.rooms.RestRoom.lastFireSoundId != 0L) {
                com.megacrit.cardcrawl.core.CardCrawlGame.sound.fadeOut("REST_FIRE_WET",
                        com.megacrit.cardcrawl.rooms.RestRoom.lastFireSoundId);
                com.megacrit.cardcrawl.rooms.RestRoom.lastFireSoundId = 0L;
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 淡出营火火声失败: " + t);
        }
    }

    // ------------------------------------------------------------ apply phase

    public static JsonObject enter(String type) {
        if (!spirelink.util.TestMode.enabled()) {
            return err("DEV_TOOL_DISABLED", null);
        }
        // Every rejection the plan can produce happens here, before a single field is written.
        RoomPlan plan = plan(type, AbstractDungeon.currMapNode != null, runtimeLookup());
        if (!plan.ok()) {
            return err(plan.errorCode, plan.errorMessage);
        }
        // Never teleport while an effect or an action still references the old room: swapping then
        // kills the render loop with an NPE inside BattleStartEffect.
        String busy = spirelink.util.RoomGuard.busyReason();
        if (busy != null) {
            return err("NOT_READY", "现在不能切房间: " + busy);
        }
        if (plan.isEncounter()) {
            // This branch returns early and never reaches the "no map node" check below, so the plan
            // carries that check instead - the failure code/text stays identical to shop/rest/...
            // (issue #45).
            return alignEncounterFailure(DevEncounterTool.enterRandom());
        }
        if (RoomPlan.KIND_VICTORY.equals(plan.kind)) {
            return enterVictory(plan.type);
        }
        if (RoomPlan.KIND_BOSS_REWARD.equals(plan.kind)) {
            return enterBossReward();
        }
        return enterRoom(plan);
    }

    /** Mounts a plain {@code AbstractRoom} subclass and lets vanilla enter it. */
    private static JsonObject enterRoom(RoomPlan plan) {
        String simple = plan.simpleName;
        Class<?> clazz = find(BASE, simple, AbstractRoom.class, "");
        if (clazz == null) {
            return err("ROOM_NOT_FOUND", "找不到房间类型: " + plan.type + "（试过 " + simple + "）");
        }
        if ("MonsterRoomBoss".equals(simple) && AbstractDungeon.bossList == null) {
            // bossList.remove(0) inside onPlayerEntry: without a boss the game dies with an IOOBE.
            // The *seeding* itself (BossGuard.ensureBossList) is an apply-phase step: a call that
            // ends up rejected must not have changed the list.
            return err("NOT_READY",
                    "当前 act 没有 boss 列表（dev 强制的上下文），进 Boss 房会崩；"
                            + "请先 dev_map 到 boss 前或者在正常流程里测试");
        }
        AbstractRoom previousRoom = AbstractDungeon.currMapNode.room;
        // Snapshot before the switch: a failed entry must not leave the screen closed / half-built.
        final DevEncounterTool.RoomRollback rollback =
                DevEncounterTool.RoomRollback.capture(previousRoom);
        // Issue #87: what the room we are leaving still has alive decides the teardown. Reads only,
        // so a rejection above has already returned without a single write (#64's rule).
        final RoomFacts facts = RoomFacts.capture(previousRoom);
        final List<SwitchStep> steps = planSwitch(true, facts);
        final AbstractRoom[] mounted = new AbstractRoom[1];
        try {
            // Recorded before the switch: right after a swap the battle-start effect may not even
            // exist yet, so the cooldown is the only reliable guard in that window.
            spirelink.util.RoomGuard.noteSwitch();
            String done = runSwitch(steps, previousRoom, new RoomMount() {
                @Override
                public void swap() throws Throwable {
                    if ("MonsterRoomBoss".equals(simple)) {
                        // Apply-phase only, and idempotent: seeds one placeholder boss when the
                        // list is empty.
                        spirelink.util.BossGuard.ensureBossList();
                        if (!spirelink.util.BossGuard.bossAvailable()) {
                            throw new IllegalStateException("bossList 补不上（act 上下文不完整）");
                        }
                    }
                    AbstractRoom room = (AbstractRoom) clazz.getDeclaredConstructor().newInstance();
                    mounted[0] = room;
                    AbstractDungeon.currMapNode.room = room;
                }

                @Override
                public void open() throws Throwable {
                    if (mounted[0] != null) {
                        mounted[0].onPlayerEntry();
                        // B2-15: a dev-mounted monster room skips vanilla's whole player-side combat
                        // start (dev_room{type:"boss"} used to mount a fight with hand=0, energy=0 and
                        // empty piles — unplayable). Same sequence dev_encounter uses.
                        DevEncounterTool.startCombatIfMonsterRoom(mounted[0]);
                    }
                }
            });
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("type", plan.type);
            out.addProperty("class", clazz.getName());
            if (mounted[0] instanceof com.megacrit.cardcrawl.rooms.MonsterRoom) {
                // I4: never answer ok for a fight the player cannot act in (B2-15 实测 B).
                JsonObject readiness = DevEncounterTool.combatReadiness();
                out.add("readiness", readiness);
                if (!readiness.get("ready").getAsBoolean()) {
                    throw new IllegalStateException("战斗未初始化: " + readiness);
                }
            }
            spirelink.ui.AgentHud.push("调试房间：" + simple);
            spirelink.util.Diag.info("dev_room: 切到 " + simple + " 步骤=" + steps + " 执行=" + done
                    + " 现场[" + facts.describe() + "]");
            return out;
        } catch (Throwable t) {
            // Half-entered rooms are how a single dev call turns into a dead render loop: put the old
            // room AND the old screen back so the game keeps running, get_state keeps reporting the
            // room the player was actually in, and the caller gets a clear error instead.
            spirelink.util.Diag.warn("dev_room: 进入 " + simple + " 失败，已回滚: "
                    + rollback.restore() + "（" + t + "）");
            return err("NOT_READY", FAILURE_PREFIX + t);
        }
    }

    /**
     * Boss relic select screen, without fighting the boss. The room must be a {@code TreasureRoomBoss}
     * because vanilla's {@code relicObtainLogic} casts the current room to it.
     */
    private static JsonObject enterBossReward() {
        if (AbstractDungeon.currMapNode == null) {
            return err("NOT_READY", "当前没有地图节点（还在主菜单）");
        }
        // Issue #64: this branch creates a room + a chest and opens a screen. It used to answer
        // ok:false ("遗物屏没稳定下来") while all of that stayed behind; snapshot, and undo.
        final DevEncounterTool.RoomRollback rollback =
                DevEncounterTool.RoomRollback.capture(AbstractDungeon.currMapNode.room);
        final AbstractRoom previousRoom = AbstractDungeon.currMapNode.room;
        // Issue #87: same teardown plan as every other room switch — a campfire left behind must not
        // be able to tick (or cast the current room) through the BOSS_REWARD screen either.
        final RoomFacts facts = RoomFacts.capture(previousRoom);
        final List<SwitchStep> steps = planSwitch(true, facts);
        try {
            final com.megacrit.cardcrawl.rooms.TreasureRoomBoss room =
                    new com.megacrit.cardcrawl.rooms.TreasureRoomBoss();
            room.phase = com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMPLETE;
            // TreasureRoomBoss.update() calls chest.update() every frame: a null chest throws inside
            // the game loop, the gate then never sees a stable state and the whole entry is unusable.
            // Same helper the real path uses, so dev and production can never drift apart.
            final com.megacrit.cardcrawl.rewards.chests.BossChest chest =
                    new com.megacrit.cardcrawl.rewards.chests.BossChest();
            String done = runSwitch(steps, previousRoom, new RoomMount() {
                @Override
                public void swap() {
                    room.chest = chest;
                    AbstractDungeon.currMapNode.room = room;
                }

                @Override
                public void open() {
                    spirelink.actions.BossChestGate.ensureChest();
                    chest.open(true);
                    if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.BOSS_REWARD) {
                        // open() only shows the screen when the relic list is non-empty; force it.
                        if (chest.relics == null || chest.relics.isEmpty()) {
                            chest.relics = new java.util.ArrayList<>();
                            chest.relics.add(new com.megacrit.cardcrawl.relics.PhilosopherStone());
                            chest.relics.add(new com.megacrit.cardcrawl.relics.RunicDome());
                            chest.relics.add(new com.megacrit.cardcrawl.relics.SneckoEye());
                        }
                        AbstractDungeon.bossRelicScreen.open(chest.relics);
                    }
                    room.phase = com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMPLETE;
                    if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.BOSS_REWARD) {
                        AbstractDungeon.screen = AbstractDungeon.CurrentScreen.BOSS_REWARD;
                        AbstractDungeon.isScreenUp = true;
                    }
                }
            });
            spirelink.util.Diag.info("dev_room boss_reward: 步骤=" + steps + " 执行=" + done
                    + " 现场[" + facts.describe() + "]");
            JsonArray ids = new JsonArray();
            java.util.List<com.megacrit.cardcrawl.relics.AbstractRelic> shown =
                    AbstractDungeon.bossRelicScreen == null ? chest.relics
                            : AbstractDungeon.bossRelicScreen.relics;
            if (shown != null) {
                for (com.megacrit.cardcrawl.relics.AbstractRelic relic : shown) {
                    ids.add(relic.relicId);
                }
            }
            // Reproduce the readiness checks here: the gate needs a mounted room with a live chest,
            // the BOSS_REWARD screen up and the room marked COMPLETE.
            boolean stable = AbstractDungeon.getCurrRoom()
                    instanceof com.megacrit.cardcrawl.rooms.TreasureRoomBoss
                    && ((com.megacrit.cardcrawl.rooms.TreasureRoomBoss) AbstractDungeon.getCurrRoom())
                            .chest != null
                    && AbstractDungeon.screen == AbstractDungeon.CurrentScreen.BOSS_REWARD
                    && AbstractDungeon.isScreenUp;
            if (!stable) {
                // Issue #64: a rejection must not leave the half-built room behind.
                String restored = rollback.restore();
                spirelink.util.Diag.warn("dev_room boss_reward: 遗物屏没稳定，已回滚: " + restored);
                return err("NOT_READY", "遗物屏没稳定下来: screen=" + AbstractDungeon.screen
                        + " isScreenUp=" + AbstractDungeon.isScreenUp
                        + " room=" + (AbstractDungeon.currMapNode == null ? "null"
                                : AbstractDungeon.currMapNode.room.getClass().getSimpleName()));
            }
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("type", "boss_reward");
            out.addProperty("screen", String.valueOf(AbstractDungeon.screen));
            out.add("relics", ids);
            spirelink.ui.AgentHud.push("调试界面：Boss 遗物");
            spirelink.util.Diag.info("dev_room boss_reward: screen=" + AbstractDungeon.screen
                    + " isScreenUp=" + AbstractDungeon.isScreenUp + " stable=" + stable
                    + " relics=" + ids);
            return out;
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev_room boss_reward 失败，已回滚: " + rollback.restore()
                    + "（" + t + "）");
            return err("NOT_READY", "打开 Boss 遗物屏失败: " + t);
        }
    }

    /**
     * Simple class name of the room a dev victory mounts under the screen (issue #108 · C7-08).
     *
     * <p>{@code dev_room{type:"victory"}} used to install only the {@code VictoryScreen} and leave
     * whatever room the player happened to be in as {@code currMapNode.room}. Vanilla's winning frame
     * is different: the screen is shown while the player is already inside a {@code VictoryRoom}
     * ({@code VictoryScreen} itself is built by the {@code Cutscene} that runs in that room). The
     * difference was observable through {@code act proceed}, which checks the <b>room</b> branches
     * before the run-over branch: with a cleared boss still mounted
     * ({@code MonsterRoomBoss} + {@code phase=COMPLETE}) proceed ran the boss transition
     * ({@code goToTreasureRoom}) and the run stayed alive at {@code ui=map}, while from a plain room
     * the very same call returned to the main menu. One dev call, two outcomes — C7-08.</p>
     */
    static final String VICTORY_ROOM = "VictoryRoom";

    /**
     * {@code VictoryRoom}'s own constructor phase ({@code RoomPhase.EVENT}), never {@code COMPLETE}.
     *
     * <p>A {@code COMPLETE} victory room would be taken by the boss-transition branch again, which is
     * exactly the nondeterminism above; see {@link #proceedReachesRunOver}.</p>
     */
    static final String VICTORY_ROOM_PHASE = "EVENT";

    /**
     * Vanilla's victory room, the room a real win shows the victory screen over.
     *
     * <p>{@code EventType.NONE} on purpose: {@code EventType.HEART} makes {@code onPlayerEntry()}
     * build the {@code SpireHeart} event (that is the act-3 heart fight, not a victory screen), while
     * {@code NONE} only hides the overlay proceed button — which is what vanilla does on that frame.
     * </p>
     */
    static AbstractRoom victoryRoom() {
        return new com.megacrit.cardcrawl.rooms.VictoryRoom(
                com.megacrit.cardcrawl.rooms.VictoryRoom.EventType.NONE);
    }

    /**
     * C7-08, pure: with this room and screen mounted, does {@code act proceed} reach the run-over
     * branch (→ main menu) instead of one of the room branches?
     *
     * <p>A mirror of {@code ActionExecutor.proceed()}'s branch order, kept next to the mount that has
     * to satisfy it (the same way {@code ActionLegality} mirrors {@code StateBuilder#deriveUi}). The
     * order there is: no current room → refuse; map screen; combat-reward screen; card-reward screen;
     * boss-reward screen; <b>then</b> a {@code COMPLETE} combat/victory room (boss transition, the run
     * lives on); then the run-over screens. So a {@code COMPLETE} combat room under a victory screen
     * never reaches the main menu.</p>
     *
     * <p>{@link #enterVictory} uses this as a post-mount self-check (a warning in the log when the
     * mounted state would not reach the run-over branch), and {@code DevRoomToolTest} pins the matrix
     * so the mount cannot silently regress.</p>
     */
    static boolean proceedReachesRunOver(String roomClass, String roomPhase, String screen) {
        if ("MAP".equals(screen) || "COMBAT_REWARD".equals(screen) || "CARD_REWARD".equals(screen)
                || "BOSS_REWARD".equals(screen)) {
            // These have their own branches in front of the run-over one.
            return false;
        }
        boolean combatRoom = "MonsterRoom".equals(roomClass) || "MonsterRoomElite".equals(roomClass)
                || "MonsterRoomBoss".equals(roomClass) || VICTORY_ROOM.equals(roomClass);
        if (combatRoom && "COMPLETE".equals(roomPhase)) {
            // advanceFromBoss(): goToTreasureRoom / goToTrueVictoryRoom / goToNextDungeon - the run
            // continues. This is the branch that made the same call behave differently per run.
            return false;
        }
        return "VICTORY".equals(screen) || "DEATH".equals(screen) || "CREDITS".equals(screen)
                || VICTORY_ROOM.equals(roomClass);
    }

    // --------------------------------------------------- #109: monsters still dying

    /**
     * What the room we are leaving still has in its death animations (issue #109).
     *
     * <p>Plain numbers so the decision below is pure and testable: the game-side read lives in
     * {@link #capture(AbstractRoom)}, every other method works on the numbers alone.</p>
     */
    static final class MonsterFacts {

        /** Monsters still listed in the group. */
        final int total;
        /** {@code isDying} — the death animation is still playing. */
        final int dying;
        /** {@code isDead && !isDying} — animation finished, the corpse is still in the list. */
        final int dead;
        /** {@code currentHealth > 0 && !isDying}. */
        final int alive;

        private MonsterFacts(int total, int dying, int dead, int alive) {
            this.total = Math.max(0, total);
            this.dying = Math.max(0, dying);
            this.dead = Math.max(0, dead);
            this.alive = Math.max(0, alive);
        }

        /** Pure factory: the whole matrix (empty / dying / all dead / mixed) without a game. */
        static MonsterFacts of(int total, int dying, int dead, int alive) {
            return new MonsterFacts(total, dying, dead, alive);
        }

        /**
         * Live read of {@code room.monsters}; never throws.
         *
         * <p>This is the "还有怪物在死亡动画里" precondition QA filed: after a dev kill the boss (or a
         * plain Cultist) keeps {@code isDying=true} for as long as the reward screen owns the frame,
         * because the room is not ticked while a screen is up.</p>
         */
        static MonsterFacts capture(AbstractRoom room) {
            int total = 0;
            int dying = 0;
            int dead = 0;
            int alive = 0;
            try {
                if (room == null || room.monsters == null || room.monsters.monsters == null) {
                    return of(0, 0, 0, 0);
                }
                for (com.megacrit.cardcrawl.monsters.AbstractMonster monster : room.monsters.monsters) {
                    if (monster == null) {
                        continue;
                    }
                    total++;
                    if (monster.isDying) {
                        dying++;
                    } else if (monster.isDead) {
                        dead++;
                    } else if (monster.currentHealth > 0) {
                        alive++;
                    }
                }
            } catch (Throwable t) {
                spirelink.util.Diag.warn("dev: 读取怪物死亡动画状态失败: " + t);
            }
            return of(total, dying, dead, alive);
        }

        boolean empty() {
            return total == 0;
        }

        /** The precondition of #109: at least one monster is still playing its death animation. */
        boolean deathAnimationsRunning() {
            return dying > 0;
        }

        String describe() {
            return "monsters=" + total + " dying=" + dying + " dead=" + dead + " alive=" + alive;
        }
    }

    /** What {@code dev_room{victory}} does about the monsters of the room it leaves (issue #109). */
    enum VictoryMonsters {
        /** Nothing is dying: mount the screen straight away (its monster group is still null). */
        MOUNT_WITHOUT_GROUP,
        /** Monsters are still dying: finish their death animations first, then mount. */
        SETTLE_THEN_MOUNT_WITHOUT_GROUP
    }

    /**
     * #109, pure: is a settle pass needed before the victory screen goes up?
     *
     * <p>QA crashed twice — a boss ({@code Hexaghost}) and a plain {@code Cultist} — both times one
     * second to several seconds after a dev kill, with {@code dying=true} in {@code monster_list}. So
     * the criterion is "is anything in the group still dying", never "is it a boss".</p>
     */
    static VictoryMonsters planVictoryMonsters(MonsterFacts facts) {
        return facts != null && facts.deathAnimationsRunning()
                ? VictoryMonsters.SETTLE_THEN_MOUNT_WITHOUT_GROUP
                : VictoryMonsters.MOUNT_WITHOUT_GROUP;
    }

    /**
     * #109: the monster group handed to {@code VictoryScreen} — always {@code null}.
     *
     * <p>{@code VictoryScreen.update()} ends with {@code if (monsters != null) { monsters.update();
     * monsters.updateAnimations(); }}, and {@code AbstractMonster.update()} has no early exit: every
     * frame it runs {@code updateDeathAnimation()}, whose tail is</p>
     *
     * <pre>
     * if (deathTimer &lt; 0) { isDead = true; AbstractDungeon.getMonsters().areMonstersDead() ... }
     * </pre>
     *
     * <p>{@code AbstractDungeon.getMonsters()} is {@code getCurrRoom().monsters}, and the room under
     * the victory screen is a {@code VictoryRoom} whose {@code monsters} is null — that is the NPE at
     * {@code AbstractMonster.updateDeathAnimation:1163} QA hit. Note the branch is entered on
     * {@code deathTimer < 0} alone, which is <em>also</em> true for every corpse whose animation
     * finished long ago, so "a group without dying monsters" is not safe either. There is exactly one
     * safe group: none — which is also what vanilla passes
     * ({@code Cutscene.openVictoryScreen()} → {@code new VictoryScreen(null)}).</p>
     *
     * @param facts what the leaving room's group looks like (accepted for readability; the answer
     *              never depends on it — that is the point)
     */
    static com.megacrit.cardcrawl.monsters.MonsterGroup groupForVictoryScreen(MonsterFacts facts) {
        return null;
    }

    /** Upper bound on one settle pass; a fight never has more monsters than this. */
    static final int MAX_SETTLE_MONSTERS = 64;

    /**
     * What {@link #settleDeathAnimations} changed, and how to undo it (issue #109).
     *
     * <p>Undo exists for the same reason {@link DevEncounterTool.RoomRollback} does: if the victory
     * mount fails after the settle, the agent is left standing in the room it came from, and that room
     * must look exactly as it did — no half-finished death silently turned into a corpse.</p>
     */
    static final class DeathSettle {

        /** One monster's fields, as they were before the settle. */
        private static final class State {
            private final com.megacrit.cardcrawl.monsters.AbstractMonster monster;
            private final boolean isDying;
            private final boolean isDead;
            private final int health;
            private final float deathTimer;
            private final boolean tintFadeOutCalled;

            private State(com.megacrit.cardcrawl.monsters.AbstractMonster monster) {
                this.monster = monster;
                this.isDying = monster.isDying;
                this.isDead = monster.isDead;
                this.health = monster.currentHealth;
                this.deathTimer = monster.deathTimer;
                this.tintFadeOutCalled = monster.tintFadeOutCalled;
            }

            private void revert() {
                monster.isDying = isDying;
                monster.isDead = isDead;
                monster.currentHealth = health;
                monster.deathTimer = deathTimer;
                monster.tintFadeOutCalled = tintFadeOutCalled;
            }
        }

        static final DeathSettle EMPTY = new DeathSettle(new ArrayList<State>());

        private final List<State> states;

        private DeathSettle(List<State> states) {
            this.states = states;
        }

        /** How many monsters were put to rest. */
        int count() {
            return states.size();
        }

        /** Puts every settled monster back exactly as it was; returns how many were undone. */
        int revert() {
            int reverted = 0;
            for (State state : states) {
                try {
                    state.revert();
                    reverted++;
                } catch (Throwable t) {
                    spirelink.util.Diag.warn("dev: 回滚怪物死亡动画失败: " + t);
                }
            }
            return reverted;
        }
    }

    /**
     * Finishes every death animation the room still has running (issue #109).
     *
     * <p>Why bother, when the screen no longer ticks the group? Because the run has to be left in a
     * consistent state: {@code dying=true} survived a whole QA round (7 s after the kill) because the
     * reward screen owns the frame and the room is never ticked, so anything that reads the room
     * afterwards ({@code meta.monster_list} via {@code RuntimeProbe}, {@code RoomGuard}, a switch that
     * fails and rolls the room back) would keep seeing a half-finished death. Each monster is put into
     * the state it would have reached on its own:</p>
     * <ul>
     *   <li>{@code isDying = false} — no animation left to play;</li>
     *   <li>{@code isDead = true}, {@code currentHealth = 0} — it really is a corpse;</li>
     *   <li>{@code deathTimer = 0} — {@code deathTimer < 0} is what makes the vanilla tail call
     *       {@code AbstractDungeon.getMonsters()}, so nothing can re-enter that branch even if some
     *       other path ticks the group later (see {@link #groupForVictoryScreen}).</li>
     * </ul>
     *
     * <p>Bounded by {@link #MAX_SETTLE_MONSTERS}; never throws (per-monster try/catch).</p>
     *
     * @return an undo token; {@link DeathSettle#count()} is 0 when there was nothing to do
     */
    static DeathSettle settleDeathAnimations(AbstractRoom room) {
        List<DeathSettle.State> settled = new ArrayList<DeathSettle.State>();
        try {
            if (room == null || room.monsters == null || room.monsters.monsters == null) {
                return DeathSettle.EMPTY;
            }
            int seen = 0;
            for (com.megacrit.cardcrawl.monsters.AbstractMonster monster : room.monsters.monsters) {
                if (monster == null || seen++ >= MAX_SETTLE_MONSTERS) {
                    continue;
                }
                if (!monster.isDying && !monster.isDead && monster.currentHealth > 0) {
                    continue;
                }
                try {
                    settled.add(new DeathSettle.State(monster));
                    monster.isDying = false;
                    monster.isDead = true;
                    monster.currentHealth = 0;
                    monster.deathTimer = 0.0f;
                    monster.tintFadeOutCalled = true;
                } catch (Throwable t) {
                    spirelink.util.Diag.warn("dev: 收尾怪物死亡动画失败（" + monster.id + "）: " + t);
                }
            }
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev: 收尾怪物死亡动画失败: " + t);
        }
        return settled.isEmpty() ? DeathSettle.EMPTY : new DeathSettle(settled);
    }

    /**
     * Builds and mounts the victory screen exactly like vanilla's winning frame does.
     *
     * <p>Called before the {@code VictoryRoom} is mounted. The screen gets <b>no</b> monster group —
     * see {@link #groupForVictoryScreen}, which is also what vanilla does
     * ({@code Cutscene.openVictoryScreen()} → {@code new VictoryScreen(null)}).</p>
     *
     * @return the {@code VictoryScreen}, already assigned to {@code AbstractDungeon.victoryScreen}
     */
    public static com.megacrit.cardcrawl.screens.VictoryScreen buildVictoryScreen() {
        com.megacrit.cardcrawl.screens.VictoryScreen screen =
                new com.megacrit.cardcrawl.screens.VictoryScreen(
                        groupForVictoryScreen(MonsterFacts.capture(AbstractDungeon.getCurrRoom())));
        AbstractDungeon.victoryScreen = screen;
        installVictoryScreenState();
        try {
            screen.update();
        } catch (Throwable t) {
            spirelink.util.Diag.warn("buildVictoryScreen: update() 失败: " + t);
        }
        return screen;
    }

    /**
     * Puts the win frame back on top: {@code is_victory}, {@code screen=VICTORY}, {@code isScreenUp}.
     *
     * <p>Idempotent and used twice per dev victory (after building the screen and again after the
     * {@code VictoryRoom} entered the stage), because {@code ui}/{@code postmortem.result} and
     * {@code AbstractDungeon.update()}'s {@code victoryScreen.update()} both key off these three.</p>
     */
    private static void installVictoryScreenState() {
        try {
            AbstractDungeon.is_victory = true;
            AbstractDungeon.screen = AbstractDungeon.CurrentScreen.VICTORY;
            AbstractDungeon.isScreenUp = true;
        } catch (Throwable t) {
            spirelink.util.Diag.warn("dev_room victory: 设置胜利屏状态失败: " + t);
        }
    }

    private static JsonObject enterVictory(String key) {
        if (AbstractDungeon.currMapNode == null) {
            return err("NOT_READY", "当前没有地图节点（还在主菜单）");
        }
        // Issue #64: same rule as everywhere else in this file — a failure must not leave the
        // previous screen closed and the run looking half-finished.
        final AbstractRoom previousRoom = AbstractDungeon.currMapNode.room;
        final DevEncounterTool.RoomRollback rollback =
                DevEncounterTool.RoomRollback.capture(previousRoom);
        // Issue #87: leaving the room is the same teardown as everywhere else — the victory screen is
        // just a different target. A campfire effect that is still alive would tick (and cast) here.
        final RoomFacts facts = RoomFacts.capture(previousRoom);
        // Issue #109: the room may still have monsters in their death animation (a dev kill leaves
        // isDying=true for as long as a screen owns the frame). Decided before anything is touched.
        final MonsterFacts monsters = MonsterFacts.capture(previousRoom);
        final VictoryMonsters monsterPlan = planVictoryMonsters(monsters);
        final List<SwitchStep> steps = planSwitch(true, facts);
        final AbstractRoom[] mounted = new AbstractRoom[1];
        final DeathSettle[] settle = new DeathSettle[] {DeathSettle.EMPTY};
        try {
            spirelink.util.RoomGuard.noteSwitch();
            String done = runSwitch(steps, previousRoom, new RoomMount() {
                @Override
                public void swap() {
                    // Issue #109: finish the death animations first, so the room is left consistent
                    // (dying=true used to survive the whole reward screen).
                    if (monsterPlan == VictoryMonsters.SETTLE_THEN_MOUNT_WITHOUT_GROUP) {
                        settle[0] = settleDeathAnimations(previousRoom);
                    }
                    // C7-08: screen first, then vanilla's VictoryRoom as the current room. Without the
                    // room, act proceed found whatever was mounted before - a COMPLETE boss room made
                    // it run the boss transition and stay in the run instead of returning to the main
                    // menu. The screen gets no monster group (see groupForVictoryScreen: ticking one
                    // is what crashed QA with a dying monster, #109).
                    buildVictoryScreen();
                    mounted[0] = victoryRoom();
                    AbstractDungeon.currMapNode.room = mounted[0];
                }

                @Override
                public void open() {
                    if (mounted[0] != null) {
                        try {
                            // Hides the overlay proceed button, exactly like vanilla's victory frame.
                            mounted[0].onPlayerEntry();
                        } catch (Throwable t) {
                            spirelink.util.Diag.warn("dev_room victory: VictoryRoom.onPlayerEntry 失败: " + t);
                        }
                    }
                    // Whatever onPlayerEntry did, the win frame stays on top.
                    installVictoryScreenState();
                }
            });
            String roomClass = mounted[0] == null ? null : mounted[0].getClass().getSimpleName();
            String roomPhase = mounted[0] == null || mounted[0].phase == null ? null
                    : mounted[0].phase.name();
            if (!proceedReachesRunOver(roomClass, roomPhase,
                    AbstractDungeon.screen == null ? null : AbstractDungeon.screen.name())) {
                // C7-08: never answer ok for a win frame whose proceed would not leave the run.
                spirelink.util.Diag.warn("dev_room victory: 挂载后 proceed 仍会走房间分支（C7-08）room="
                        + roomClass + " phase=" + roomPhase + " screen=" + AbstractDungeon.screen);
            }
            MonsterFacts after = MonsterFacts.capture(previousRoom);
            if (after.deathAnimationsRunning()) {
                // Cannot happen with the null group in place, and the screen is safe either way - but
                // say it out loud instead of pretending the room is clean.
                spirelink.util.Diag.warn("dev_room victory: 收尾后仍有 " + after.dying
                        + " 只怪物在死亡动画里（#109），胜利屏已按空怪物组挂载，不会崩");
            }
            spirelink.util.Diag.info("dev_room victory: 步骤=" + steps + " 执行=" + done
                    + " 现场[" + facts.describe() + "] 怪物[" + monsters.describe() + "]"
                    + " 收尾=" + settle[0].count() + " room=" + roomClass + " phase=" + roomPhase
                    + " 屏幕怪物组=null");
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("type", key);
            out.addProperty("class", "VictoryScreen");
            out.addProperty("room", VICTORY_ROOM);
            out.addProperty("room_phase", VICTORY_ROOM_PHASE);
            // #109: what the room still had in its death animations, and how many were finished.
            out.addProperty("monsters_dying", monsters.dying);
            out.addProperty("monsters_settled", settle[0].count());
            out.addProperty("screen", String.valueOf(AbstractDungeon.screen));
            spirelink.ui.AgentHud.push("调试界面：胜利");
            spirelink.util.Diag.info("dev_room victory: 置入 VictoryScreen + " + VICTORY_ROOM
                    + "，screen=" + AbstractDungeon.screen);
            return out;
        } catch (Throwable t) {
            // #109: undo the death settle before the room itself is put back.
            String undone = settle[0].count() == 0 ? "无怪物收尾"
                    : "怪物死亡动画已回滚 " + settle[0].revert() + " 只";
            spirelink.util.Diag.warn("dev_room victory 失败，已回滚（" + undone + "）: "
                    + rollback.restore() + "（" + t + "）");
            return err("NOT_READY", "进入胜利界面失败: " + t);
        }
    }

    /**
     * dev_room 的 monster 分支复用 {@link DevEncounterTool}（「开战」只有一份实现），但调用方看到的是
     * 房间语义：失败文案必须和本文件其它 type 一样以 {@link #FAILURE_PREFIX} 开头。issue #45 里贴出来
     * 的是更早版本的「跳转失败: …」，和现在的「进入遭遇失败: …」对不上 —— 按 message 前缀匹配的验证
     * 脚本两边都会漏，所以在这里统一成一种口径。
     *
     * <p>只改「失败且带 message」的返回；成功体、DEV_TOOL_DISABLED 这类没有 message 的返回原样放行。
     */
    static JsonObject alignEncounterFailure(JsonObject result) {
        if (result == null || !result.has("ok") || result.get("ok").isJsonNull()
                || result.get("ok").getAsBoolean()) {
            return result;
        }
        if (!result.has("message") || result.get("message").isJsonNull()) {
            return result;
        }
        String message = result.get("message").getAsString();
        if (message == null || message.startsWith(FAILURE_PREFIX)) {
            return result;
        }
        for (String prefix : ENCOUNTER_FAILURE_PREFIXES) {
            if (message.startsWith(prefix)) {
                result.addProperty("message", FAILURE_PREFIX + message.substring(prefix.length()));
                break;
            }
        }
        return result;
    }

    static Class<?> find(String base, String id, Class<?> parent, String... pkgs) {
        if (id.indexOf('.') > 0) {
            try {
                Class<?> c = Class.forName(id);
                return parent.isAssignableFrom(c) ? c : null;
            } catch (Throwable t) {
                return null;
            }
        }
        for (String pkg : pkgs) {
            try {
                Class<?> c = Class.forName(base + pkg + id);
                if (parent.isAssignableFrom(c)) {
                    return c;
                }
            } catch (Throwable ignored) {
                // next package
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

}
