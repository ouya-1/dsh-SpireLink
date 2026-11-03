package spirelink.actions;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #90: {@code cancel_grid} must finish an upgrade grid the way vanilla's CancelButton does —
 * close the screen, then {@code restRoom.campfireUI.reopen()} so the campfire buttons come back.
 *
 * <p>Vanilla (CancelButton.update, bytecode 318-344) only takes that second step for the
 * upgrade/transform/purge grids and only inside a {@code RestRoom}; every other case just closes the
 * screen. The plan is pure, so all of that is pinned down here without a game.</p>
 */
public class GridClosePlanTest {

    private static String read(String relative) throws IOException {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
            dir = dir.getParentFile();
        }
        throw new IOException("找不到 " + relative);
    }

    // ---------------------------------------------------------------- grid flavour

    @Test
    public void gridKindFollowsVanilla() {
        assertEquals(GridClosePlan.KIND_UPGRADE,
                GridClosePlan.gridKind(true, false, false));
        assertEquals(GridClosePlan.KIND_TRANSFORM,
                GridClosePlan.gridKind(false, true, false));
        assertEquals(GridClosePlan.KIND_PURGE,
                GridClosePlan.gridKind(false, false, true));
        assertEquals(GridClosePlan.KIND_NORMAL,
                GridClosePlan.gridKind(false, false, false));
        // A transform grid also reports forUpgrade on some paths; transform must win (that is the
        // flavour whose cancel restores the campfire).
        assertEquals(GridClosePlan.KIND_TRANSFORM,
                GridClosePlan.gridKind(true, true, false));
    }

    @Test
    public void onlyCampfireFlavoursGetTheRestore() {
        assertTrue(GridClosePlan.isCampfireGridKind(GridClosePlan.KIND_UPGRADE));
        assertTrue(GridClosePlan.isCampfireGridKind(GridClosePlan.KIND_TRANSFORM));
        assertTrue(GridClosePlan.isCampfireGridKind(GridClosePlan.KIND_PURGE));
        assertFalse(GridClosePlan.isCampfireGridKind(GridClosePlan.KIND_NORMAL));
        assertFalse(GridClosePlan.isCampfireGridKind(null));
    }

    // ---------------------------------------------------------------- the core bug

    /** The bug: a campfire upgrade grid cancelled from MCP must restore the campfire UI. */
    @Test
    public void cancelInARestRoomUpgradeGridRestoresTheCampfire() {
        List<GridClosePlan.Step> steps = GridClosePlan.steps(
                GridClosePlan.REST_ROOM, GridClosePlan.KIND_UPGRADE, true);
        assertEquals(4, steps.size());
        assertEquals(GridClosePlan.Step.CLOSE_SCREEN, steps.get(0));
        assertEquals("恢复营火 UI 必须是最后一步（屏幕关掉之后）",
                GridClosePlan.Step.REOPEN_CAMPFIRE, steps.get(3));
        assertTrue(GridClosePlan.restoresCampfire(steps));
    }

    @Test
    public void cancelInARestRoomTransformOrPurgeGridAlsoRestoresTheCampfire() {
        assertTrue(GridClosePlan.restoresCampfire(
                GridClosePlan.steps(GridClosePlan.REST_ROOM, GridClosePlan.KIND_TRANSFORM, true)));
        assertTrue(GridClosePlan.restoresCampfire(
                GridClosePlan.steps(GridClosePlan.REST_ROOM, GridClosePlan.KIND_PURGE, true)));
    }

    /** Vanilla parity: a non-upgrade grid closes without touching the campfire. */
    @Test
    public void cancelOfAPlainGridDoesNotTouchTheCampfire() {
        List<GridClosePlan.Step> steps = GridClosePlan.steps(
                GridClosePlan.REST_ROOM, GridClosePlan.KIND_NORMAL, true);
        assertEquals(3, steps.size());
        assertFalse(GridClosePlan.restoresCampfire(steps));
    }

    /** Confirming is not cancelling: vanilla's smith flow drives the campfire itself. */
    @Test
    public void confirmNeverRestoresTheCampfire() {
        assertFalse(GridClosePlan.restoresCampfire(
                GridClosePlan.steps(GridClosePlan.REST_ROOM, GridClosePlan.KIND_UPGRADE, false)));
    }

    // ---------------------------------------------------------------- unchanged everywhere else

    /** Acceptance: cancel_grid in every non-campfire room behaves exactly as before. */
    @Test
    public void everyNonRestRoomKeepsTheThreeCloseSteps() {
        String[] rooms = {
                "com.megacrit.cardcrawl.rooms.EventRoom",
                "com.megacrit.cardcrawl.rooms.ShopRoom",
                "com.megacrit.cardcrawl.rooms.MonsterRoom",
                "com.megacrit.cardcrawl.rooms.MonsterRoomElite",
                "com.megacrit.cardcrawl.rooms.MonsterRoomBoss",
                "com.megacrit.cardcrawl.rooms.TreasureRoom",
                "com.megacrit.cardcrawl.rooms.TreasureRoomBoss",
                "com.megacrit.cardcrawl.rooms.VictoryRoom",
                "com.megacrit.campfire.FakeRoom",  // not a RestRoom, even though it says campfire
        };
        for (String room : rooms) {
            for (String kind : new String[] {GridClosePlan.KIND_UPGRADE,
                    GridClosePlan.KIND_TRANSFORM, GridClosePlan.KIND_PURGE,
                    GridClosePlan.KIND_NORMAL}) {
                List<GridClosePlan.Step> steps = GridClosePlan.steps(room, kind, true);
                assertEquals("非营火房间的收尾步骤不能变: " + room + "/" + kind,
                        3, steps.size());
                assertFalse(GridClosePlan.restoresCampfire(steps));
            }
        }
    }

    @Test
    public void unknownRoomOrKindIsSafe() {
        assertFalse(GridClosePlan.restoresCampfire(
                GridClosePlan.steps(null, GridClosePlan.KIND_UPGRADE, true)));
        assertFalse(GridClosePlan.restoresCampfire(
                GridClosePlan.steps(GridClosePlan.REST_ROOM, null, true)));
        assertFalse(GridClosePlan.restoresCampfire(GridClosePlan.steps(null, null, true)));
        assertEquals(3, GridClosePlan.steps(null, null, true).size());
        assertFalse(GridClosePlan.restoresCampfire(null));
    }

    /** The close chain itself must be unchanged: close, then hide, then reset. */
    @Test
    public void theCloseChainKeepsItsOrder() {
        List<GridClosePlan.Step> steps = GridClosePlan.steps(
                GridClosePlan.REST_ROOM, GridClosePlan.KIND_UPGRADE, true);
        assertEquals(GridClosePlan.Step.CLOSE_SCREEN, steps.get(0));
        assertEquals(GridClosePlan.Step.HIDE_GRID, steps.get(1));
        assertEquals(GridClosePlan.Step.RESET_SCREEN, steps.get(2));
        assertTrue("REOPEN_CAMPFIRE 必须在所有关闭步骤之后",
                steps.indexOf(GridClosePlan.Step.REOPEN_CAMPFIRE)
                        > steps.indexOf(GridClosePlan.Step.CLOSE_SCREEN));
    }

    // ---------------------------------------------------------------- wiring

    /** The plan is worthless if cancel_grid never asks for it. */
    @Test
    public void cancelGridAsksForTheCancelPlan() throws IOException {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        assertTrue("cancel_grid 必须带 cancel=true 走 closeGrid，否则 #90 会复发",
                executor.contains("closeGrid(screen, \"cancel_grid\", true)"));
        assertTrue("closeGrid 必须由 GridClosePlan.steps(...) 驱动",
                executor.contains("GridClosePlan.steps("));
        assertTrue("executor 必须真的调用 campfireUI.reopen()",
                executor.contains("ui.reopen()"));
        assertTrue("REOPEN_CAMPFIRE 只能在网格关掉之后执行",
                executor.contains("if (closed) {\n                        reopenCampfireUi(where);"));
    }
}
