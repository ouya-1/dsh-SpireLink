package spirelink.mcp;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #123 · C14-01/02/03：屏切换类动作（continue_run / embark）**applied 却回 {@code outcome:"queued"}**
 * 且阻塞约 10.7 秒；char_select 的 choose_character 每条固定约 10 秒、state_version 不前进。
 *
 * <p>根因是时间轴：这些动作本身在主线程上跑完整个地牢构建（QA 实测 10,689 ms），而 {@code state_version}
 * 要等地牢建好才动 —— 所以「等版本变化」的 settle 循环必然满 10 秒超时，把一次已经落地的点击报成
 * 「还没落地」。修法：载入类立刻回 {@code applied + settled:false}（并让载入窗口解释等待），
 * 选中类按「提交即结算」处理（它们根本不会改版本号）。</p>
 */
public class ScreenChangeAnswerTest {

    // ------------------------------------------------------------ 哪些动作属于这一类

    @Test
    public void theRunStartActionsAreNamed() {
        assertTrue(ToolCatalog.startsRunLoad("continue_run"));
        assertTrue(ToolCatalog.startsRunLoad("embark"));
        assertFalse("地图/出牌不是载入", ToolCatalog.startsRunLoad("choose_map_node"));
        assertFalse(ToolCatalog.startsRunLoad(null));
    }

    @Test
    public void theAbandonActionsAreNamed() {
        assertTrue(ToolCatalog.startsRunExit("abandon_run"));
        assertTrue(ToolCatalog.startsRunExit("abandon_mid_run"));
        assertTrue(ToolCatalog.startsRunExit("confirm_abandon"));
        assertFalse(ToolCatalog.startsRunExit("continue_run"));
        assertFalse(ToolCatalog.startsRunExit(null));
    }

    @Test
    public void theSelectionActionsSettleAtSubmission() {
        assertTrue("选人不会移动 state_version（QA：237→237）",
                ToolCatalog.settlesAtSubmission("choose_character"));
        assertTrue(ToolCatalog.settlesAtSubmission("choose_ascension"));
        assertFalse("出牌必须等版本推进", ToolCatalog.settlesAtSubmission("play_card"));
        assertFalse(ToolCatalog.settlesAtSubmission(null));
    }

    // ------------------------------------------------------------ 回包与读超时

    @Test
    public void readsGetAShortTimeoutOnlyInsideTheLoadingWindow() {
        assertEquals("窗口没开就是原来的读超时", ToolCatalog.COMMAND_TIMEOUT_MS,
                ToolCatalog.readTimeoutMs());
        assertTrue("窗口内的读要快（否则轮询看不到载入帧）",
                ToolCatalog.LOADING_READ_TIMEOUT_MS < ToolCatalog.COMMAND_TIMEOUT_MS);
        assertEquals(500L, ToolCatalog.LOADING_READ_TIMEOUT_MS);
    }

    /** 载入类回包不许再等 settle 循环：early return 必须在循环之前，且形状自洽。 */
    @Test
    public void theRunStartAnswerIsImmediateAndSelfConsistent() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        int act = code.indexOf("private ToolResult act(JsonObject arguments)");
        String body = code.substring(act, Math.min(code.length(), act + 14000));
        int earlyReturn = body.indexOf("if (startsRun || startsExit) {");
        int deadline = body.indexOf("long deadline = System.currentTimeMillis() + ACT_STABLE_TIMEOUT_MS");
        assertTrue("载入/放弃类要早退（S2 的放弃同样等不到版本变化）", earlyReturn > 0);
        assertTrue("早退必须在 settle 循环之前", deadline > earlyReturn);
        assertTrue(body.contains("transitionSubmittedPayload(type, args, startsRun)"));

        int builder = code.indexOf("private JsonObject transitionSubmittedPayload(");
        assertTrue(builder > 0);
        String shape = code.substring(builder, Math.min(code.length(), builder + 2200));
        assertTrue("落地的点击要报 applied（不是 queued）: " + shape,
                shape.contains("payload.addProperty(\"outcome\", \"applied\")"));
        assertTrue("但要说明世界还没静止", shape.contains("payload.addProperty(\"settled\", false)"));
        assertTrue("动作表必须是空的（旧屏不许再被广告）",
                shape.contains("payload.add(\"actions\", new JsonArray())"));
        assertTrue("awaiting 要说清是载入还是切屏",
                shape.contains("payload.addProperty(\"awaiting\", loading ? \"loading\" : \"transition\")"));
        assertTrue("要给下一步", shape.contains("轮询 get_state"));
        assertFalse("不许顺手 attachPlan 把旧屏动作带回来", shape.contains("attachPlan(payload)"));
    }

    /** 「选人」类按已结算处理：走正常 applied 路径，带新鲜动作表。 */
    @Test
    public void theSelectionAnswerKeepsTheNormalAppliedPath() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        int settle = code.indexOf("boolean settled = actionResult.verified || settlesAtSubmission(type);");
        assertTrue("settle 起点要包含提交即结算的动作", settle > 0);
    }
}
