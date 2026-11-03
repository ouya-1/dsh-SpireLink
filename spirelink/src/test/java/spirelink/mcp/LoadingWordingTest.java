package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #121 · C12-01 / C12-04：载入窗口的措辞，以及 {@code blocked_ui} 在两个入口上的对称。
 *
 * <p>C12-01：{@code continue_run} 之后 1.6 秒内 {@code ready:true} + 继续广告 {@code continue_run}。
 * 闸门侧（{@code state/LoadingWindow} + {@code StableGate}）负责把 ready 变 false，这里钉的是
 * <b>agent 读到的那句话</b>：{@code get_state} / {@code legal_actions} / {@code act} 三个入口说的是
 * 同一件事（「正在载入，别重发 continue_run」），而不是「动画没结束，请重试」。</p>
 *
 * <p>C12-04：{@code blocked_ui} 原来只在 {@code legal_actions} 顶层；{@code get_state} 的 NOT_READY
 * 只有 {@code meta.blocked_ui}。现在两个入口的顶层都有，同一个问题同一个地址。</p>
 */
public class LoadingWordingTest {

    private static JsonObject json(String raw) {
        return new JsonParser().parse(raw).getAsJsonObject();
    }

    // ------------------------------------------------------------ C12-01 措辞

    @Test
    public void theLoadingSentenceSaysWhatNotToDo() {
        String message = ToolCatalog.loadingMessage("get_state");
        assertTrue(message, message.contains("载入"));
        assertTrue("要点名 continue_run 已经点过了: " + message, message.contains("continue_run"));
        assertTrue("要明说不是「没点动」: " + message, message.contains("没点动"));
        assertTrue("要给下一步: " + message, message.contains("ready:true"));
        assertTrue("要带上被回答的工具名", ToolCatalog.loadingMessage("legal_actions")
                .contains("legal_actions"));
    }

    @Test
    public void notReadyMessagePicksLoadingOverTheGenericRetry() {
        assertEquals("载入中就用载入那句话", ToolCatalog.loadingMessage("get_state"),
                ToolCatalog.notReadyMessage(true, "get_state"));
        String generic = ToolCatalog.notReadyMessage(false, "get_state");
        assertTrue(generic, generic.contains("动画未结束"));
        assertFalse("非载入时不许说载入", generic.contains("载入"));
        assertTrue(generic.contains("get_state"));
    }

    @Test
    public void theEmptyActionListPrefersTheLoadingReason() {
        assertEquals("载入中的空动作表要说「载入」", ToolCatalog.loadingMessage("legal_actions"),
                ToolCatalog.emptyActionsReason(true, false, "main_menu", true));
        assertEquals("不载入时还是 C11-03 那句", ToolCatalog.emptyActionsMessage("event", true),
                ToolCatalog.emptyActionsReason(false, false, "event", true));
        assertEquals(ToolCatalog.emptyActionsMessage("combat", false),
                ToolCatalog.emptyActionsReason(false, false, "combat", false));
        // Issue #123 · S2：放弃过渡期要说「正在离开本局」，而不是把过渡桶当真实屏描述。
        assertEquals(ToolCatalog.leavingRunMessage("legal_actions"),
                ToolCatalog.emptyActionsReason(false, true, "other", false));
    }

    // ------------------------------------------------------------ C12-04 blocked_ui

    @Test
    public void blockedUiComesFromTheSameBodyGetStateServes() {
        assertEquals("main_menu", ToolCatalog.blockedUi(json("{\"meta\":{\"ui\":\"main_menu\"}}")));
        assertEquals("combat", ToolCatalog.blockedUi(json("{\"meta\":{\"ui\":\"combat\"}}")));
        assertEquals("没有 body 也要有话说", "other", ToolCatalog.blockedUi(null));
        assertEquals("other", ToolCatalog.blockedUi(json("{\"meta\":{}}")));
    }

    @Test
    public void bothEntryPointsReportItAtTheTopLevel() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        int payloadFor = code.indexOf("private JsonObject payloadFor(");
        assertTrue(payloadFor > 0);
        assertTrue("get_state 的 not-ready 回包要带顶层 blocked_ui: "
                        + code.substring(payloadFor, payloadFor + 900),
                code.substring(payloadFor, payloadFor + 900)
                        .contains("payload.addProperty(\"blocked_ui\""));
        int notReady = code.indexOf("private JsonObject notReadyPayload(");
        assertTrue(notReady > 0);
        assertTrue("缓存回包（队列超时那条）也要带",
                code.substring(notReady, notReady + 1400).contains("payload.addProperty(\"blocked_ui\""));
        int align = code.indexOf("private boolean alignReady(");
        assertTrue(align > 0);
        assertTrue("把 ready 翻成 false 的那条路也要补顶层字段: "
                        + code.substring(align, align + 1600),
                code.substring(align, align + 1600).contains("body.addProperty(\"blocked_ui\""));
    }

    // ------------------------------------------------------------ C12-01 接线

    @Test
    public void everyNotReadyPathAsksTheLoadingWindow() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        assertTrue("get_state 的 not-ready 措辞要看载入窗口",
                code.contains("notReadyReason(spirelink.state.LoadingWindow.active(),"));
        assertTrue("队列超时那条也要看",
                code.contains("notReadyReason(loading, !loading, \"get_state\")"));
        assertTrue("legal_actions 的空表原因要看",
                code.contains("emptyActionsReason(loading, spirelink.state.RunExitWindow.active(), ui,"));
        assertTrue("loading 必须来自 LoadingWindow",
                code.contains("boolean loading = spirelink.state.LoadingWindow.active();"));
        assertTrue("legal_actions 的 not-ready 回包也要看",
                code.contains("loadingMessage(\"legal_actions\")"));
        assertTrue("act 的预检也要看",
                code.contains("preflightRejectionCode(loadingNow, leavingNow,"));
        assertTrue("两个窗口都要从状态层取",
                code.contains("boolean loadingNow = spirelink.state.LoadingWindow.active();")
                        && code.contains("boolean leavingNow = spirelink.state.RunExitWindow.active();"));
    }

    /** 载入窗口里 {@code continue_run} 不能再被广告：ready:false 时动作表根本不构建。 */
    @Test
    public void anUnreadyScreenAdvertisesNothing() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        int plan = code.indexOf("private JsonObject planPayload()");
        assertTrue(plan > 0);
        String body = code.substring(plan, Math.min(code.length(), plan + 2200));
        assertTrue("动作只在 ready 时构建: " + body, body.contains("if (snapshot.ready) {"));
        assertTrue("ready 又必须由动作表决定（C11-03）: " + body,
                body.contains("boolean actionable = snapshot.ready && actions.size() > 0;"));
    }
}
