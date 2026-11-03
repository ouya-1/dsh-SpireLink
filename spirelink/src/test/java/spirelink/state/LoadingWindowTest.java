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
 * Issue #122 · C12-01（v3）：{@code act{continue_run}} 回 applied 之后，载入窗口里
 * {@code get_state} / {@code legal_actions} 仍是 {@code ready:true} + 继续广告 {@code continue_run}；
 * 过渡帧的文案是通用模板、{@code meta.awaiting} 也不是 {@code "loading"}。
 *
 * <p>两个被真机证伪的判据（这里的测试就是防止再走回去）：</p>
 * <ul>
 *   <li>v1 用 {@code CardCrawlGame.loadingSave} + {@code AbstractDungeon.player == null} ——
 *       {@code MenuButton.resumeGame()} 的第一步就把 {@code AbstractDungeon.player} 建好了，条件恒假；</li>
 *   <li>v2 用 {@code !CardCrawlGame.isInARun()} —— 地牢一建好 {@code mode} 就是 GAMEPLAY，而屏还没切过去
 *       （真机 sv=3 的 {@code ui="other"} 那几帧），会提前关窗、文案退回通用模板。</li>
 * </ul>
 *
 * <p>v3 只用：我们自己打的时间戳 + 状态层算出的 ui（还是「开局前的屏」）+ 上界。</p>
 */
public class LoadingWindowTest {

    // ------------------------------------------------------------ 纯判据

    @Test
    public void anOpenWindowNeedsAllThreeConditions() {
        assertTrue("刚点过 + 还是主菜单 = 载入中", LoadingWindow.activeFor(true, 0L, "main_menu"));
        assertTrue(LoadingWindow.activeFor(true, LoadingWindow.MAX_LOADING_MS - 1L, "main_menu"));
        assertTrue("过渡桶 other 也算载入（真机 sv=3 就是这一帧）",
                LoadingWindow.activeFor(true, 10L, "other"));
        assertTrue(LoadingWindow.activeFor(true, 10L, null));
    }

    @Test
    public void noMarkerMeansNoWindow() {
        assertFalse("没点过 continue_run 就不是载入",
                LoadingWindow.activeFor(false, 0L, "main_menu"));
    }

    @Test
    public void theWindowEndsWhenARunScreenIsUp() {
        assertFalse("战斗屏已经在屏幕上，窗口必须关掉", LoadingWindow.activeFor(true, 0L, "combat"));
        assertFalse(LoadingWindow.activeFor(true, 0L, "map"));
        assertFalse(LoadingWindow.activeFor(true, 0L, "neow"));
        assertFalse(LoadingWindow.activeFor(true, 0L, "reward"));
    }

    @Test
    public void theWindowDoesNotLieForever() {
        assertFalse("超过上界就不再自称载入（载入失败时没有人会来关窗）",
                LoadingWindow.activeFor(true, LoadingWindow.MAX_LOADING_MS, "main_menu"));
        assertFalse(LoadingWindow.activeFor(true, LoadingWindow.MAX_LOADING_MS * 10L, "main_menu"));
        assertFalse("时钟回拨也不许开窗", LoadingWindow.activeFor(true, -5L, "main_menu"));
    }

    @Test
    public void onlyPreRunScreensCount() {
        assertTrue(LoadingWindow.beforeRun(null));
        assertTrue(LoadingWindow.beforeRun("main_menu"));
        assertTrue(LoadingWindow.beforeRun("char_select"));
        assertTrue(LoadingWindow.beforeRun("mode_select"));
        assertTrue("过渡桶里屏还没切过去，必须继续报载入", LoadingWindow.beforeRun("other"));
        assertFalse(LoadingWindow.beforeRun("combat"));
        assertFalse(LoadingWindow.beforeRun("map"));
        assertFalse(LoadingWindow.beforeRun("event"));
        assertFalse(LoadingWindow.beforeRun("grid"));
    }

    // ------------------------------------------------------------ 回归线

