package spirelink.actions;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #100 (S1): with no reachable map node the planner advertised a lone {@code proceed}, and the
 * executor never closed the map with it — the room branches matched first and clicked a button that
 * does nothing while the map owns the screen. The only advertised action could never land: 10.6 s
 * {@code queued}, then 13 s of {@code ACTION_IN_FLIGHT}.
 *
 * <p>This class is the single decision both sides now ask, so "advertised" and "executable" cannot
 * drift apart again.</p>
 */
public class MapExitPolicyTest {

    @Test
    public void nodesWinOverEverything() {
        assertEquals(MapExitPolicy.PICK_NODE, MapExitPolicy.decide(3, true, true));
        assertEquals(MapExitPolicy.PICK_NODE, MapExitPolicy.decide(1, false, false));
        assertEquals("房间没结束但地图上有节点：也先选点", MapExitPolicy.PICK_NODE,
                MapExitPolicy.decide(2, false, true));
        assertFalse("有节点时 proceed 不会让你前进",
                MapExitPolicy.proceedClosesMap(MapExitPolicy.PICK_NODE));
    }

    @Test
    public void clearedLastRowSendsYouToTheBoss() {
        assertEquals(MapExitPolicy.PICK_BOSS, MapExitPolicy.decide(0, true, true));
        assertEquals("Boss 能不能进与房间阶段无关（bossAvailable 自带判据）",
                MapExitPolicy.PICK_BOSS, MapExitPolicy.decide(0, true, false));
        assertFalse(MapExitPolicy.proceedClosesMap(MapExitPolicy.PICK_BOSS));
    }

    @Test
    public void closingTheMapIsOnlyRealWhileTheRoomIsUnfinished() {
        assertEquals(MapExitPolicy.CLOSE_MAP, MapExitPolicy.decide(0, false, true));
        assertTrue("房间没结束时关地图是唯一真动作",
                MapExitPolicy.proceedClosesMap(MapExitPolicy.CLOSE_MAP));
    }

    /** The exact #100 state: nothing to walk to, no boss, nothing to go back to. Never proceed. */
    @Test
    public void deadEndIsNeverAProceed() {
        assertEquals("房间已经结束", MapExitPolicy.DEAD_END,
                MapExitPolicy.decide(0, false, false));
        assertEquals("还没有当前节点（房间都没有）时也一样", MapExitPolicy.DEAD_END,
                MapExitPolicy.decide(0, false, false));
        assertFalse("这一格正是「唯一动作永远 queued」的来源",
                MapExitPolicy.proceedClosesMap(MapExitPolicy.DEAD_END));
    }

    @Test
    public void messagesNameAUsableAlternative() {
        String nodes = MapExitPolicy.message(MapExitPolicy.PICK_NODE, 2, 15);
        assertTrue(nodes, nodes.contains(ActionTypes.CHOOSE_MAP_NODE));
        assertTrue(nodes, nodes.contains("2"));

        String boss = MapExitPolicy.message(MapExitPolicy.PICK_BOSS, 0, 15);
        assertTrue(boss, boss.contains(ActionTypes.CHOOSE_MAP_NODE));
        assertTrue("Boss 入口的 payload 是 x:-1, y:行数: " + boss, boss.contains("15"));

        String close = MapExitPolicy.message(MapExitPolicy.CLOSE_MAP, 0, 15);
        assertTrue(close, close.contains("关闭地图"));

        String dead = MapExitPolicy.message(MapExitPolicy.DEAD_END, 0, 15);
        assertTrue("死角必须指一条真出路: " + dead, dead.contains(ActionTypes.ABANDON_MID_RUN));
        assertFalse("不能建议 proceed: " + dead, dead.contains("用 proceed"));
    }

    /**
     * #110: a rejection that happens while the map is open may only name an action this tick can
     * really run. The report was {@code "房间还没结束…，先 proceed 离开"} on a map that offered nodes —
     * where {@link MapExitPolicy#proceedClosesMap} is false and {@code proceed} is never advertised, so
     * the tip pointed at a move that cannot execute.
     */
    @Test
    public void theHintNeverNamesAnActionThatCannotRun() {
        String nodes = MapExitPolicy.executableHint(MapExitPolicy.PICK_NODE);
        assertTrue(nodes, nodes.contains(ActionTypes.CHOOSE_MAP_NODE));
        assertFalse("有节点时 proceed 不可执行，提示里不许出现: " + nodes, nodes.contains("proceed"));

        String boss = MapExitPolicy.executableHint(MapExitPolicy.PICK_BOSS);
        assertTrue(boss, boss.contains(ActionTypes.CHOOSE_MAP_NODE));

        String close = MapExitPolicy.executableHint(MapExitPolicy.CLOSE_MAP);
        assertTrue("只有这一格 proceed 是真动作: " + close, close.contains("proceed"));

        String dead = MapExitPolicy.executableHint(MapExitPolicy.DEAD_END);
        assertTrue(dead, dead.contains(ActionTypes.ABANDON_MID_RUN));
        assertFalse("死角不能建议 proceed: " + dead, dead.contains("proceed"));
    }
}
