package spirelink.mcp;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #89: {@code dev_event} had the same room-switch hole as {@code dev_room} (#87) — it closed
 * the screen and swapped {@code currMapNode.room} by hand, so the room it left behind stayed alive
 * and its campfire effects cast the new room to {@code RestRoom} one frame later.
 *
 * <p>Two properties are pinned here, both table driven:</p>
 * <ol>
 *   <li>every rejection is decided by the pure {@link DevEventTool#gate} from its arguments, and a
 *       rejection never even consults the busy probe (that probe is
 *       {@code RoomGuard.busyReason()}, which force-clears a stuck action queue — so "not asked"
 *       is what makes "被拒 = 零副作用" true rather than merely intended);</li>
 *   <li>a rejected gate plans <b>zero</b> switch steps, so the teardown of the old room cannot have
 *       run either.</li>
 * </ol>
 */
public class DevEventGateTest {

    /** Simple name of a real vanilla event; the gate only cares that it resolved. */
    private static final String EVENT = "BigFish";

    /** One row: id, resolvable, testMode, screen, hasMapNode, expected error code, message fragment. */
    private static final Object[][] REJECTIONS = {
            // test mode is the very first thing checked: no dev tool outside test mode
            {EVENT, true, false, "NONE", true, "DEV_TOOL_DISABLED", null},
            {null, false, false, "DEATH", false, "DEV_TOOL_DISABLED", null},
            // …then the id
            {null, true, true, "NONE", true, "EVENT_NOT_FOUND", "缺少 id"},
            {"", true, true, "NONE", true, "EVENT_NOT_FOUND", "缺少 id"},
            {"NoSuchEventXyz", false, true, "NONE", true, "EVENT_NOT_FOUND", "找不到事件类: NoSuchEventXyz"},
            // …then the run-over screens
            {EVENT, true, true, "DEATH", true, "NOT_READY", "结算界面"},
            {EVENT, true, true, "VICTORY", true, "NOT_READY", "结算界面"},
            {EVENT, true, true, "CREDITS", true, "NOT_READY", "结算界面"},
            // …then "is there even a run?"
            {EVENT, true, true, "NONE", false, "NOT_READY", "当前没有地图节点"},
            // an id typo must not be masked by the "no map node" message (order is part of the contract)
            {"NoSuchEventXyz", false, true, "DEATH", false, "EVENT_NOT_FOUND", "找不到事件类"},
    };

    /** A probe that fails the test if the gate ever asks: a rejection must not touch game state. */
    private static DevEventTool.BusyProbe mustNotBeAsked() {
        return new DevEventTool.BusyProbe() {
            @Override
            public String reasonOrNull() {
                fail("被拒的 dev_event 不该探测忙碌状态（busyReason 会自救清动作队列）");
                return null;
            }
        };
    }

    private static final class CountingProbe implements DevEventTool.BusyProbe {

        private final String reason;
        int calls;

        CountingProbe(String reason) {
            this.reason = reason;
        }

        @Override
        public String reasonOrNull() {
            calls++;
            return reason;
        }
    }

    // ------------------------------------------------------------ the table

    @Test
    public void everyRejectionIsDecidedWithoutAskingTheBusyProbe() {
        for (Object[] row : REJECTIONS) {
            DevEventTool.Gate gate = DevEventTool.gate((String) row[0], (Boolean) row[1],
                    (Boolean) row[2], (String) row[3], (Boolean) row[4], mustNotBeAsked());
            String label = Arrays.toString(row);
            assertFalse(label, gate.ok());
            assertEquals(label, row[5], gate.errorCode);
            if (row[6] != null) {
                assertTrue(label + " → " + gate.errorMessage,
                        gate.errorMessage != null && gate.errorMessage.contains((String) row[6]));
            }
        }
    }

    @Test
    public void aBusyGameIsReportedWithItsReason() {
        CountingProbe busy = new CountingProbe("正在过渡（淡入淡出）");
        DevEventTool.Gate gate = DevEventTool.gate(EVENT, true, true, "NONE", true, busy);
        assertFalse(gate.ok());
        assertEquals("NOT_READY", gate.errorCode);
        assertTrue(gate.errorMessage, gate.errorMessage.contains("现在不能切事件"));
        assertTrue(gate.errorMessage, gate.errorMessage.contains("正在过渡（淡入淡出）"));
        assertEquals("忙碌状态只应问一次", 1, busy.calls);
    }

    @Test
    public void anAcceptedCallAsksTheProbeExactlyOnce() {
        CountingProbe idle = new CountingProbe(null);
        DevEventTool.Gate gate = DevEventTool.gate(EVENT, true, true, "NONE", true, idle);
        assertTrue(gate.ok());
        assertEquals(1, idle.calls);
    }

    /** {@code force=true} passes a null probe: the busy check is skipped, not answered with "idle". */
    @Test
    public void aForcedCallNeverConsultsTheProbe() {
        DevEventTool.Gate gate = DevEventTool.gate(EVENT, true, true, "NONE", true, null);
        assertTrue(gate.ok());
    }

    @Test
    public void runOverScreensAreRecognisedAndNothingElseIs() {
        assertTrue(DevEventTool.isRunOverScreen("DEATH"));
        assertTrue(DevEventTool.isRunOverScreen("VICTORY"));
        assertTrue(DevEventTool.isRunOverScreen("CREDITS"));
        assertFalse(DevEventTool.isRunOverScreen("NONE"));
        assertFalse(DevEventTool.isRunOverScreen("EVENT"));
        assertFalse("读不到屏幕时不能当成结算界面", DevEventTool.isRunOverScreen(null));
    }

    // ------------------------------------------------------------ #87's property, carried over

    /** 真机 A: 干净地图（上一间是普通怪房）→ dev_event. */
    private static DevRoomTool.RoomFacts cleanMap() {
        return DevRoomTool.RoomFacts.of("MonsterRoom", "MAP", true,
                false, false, 0, 0, false, 0);
    }

    /** 真机 B: 营火 → proceed 到地图 → 2 秒内 dev_event（#87 的崩溃窗口）. */
    private static DevRoomTool.RoomFacts campfireOnTheMap() {
        return DevRoomTool.RoomFacts.of("RestRoom", "MAP", true,
                true, true, 2, 0, false, 0);
    }

    /** 真机 C: 营火房内（smith 的升级网格还开着）→ dev_event. */
    private static DevRoomTool.RoomFacts campfireWithGridOpen() {
        return DevRoomTool.RoomFacts.of("RestRoom", "GRID", true,
                true, true, 1, 0, true, 0);
    }

    @Test
    public void aRejectedGatePlansNoSwitchStepAtAll() {
        for (Object[] row : REJECTIONS) {
            DevEventTool.Gate gate = DevEventTool.gate((String) row[0], (Boolean) row[1],
                    (Boolean) row[2], (String) row[3], (Boolean) row[4], mustNotBeAsked());
            for (DevRoomTool.RoomFacts facts : Arrays.asList(
                    cleanMap(), campfireOnTheMap(), campfireWithGridOpen())) {
                assertTrue("被拒 = 一个字节都不改（#64/#87）: " + facts.describe(),
                        DevRoomTool.planSwitch(gate.ok(), facts).isEmpty());
            }
        }
    }

    @Test
    public void anAcceptedCampfireCallPlansTheFullTeardownBeforeTheSwap() {
        DevEventTool.Gate gate = DevEventTool.gate(EVENT, true, true, "NONE", true,
                new CountingProbe(null));
        assertTrue(gate.ok());
        List<DevRoomTool.SwitchStep> steps =
                DevRoomTool.planSwitch(gate.ok(), campfireOnTheMap());
        assertEquals(Arrays.asList(
                DevRoomTool.SwitchStep.CLOSE_SCREENS,
                DevRoomTool.SwitchStep.DROP_STALE_EFFECTS,
                DevRoomTool.SwitchStep.DETACH_CAMPFIRE_UI,
                DevRoomTool.SwitchStep.CLEAR_ACTION_QUEUE,
                DevRoomTool.SwitchStep.SWAP_ROOM,
                DevRoomTool.SwitchStep.OPEN_NEW_ROOM), steps);
    }

    @Test
    public void anAcceptedCleanMapCallStillSwapsAndOpens() {
        DevEventTool.Gate gate = DevEventTool.gate(EVENT, true, true, "MAP", true,
                new CountingProbe(null));
        assertTrue(gate.ok());
        List<DevRoomTool.SwitchStep> steps = DevRoomTool.planSwitch(gate.ok(), cleanMap());
        assertEquals(Arrays.asList(
                DevRoomTool.SwitchStep.CLOSE_SCREENS,
                DevRoomTool.SwitchStep.CLEAR_ACTION_QUEUE,
                DevRoomTool.SwitchStep.SWAP_ROOM,
                DevRoomTool.SwitchStep.OPEN_NEW_ROOM), steps);
    }
}