    /** 两个被证伪的判据都不许回来：这里连游戏类都不许引用，判据只看我们自己的两个事实。 */
    @Test
    public void theRuleMustNotUseGameFlagsAgain() throws IOException {
        String window = source("src/main/java/spirelink/state/LoadingWindow.java");
        assertFalse("不许再看 AbstractDungeon.player（resumeGame 一开始就建玩家对象）",
                window.contains("AbstractDungeon.player"));
        assertFalse("不许再依赖 loadingSave 的时序", window.contains("loadingSave"));
        assertFalse("不许再用 isInARun（它比屏幕早一帧变真）", window.contains("isInARun"));
        assertFalse("判据不该再引用任何游戏类", window.contains("CardCrawlGame"));
        assertTrue("要有我们自己打的时间戳", window.contains("public static void noteRunStart()"));
        assertTrue("要接状态层的 ui", window.contains("public static void noteUi(String ui)"));
        assertTrue("要能主动关窗", window.contains("public static void clear()"));
        assertTrue(window.contains("resetForTests"));
    }

    /** 状态层必须把每帧的 ui 喂进来，否则窗口不知道自己什么时候该结束。 */
    @Test
    public void theStateLayerFeedsTheUi() throws IOException {
        String builder = source("src/main/java/spirelink/state/StateBuilder.java");
        assertTrue("deriveUi 要把结果交给 LoadingWindow",
                builder.contains("LoadingWindow.noteUi(ui)"));
        int wrapper = builder.indexOf("String raw = deriveUiRaw(logUnmapped);");
        assertTrue("deriveUi 必须统一经过包装（两条重载都算）", wrapper > 0);
    }

    /** 载入原因必须排在 menu 之前判，否则永远轮不到它。 */
    @Test
    public void theGateChecksLoadingBeforeTheMenu() throws IOException {
        String gate = source("src/main/java/spirelink/state/StableGate.java");
        assertTrue("Inputs 要有 loading 字段", gate.contains("public boolean loading;"));
        int loading = gate.indexOf("if (in.loading)");
        int menu = gate.indexOf("if (in.mode == Mode.MENU)");
        assertTrue(loading > 0);
        assertTrue(menu > 0);
        assertTrue("loading 必须排在 menu 之前（载入时 mode 仍是 MENU）", loading < menu);
        assertTrue("载入要回 ready:false", gate.contains("new Decision(false, \"loading\")"));
        assertTrue("probe 要把判据喂进去", gate.contains("in.loading = LoadingWindow.active();"));
    }

    /** meta.awaiting 是有限词表：载入要自成一档（QA 明确点了这一条）。 */
    @Test
    public void awaitingHasItsOwnWord() throws IOException {
        String builder = source("src/main/java/spirelink/state/StateBuilder.java");
        int loadingCase = builder.indexOf("case \"loading\":");
        assertTrue("awaitingOf 要有 loading 分支", loadingCase > 0);
        String around = builder.substring(loadingCase, Math.min(builder.length(), loadingCase + 120));
        assertTrue("载入要原样回答 loading: " + around, around.contains("return \"loading\";"));
    }

    // ------------------------------------------------------------ mcp 侧接线

    /** 时间戳必须在我们把动作交给执行器之前打，失败路径必须关窗。 */
    @Test
    public void theClickIsRememberedAndForgottenAtTheRightMoments() throws IOException {
        String code = source("src/main/java/spirelink/mcp/ToolCatalog.java");
        int act = code.indexOf("private ToolResult act(JsonObject arguments)");
        assertTrue(act > 0);
        String body = code.substring(act, Math.min(code.length(), act + 14000));
        int note = body.indexOf("spirelink.state.LoadingWindow.noteRunStart();");
        int reach = body.indexOf("ActionExecutor.reach(");
        assertTrue("要记下我们点继续的时刻", note > 0);
        assertTrue("必须早于执行器（否则第一帧就漏了）", reach > note);
        assertTrue("continue_run / embark 才算", body.contains("startsRunLoad(type)"));
        int clears = 0;
        for (int i = body.indexOf("clearSceneWindows(startsRun, startsExit);"); i > 0;
                i = body.indexOf("clearSceneWindows(startsRun, startsExit);", i + 1)) {
            clears++;
        }
        assertTrue("三条失败路径（队列 BUSY / 内部异常 / 执行器拒绝）都要关窗，实到 " + clears,
                clears >= 3);
        assertTrue("起点动作集合要覆盖 continue_run 与 embark",
                code.contains("spirelink.actions.ActionTypes.CONTINUE_RUN.equals(type)")
                        && code.contains("spirelink.actions.ActionTypes.EMBARK.equals(type)"));
    }

    // ------------------------------------------------------------ helpers

    /** 去掉注释，只留可执行代码（注释里提到旧写法不算违规）。 */
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

    /** Reads a repo file relative to the maven module directory (same trick as the other wiring tests). */
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
