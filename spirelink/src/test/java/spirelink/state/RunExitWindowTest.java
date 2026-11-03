package spirelink.state;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #123 · S2：战斗里 {@code abandon_mid_run} applied 之后 0.8–1.6 秒里，状态层还在描述一个正在消失
 * 的地牢 —— {@code ui=idle}（3 帧）+ 唯一动作 {@code proceed「打开地图」}，接着 {@code ui=map} +
 * {@code choose_map_node}，真发出去却被 {@code ILLEGAL_ACTION: ui=main_menu 下不能执行} 打回。
 *
 * <p>规则：过渡期（我们刚点了放弃）既不报旧屏、也不广告旧屏的动作。判据与自己人的点击挂钩，不依赖游戏
 * 标志位时序：{@code noteRunExit()} 打点 → 状态层算出 {@code main_menu} 才算结束 → 10 秒上界兜底。</p>
 */
public class RunExitWindowTest {

    // ------------------------------------------------------------ 纯判据

    @Test
    public void anOpenWindowNeedsTheClickAndAMissingMenu() {
        assertTrue("刚点过放弃、菜单还没起来 = 过渡中",
                RunExitWindow.activeFor(true, 0L, "combat"));
        assertTrue(RunExitWindow.activeFor(true, 20L, "map"));
        assertTrue("idle 也是旧地牢的遗留值，同样不能报",
                RunExitWindow.activeFor(true, 20L, "idle"));
    }

    @Test
    public void theWindowEndsWhenTheMenuIsUp() {
        assertFalse("主菜单起来了，过渡结束",
                RunExitWindow.activeFor(true, 0L, "main_menu"));
    }

    @Test
    public void noClickMeansNoWindow() {
        assertFalse(RunExitWindow.activeFor(false, 0L, "combat"));
    }

    @Test
    public void theWindowDoesNotLieForever() {
        assertFalse(RunExitWindow.activeFor(true, RunExitWindow.MAX_MS, "combat"));
        assertFalse(RunExitWindow.activeFor(true, -1L, "combat"));
    }

    // ------------------------------------------------------------ 接线

    /** 闸门必须把 leaving_run 排在 menu/屏分支之前，否则旧屏照样会被当成 ready。 */
    @Test
    public void theGateRefusesDuringTheExit() throws IOException {
        String gate = source("src/main/java/spirelink/state/StableGate.java");
        assertTrue("Inputs 要有 leavingRun", gate.contains("public boolean leavingRun;"));
        int leaving = gate.indexOf("if (in.leavingRun)");
        int menu = gate.indexOf("if (in.mode == Mode.MENU)");
        int screen = gate.indexOf("if (in.screenUp)");
        assertTrue(leaving > 0);
        assertTrue("要排在 menu 之前（放弃瞬间 mode 仍是 GAMEPLAY，但屏已经不算数）",
                leaving < menu);
        assertTrue("也要排在「屏等着你选」之前", leaving < screen);
        assertTrue(gate.contains("new Decision(false, \"leaving_run\")"));
        assertTrue("probe 要把判据喂进去", gate.contains("in.leavingRun = RunExitWindow.active();"));
    }

    /** 过渡期 ui 必须是过渡桶，不能是 idle/map 这种「已经过去」的屏。 */
    @Test
    public void theReportedUiBecomesTheTransitionBucket() throws IOException {
        String builder = source("src/main/java/spirelink/state/StateBuilder.java");
        int exit = builder.indexOf("RunExitWindow.noteUi(raw);");
        int substitute = builder.indexOf("String reported = RunExitWindow.active() ? \"other\" : raw;");
        assertTrue("deriveUi 要把原始 ui 喂给窗口", exit > 0);
        assertTrue("窗口开着就替换掉旧屏", substitute > exit);
        assertTrue("上报值要落到 reportedUi()（C15-01 靠它统一口径）",
                builder.indexOf("lastReportedUi = reported;") > substitute);
        // awaiting 词表：leaving_run 自成一档（值仍是 transition，但不会掉进 default 猜屏）
        int awaiting = builder.indexOf("case \"leaving_run\":");
        assertTrue("awaitingOf 要有 leaving_run 分支", awaiting > 0);
    }

    /** mcp 侧：放弃类动作打点、失败关窗；过渡期谁都别想发动作。 */
    @Test
    public void theClickIsRememberedAndActsAreRefused() throws IOException {
        String code = source("src/main/java/spirelink/mcp/ToolCatalog.java");
        int act = code.indexOf("private ToolResult act(JsonObject arguments)");
        assertTrue(act > 0);
        String body = code.substring(act, Math.min(code.length(), act + 14000));
        assertTrue("要记下我们点放弃的时刻", body.contains("spirelink.state.RunExitWindow.noteRunExit();"));
        assertTrue("失败路径要关窗", body.contains("clearSceneWindows(startsRun, startsExit);"));
        assertTrue("放弃类动作集合", code.contains("ActionTypes.ABANDON_MID_RUN.equals(type)")
                && code.contains("ActionTypes.ABANDON_RUN.equals(type)")
                && code.contains("ActionTypes.CONFIRM_ABANDON.equals(type)"));
        assertTrue("预检要带上 leavingRun",
                code.contains("preflightRejectionCode(loadingNow, leavingNow,"));
        assertTrue("文案要说「正在离开本局」", code.contains("leavingRunMessage(\"act\")"));
    }

    // ------------------------------------------------------------ helpers

    private static String source(String relative) throws IOException {
        String raw = read(relative);
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        int n = raw.length();
        while (i < n) {
            if (raw.startsWith("/*", i)) {
                int end = raw.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (raw.startsWith("//", i)) {
                int end = raw.indexOf('\n', i);
                i = end < 0 ? n : end;
                continue;
            }
            out.append(raw.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static String read(String relative) throws IOException {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
            dir = dir.getParentFile();
        }
        fail("找不到 " + relative + "（user.dir=" + System.getProperty("user.dir") + "）");
        return null;
    }
}
