package spirelink.mcp;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #87: {@code dev_room} used to swap {@code currMapNode.room} and nothing else, so the room it
 * left behind stayed alive. A campfire is the expensive case: {@code CampfireSmithEffect} (queued
 * into {@code AbstractDungeon.effectList} by the smith action) keeps ticking, and its finishing
 * branch runs {@code ((RestRoom) AbstractDungeon.getCurrRoom()).cutFireSound()} — with a shop
 * mounted under it that is the reported
 * {@code ClassCastException: ShopRoom cannot be cast to RestRoom}, thrown from the game loop.
 *
 * <p>These tests pin the fix to a <b>plan</b>: {@link DevRoomTool#planSwitch} is pure, so the three
 * real-machine scenarios from the issue (clean map → shop, campfire → map → shop, campfire in-room →
 * shop) can be asserted without a game, and the #64 rule "rejected ⇒ zero side effects" becomes
 * "rejected ⇒ an empty step list".</p>
 */
public class RoomSwitchPlanTest {

    private static final List<DevRoomTool.SwitchStep> TEARDOWN = Arrays.asList(
            DevRoomTool.SwitchStep.DROP_STALE_REWARDS,
            DevRoomTool.SwitchStep.CLOSE_SCREENS,
            DevRoomTool.SwitchStep.CLEAR_GRID_PENDING,
            DevRoomTool.SwitchStep.DROP_STALE_EFFECTS,
            DevRoomTool.SwitchStep.DETACH_CAMPFIRE_UI,
            DevRoomTool.SwitchStep.CLEAR_ACTION_QUEUE);

    /** 真机 A: 干净地图（上一间是普通怪房，没有残留界面）→ dev_room{shop}. */
    private static DevRoomTool.RoomFacts cleanMap() {
        return DevRoomTool.RoomFacts.of("MonsterRoom", "MAP", true,
                false, false, 0, 0, false, 0);
    }

    /** 真机 B: 营火（smith → grid → cancel_grid → proceed 回地图）→ dev_room{shop}. */
    private static DevRoomTool.RoomFacts campfireOnTheMap() {
        // The smith effect is still in effectList, the campfire is hidden (somethingSelected ran its
        // timer out), and the room in currMapNode is still the RestRoom.
        return DevRoomTool.RoomFacts.of("RestRoom", "MAP", true,
                true, true, 1, 0, false, 0);
    }

    /** 真机 C: 营火房内直接 dev_room{shop}（网格还开着，没离房）. */
    private static DevRoomTool.RoomFacts campfireWithGridOpen() {
        return DevRoomTool.RoomFacts.of("RestRoom", "GRID", true,
                true, true, 1, 0, true, 0);
    }

    // ------------------------------------------------------------ 真机 A

    @Test
    public void cleanMapToShopOnlyClosesTheScreenAndSwaps() {
        List<DevRoomTool.SwitchStep> steps =
                DevRoomTool.planSwitch(true, cleanMap());
        assertEquals(Arrays.asList(
                DevRoomTool.SwitchStep.CLOSE_SCREENS,
                DevRoomTool.SwitchStep.CLEAR_ACTION_QUEUE,
                DevRoomTool.SwitchStep.SWAP_ROOM,
                DevRoomTool.SwitchStep.OPEN_NEW_ROOM), steps);
        // Nothing campfire-ish may show up when the old room never had a campfire.
        assertFalse(steps.contains(DevRoomTool.SwitchStep.DETACH_CAMPFIRE_UI));
        assertFalse(steps.contains(DevRoomTool.SwitchStep.DROP_STALE_EFFECTS));
    }

    // ------------------------------------------------------------ 真机 B

    @Test
    public void campfireOnTheMapIsTornDownBeforeTheShopIsMounted() {
        List<DevRoomTool.SwitchStep> steps =
                DevRoomTool.planSwitch(true, campfireOnTheMap());
        assertEquals(Arrays.asList(
                DevRoomTool.SwitchStep.CLOSE_SCREENS,
                DevRoomTool.SwitchStep.DROP_STALE_EFFECTS,
                DevRoomTool.SwitchStep.DETACH_CAMPFIRE_UI,
                DevRoomTool.SwitchStep.CLEAR_ACTION_QUEUE,
                DevRoomTool.SwitchStep.SWAP_ROOM,
                DevRoomTool.SwitchStep.OPEN_NEW_ROOM), steps);
    }

    // ------------------------------------------------------------ 真机 C

    @Test
    public void campfireWithTheGridStillOpenClearsTheGridToo() {
        List<DevRoomTool.SwitchStep> steps =
                DevRoomTool.planSwitch(true, campfireWithGridOpen());
        assertEquals(Arrays.asList(
                DevRoomTool.SwitchStep.CLOSE_SCREENS,
                DevRoomTool.SwitchStep.CLEAR_GRID_PENDING,
                DevRoomTool.SwitchStep.DROP_STALE_EFFECTS,
                DevRoomTool.SwitchStep.DETACH_CAMPFIRE_UI,
                DevRoomTool.SwitchStep.CLEAR_ACTION_QUEUE,
                DevRoomTool.SwitchStep.SWAP_ROOM,
                DevRoomTool.SwitchStep.OPEN_NEW_ROOM), steps);
    }

    // ------------------------------------------------------------ order is the contract

    @Test
    public void everyTeardownStepRunsBeforeTheSwapAndTheNewRoomOpensLast() {
        List<DevRoomTool.SwitchStep> steps =
                DevRoomTool.planSwitch(true, campfireWithGridOpen());
        int swap = steps.indexOf(DevRoomTool.SwitchStep.SWAP_ROOM);
        assertTrue("计划里必须有 SWAP_ROOM", swap > 0);
        for (DevRoomTool.SwitchStep teardown : TEARDOWN) {
            int at = steps.indexOf(teardown);
            if (at >= 0) {
                assertTrue(teardown + " 必须在 SWAP_ROOM 之前: " + steps, at < swap);
            }
        }
        assertEquals("OPEN_NEW_ROOM 永远是最后一步",
                DevRoomTool.SwitchStep.OPEN_NEW_ROOM, steps.get(steps.size() - 1));
    }

    @Test
    public void noStepAppearsTwice() {
        List<DevRoomTool.SwitchStep> steps =
                DevRoomTool.planSwitch(true, campfireWithGridOpen());
        Set<DevRoomTool.SwitchStep> unique = new HashSet<DevRoomTool.SwitchStep>(steps);
        assertEquals(steps.size(), unique.size());
    }

    /** Guard against a silent reorder: the enum declaration order is the execution order. */
    @Test
    public void switchStepEnumDeclaresTheExecutionOrder() {
        assertEquals(Arrays.asList(
                DevRoomTool.SwitchStep.DROP_STALE_REWARDS,
                DevRoomTool.SwitchStep.CLOSE_SCREENS,
                DevRoomTool.SwitchStep.CLEAR_GRID_PENDING,
                DevRoomTool.SwitchStep.DROP_STALE_EFFECTS,
                DevRoomTool.SwitchStep.DETACH_CAMPFIRE_UI,
                DevRoomTool.SwitchStep.CLEAR_ACTION_QUEUE,
                DevRoomTool.SwitchStep.SWAP_ROOM,
                DevRoomTool.SwitchStep.OPEN_NEW_ROOM),
                Arrays.asList(DevRoomTool.SwitchStep.values()));
    }

    // ------------------------------------------------------------ each fact drives its own step

    @Test
    public void aHiddenCampfireAloneForcesTheDetachStep() {
        // The static switch is what the smith effect's last cast is gated on; it outlives the room.
        DevRoomTool.RoomFacts facts = DevRoomTool.RoomFacts.of("ShopRoom", "NONE", false,
                false, true, 0, 0, false, 0);
        assertTrue(DevRoomTool.planSwitch(true, facts)
                .contains(DevRoomTool.SwitchStep.DETACH_CAMPFIRE_UI));
    }

    @Test
    public void aLeftoverCampfireUiForcesTheDetachStep() {
        DevRoomTool.RoomFacts facts = DevRoomTool.RoomFacts.of("RestRoom", "NONE", false,
                true, false, 0, 0, false, 0);
        assertTrue(DevRoomTool.planSwitch(true, facts)
                .contains(DevRoomTool.SwitchStep.DETACH_CAMPFIRE_UI));
    }

    @Test
    public void staleEffectsAloneForceTheDropStep() {
        DevRoomTool.RoomFacts facts = DevRoomTool.RoomFacts.of("MonsterRoom", "NONE", false,
                false, false, 3, 0, false, 0);
        assertTrue(DevRoomTool.planSwitch(true, facts)
                .contains(DevRoomTool.SwitchStep.DROP_STALE_EFFECTS));
    }

    @Test
    public void aLeftoverGridSelectionForcesTheGridStepEvenOnAnotherScreen() {
        // cancel_grid leaves selectedCards/forUpgrade behind; CampfireSmithEffect.update() reads them.
        DevRoomTool.RoomFacts facts = DevRoomTool.RoomFacts.of("RestRoom", "MAP", true,
                true, true, 1, 2, true, 0);
        List<DevRoomTool.SwitchStep> steps = DevRoomTool.planSwitch(true, facts);
        assertTrue(steps.contains(DevRoomTool.SwitchStep.CLEAR_GRID_PENDING));
        assertTrue(steps.indexOf(DevRoomTool.SwitchStep.CLEAR_GRID_PENDING)
                < steps.indexOf(DevRoomTool.SwitchStep.SWAP_ROOM));
    }

    // ------------------------------------------------------------ C7-13 leftover reward screen

    /**
     * Issue #108 · C7-13: {@code dev_reward} fabricates a reward screen, then {@code dev_room} walks
     * off without it being taken. The screen's own copy of the list survives the room switch, and
     * vanilla's {@code closeCurrentScreen()} turns a non-empty copy into
     * {@code previousScreen = COMBAT_REWARD} the moment a {@code GRID} closes — the reward screen then
     * "回魂" in an unrelated room as {@code ui=combat_reward} with no {@code rewards} block and only
     * {@code proceed} to press.
     */
    private static DevRoomTool.RoomFacts rewardScreenLeftOpen() {
        // 真机：dev_reward{gold} → 屏上是 2 条奖励（屏与旧房间各一份），玩家没领就 dev_room{shop}.
        return DevRoomTool.RoomFacts.of("MonsterRoom", "COMBAT_REWARD", true,
                false, false, 0, 0, false, 0).withRewards(2, 2);
    }

    @Test
    public void aLeftoverRewardScreenIsDroppedBeforeAnythingElse() {
        List<DevRoomTool.SwitchStep> steps =
                DevRoomTool.planSwitch(true, rewardScreenLeftOpen());

        assertEquals("奖励屏必须是第一步——关 GRID 会拿这份列表把屏挂回来: " + steps,
                DevRoomTool.SwitchStep.DROP_STALE_REWARDS, steps.get(0));
        assertTrue(steps.contains(DevRoomTool.SwitchStep.SWAP_ROOM));
        assertTrue("清理必须发生在换房间之前: " + steps,
                steps.indexOf(DevRoomTool.SwitchStep.DROP_STALE_REWARDS)
                        < steps.indexOf(DevRoomTool.SwitchStep.SWAP_ROOM));
        assertTrue("清理也必须发生在关屏之前（否则关屏自己会复活奖励屏）: " + steps,
                steps.indexOf(DevRoomTool.SwitchStep.DROP_STALE_REWARDS)
                        < steps.indexOf(DevRoomTool.SwitchStep.CLOSE_SCREENS));
    }

    @Test
    public void aCleanRoomHasNoRewardStepAtAll() {
        for (DevRoomTool.RoomFacts facts : Arrays.asList(
                cleanMap(), campfireOnTheMap(), campfireWithGridOpen())) {
            assertFalse("没有残留奖励就不能多跑一步: " + facts.describe(),
                    DevRoomTool.planSwitch(true, facts)
                            .contains(DevRoomTool.SwitchStep.DROP_STALE_REWARDS));
        }
    }

    @Test
    public void eitherCopyAloneOrJustTheScreenForcesTheDrop() {
        // 屏上那份列表还在（房间那份已经被 proceed 清掉了）
        assertTrue(DevRoomTool.RoomFacts.of("MonsterRoom", "NONE", false,
                false, false, 0, 0, false, 0).withRewards(1, 0).staleRewards());
        // 屏关了但房间那份还在（下一次 open() 会把它整份复制回屏幕）
        assertTrue(DevRoomTool.RoomFacts.of("MonsterRoom", "NONE", false,
                false, false, 0, 0, false, 0).withRewards(0, 1).staleRewards());
        // 屏还开着、列表空（proceed 放弃过奖励）：也要把屏收干净
        assertTrue(DevRoomTool.RoomFacts.of("MonsterRoom", "COMBAT_REWARD", true,
                false, false, 0, 0, false, 0).withRewards(0, 0).staleRewards());
        // 正常房间：一步都不多
        assertFalse(cleanMap().staleRewards());
    }

    @Test
    public void theRewardCountsAreVisibleInTheLogLine() {
        String line = rewardScreenLeftOpen().describe();
        assertTrue("排查时要能一眼看到两份列表的条数: " + line, line.contains("rewards=2/2"));
        assertTrue(line.contains("COMBAT_REWARD"));
    }

    // ------------------------------------------------------------ #64 must not regress

    @Test
    public void aRejectedCallPlansNoStepAtAll() {
        for (DevRoomTool.RoomFacts facts : Arrays.asList(
                cleanMap(), campfireOnTheMap(), campfireWithGridOpen())) {
            assertTrue("被拒 = 一个字节都不改（#64）: " + facts.describe(),
                    DevRoomTool.planSwitch(false, facts).isEmpty());
        }
    }

    @Test
    public void aRejectedDevRoomPlanCarriesAnEmptySwitchPlan() {
        // Exactly what DevRoomTool.enter() does: the target plan is decided first, and the switch
        // plan for a rejected one is empty - so the teardown cannot have run.
        DevRoomTool.RoomPlan rejected = DevRoomTool.plan("shop", false, rooms("ShopRoom"));
        assertFalse(rejected.ok());
        assertEquals("NOT_READY", rejected.errorCode);
        assertTrue(DevRoomTool.planSwitch(rejected.ok(), campfireWithGridOpen()).isEmpty());
    }

    @Test
    public void nullFactsPlanNothing() {
        assertTrue(DevRoomTool.planSwitch(true, null).isEmpty());
    }

    // ------------------------------------------------------------ facts are pure data

    @Test
    public void factsDescribeTheStateWithoutTouchingTheGame() {
        DevRoomTool.RoomFacts facts = campfireWithGridOpen();
        String line = facts.describe();
        assertTrue(line, line.contains("RestRoom"));
        assertTrue(line, line.contains("GRID"));
        assertTrue(line, line.contains("effects=1"));
        assertEquals(1, facts.effectCount);
        assertTrue(facts.campfireAlive());
        assertTrue(facts.gridBusy());
        assertFalse(cleanMap().campfireAlive());
        assertFalse(cleanMap().gridBusy());
    }

    private static DevRoomTool.RoomLookup rooms(final String... existing) {
        final Set<String> names = new HashSet<String>(Arrays.asList(existing));
        return new DevRoomTool.RoomLookup() {
            @Override
            public boolean exists(String simpleName) {
                return names.contains(simpleName);
            }
        };
    }
}
