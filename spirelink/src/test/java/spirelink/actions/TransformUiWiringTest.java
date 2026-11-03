package spirelink.actions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #83: {@code ui=transform} advertised nothing but {@code proceed}, so the agent could only
 * shove the "transform a card" picker aside instead of picking a card.
 *
 * <p>Vanilla runs that picker on the ordinary {@code GridCardSelectScreen}
 * ({@code forTransform = true}), which is why the fix is "plan and execute it as a grid" rather than
 * a new action type. The wiring spans three files, and only the third one is reachable from a
 * headless unit test ({@code AbstractDungeon} cannot be initialised without the game), so the other
 * two are pinned down at source level — the same technique {@code ActionTypesCoverageTest} already
 * uses for the I4 invariant.</p>
 */
public class TransformUiWiringTest {

    private static String read(String relative) throws IOException {
        File dir = new File(System.getProperty("user.dir"));
        File file = null;
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                file = candidate;
                break;
            }
            dir = dir.getParentFile();
        }
        if (file == null) {
            throw new IOException("找不到 " + relative);
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** A transform screen whose only action is proceed is exactly the bug. */
    @Test
    public void transformIsModelledAsACardSelectInTheLegalityMatrix() {
        assertTrue(ActionLegality.allows("transform", ActionTypes.CHOOSE_GRID_CARDS));
        assertTrue(ActionLegality.allows("transform", ActionTypes.CONFIRM_GRID));
        assertTrue(ActionLegality.allows("transform", ActionTypes.CANCEL_GRID));
    }

    /** The planner must route transform through the shared grid planner, not through "proceed". */
    @Test
    public void plannerRoutesTransformThroughTheGridPlanner() throws IOException {
        String planner = read("src/main/java/spirelink/actions/ActionPlanner.java");
        int at = planner.indexOf("case \"transform\":");
        if (at < 0) {
            fail("ActionPlanner 没有 case \"transform\"（issue #83 会复现：transform 只剩 proceed）");
        }
        int end = planner.indexOf("break;", at);
        assertTrue("case transform 里没有 break", end > at);
        String body = planner.substring(at, end);
        assertTrue("case transform 必须调用 grid(out) 才能真的选牌: " + body,
                body.contains("grid(out)"));
        assertFalse("case transform 不能只给 proceed: " + body,
                body.contains("ActionTypes.PROCEED") && !body.contains("grid(out)"));
    }

    /** Body of one method, up to the closing brace that starts the next declaration. */
    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        if (at < 0) {
            fail("找不到方法: " + signature);
        }
        int end = source.indexOf("\n    private static ", at + signature.length());
        int end2 = source.indexOf("\n    static ", at + signature.length());
        int end3 = source.indexOf("\n    public static ", at + signature.length());
        int cut = source.length();
        for (int candidate : new int[] {end, end2, end3}) {
            if (candidate > at && candidate < cut) {
                cut = candidate;
            }
        }
        return source.substring(at, cut);
    }

    /** The executor must accept the grid actions when vanilla reports TRANSFORM instead of GRID. */
    @Test
    public void executorTreatsTransformAsACardSelectScreen() throws IOException {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        int helper = executor.indexOf("static boolean cardSelectScreenUp()");
        if (helper < 0) {
            fail("ActionExecutor 缺少 cardSelectScreenUp()");
        }
        int helperEnd = executor.indexOf("}", helper);
        String body = executor.substring(helper, helperEnd);
        assertTrue("cardSelectScreenUp 必须认 GRID: " + body,
                body.contains("CurrentScreen.GRID"));
        assertTrue("cardSelectScreenUp 必须认 TRANSFORM: " + body,
                body.contains("CurrentScreen.TRANSFORM"));
        // The grid *entry points* may not keep the old hard "screen != GRID" guard, or the advertised
        // choose_grid_cards would be rejected the moment the ui really is transform.
        // (Campfire-smith and shop-purge keep a hard GRID check on purpose: those verify that *their*
        // transition produced a grid, and they never run on a transform screen.)
        String entry = methodBody(executor, "private static ActionResult chooseGridCards(");
        assertTrue("chooseGridCards 必须用 cardSelectScreenUp() 判屏", entry.contains("cardSelectScreenUp()"));
        assertFalse("chooseGridCards 还在硬判 screen != GRID",
                entry.contains("screen != AbstractDungeon.CurrentScreen.GRID"));
        String close = methodBody(executor, "private static boolean applyGridCloseStep(");
        assertTrue("关闭链路必须用 cardSelectScreenUp() 判屏（否则 TRANSFORM 屏关不掉）",
                close.contains("cardSelectScreenUp()"));
        String force = methodBody(executor, "private static ActionResult forceCloseGrid(");
        assertTrue("forceCloseGrid 必须复用同一条关闭链路（否则又会出现关不掉 TRANSFORM 屏的分支）",
                force.contains("closeGrid(screen"));
    }
}