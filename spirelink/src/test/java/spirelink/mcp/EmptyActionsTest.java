package spirelink.mcp;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #120 · C11-03：切屏那一瞬间 {@code legal_actions} 回 {@code actions:[]} 且 {@code ready:true}、
 * 没有 {@code error} —— 一帧之隔就从「能行动但没有动作」变成「有动作」，只看一帧的 agent 会当成死路。
 *
 * <p>根因是「能不能行动」和「有没有动作」由两处决定：{@code ready} 来自稳定性闸门，动作表来自
 * planner。屏刚换、动画刚开始时闸门说 ready，planner 却什么都给不出来，于是回了一个自相矛盾的包。</p>
 *
 * <p>修法：两个字段在同一行决定（{@code actionable = ready && actions.size() > 0}），空表一律附上
 * 同一句解释（{@link ToolCatalog#emptyActionsMessage}），{@code get_state} 的 {@code alignReady}
 * 也用它，并把 {@code ok} 一起翻成 false（「ok:true 但一个动作都没有」正是让人误判的那层皮）。</p>
 */
public class EmptyActionsTest {

    private static String toolCatalog() throws IOException {
        return DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
    }

    private static String slice(String code, String marker, int length) {
        int at = code.indexOf(marker);
        assertTrue("源码里找不到 " + marker, at >= 0);
        return code.substring(at, Math.min(code.length(), at + length));
    }

    @Test
    public void theSentenceSaysWaitNotDeadEnd() {
        String switching = ToolCatalog.emptyActionsMessage("event", true);
        assertTrue(switching, switching.contains("ui=event"));
        assertTrue("要说清是「还没到」而不是「没有」: " + switching, switching.contains("还没有可执行动作"));
        assertTrue("要给出下一步: " + switching, switching.contains("重试 legal_actions"));
        assertTrue("要解释 ready 为什么是 false: " + switching, switching.contains("ready"));

        String moving = ToolCatalog.emptyActionsMessage("combat", false);
        assertTrue(moving, moving.contains("ui=combat"));
        assertTrue("闸门没 ready 时是另一种解释: " + moving, moving.contains("还没静止"));

        assertTrue("屏名缺失也要有话说", ToolCatalog.emptyActionsMessage(null, true).contains("当前屏"));
        assertTrue(ToolCatalog.emptyActionsMessage("  ", true).contains("当前屏"));
    }

    @Test
    public void readyIsDerivedFromTheActionListInOnePlace() throws IOException {
        String plan = slice(toolCatalog(), "private JsonObject planPayload()", 2600);
        assertTrue("ready 必须由「闸门 + 动作表」一起决定: " + plan,
                plan.contains("boolean actionable = snapshot.ready && actions.size() > 0;"));
        assertTrue(plan, plan.contains("payload.addProperty(\"ready\", actionable);"));
        assertFalse("不许再提前用闸门的 ready 直接回包（那正是 ready:true + actions:[]）",
                plan.contains("payload.addProperty(\"ready\", snapshot.ready);"));
        // #121·C12-01 之后由 emptyActionsReason 选句子（载入中 / 屏上没动作），但规则没变：
        // 空表一定带一句解释。
        assertTrue("空表要带 NOT_READY 说明: " + plan,
                plan.contains("emptyActionsReason(loading, spirelink.state.RunExitWindow.active(), ui,"));
        assertTrue("载入与否要问 LoadingWindow: " + plan,
                plan.contains("boolean loading = spirelink.state.LoadingWindow.active();"));
        assertTrue("空表要标出是哪个屏: " + plan, plan.contains("blocked_ui"));
    }

    @Test
    public void getStateExplainsTheSameWayAndStopsSayingOk() throws IOException {
        String align = slice(toolCatalog(), "private boolean alignReady(", 1800);
        assertTrue("get_state 的空动作屏也要有解释: " + align, align.contains("emptyActionsMessage(ui, true)"));
        assertTrue("ok 要与 ready 一起翻: " + align, align.contains("body.addProperty(\"ok\", false);"));
        assertTrue("调用方要能知道被降级了: " + align, align.contains("return true;"));
        String code = toolCatalog();
        assertTrue("降级要记下来: " + slice(code, "JsonObject body = payloadFor(snapshot);", 200),
                slice(code, "JsonObject body = payloadFor(snapshot);", 200)
                        .contains("boolean blockedByActions = alignReady(body);"));
        assertTrue("降级要反映到 ToolResult（否则 ok/ready 与实际不符）",
                code.contains("new ToolResult(body, !snapshot.ready || blockedByActions)"));
    }
}
