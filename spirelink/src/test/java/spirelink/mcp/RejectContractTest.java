package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #121 · C12-02 / C12-03：两条拒绝路径的回包形状，以及「同时命中多个拒绝原因时谁说话」。
 *
 * <p>C12-02 现场：{@code ACTION_IN_FLIGHT}(BUSY) 回包只有 {@code error} + 三个诊断字段，没有
 * {@code ui}/{@code actions}；{@code ILLEGAL_ACTION} 却带 {@code ui}/{@code ready}/{@code actions}
 * （它走 {@code attachPlan}）。同一个客户端的解析代码要写两套。</p>
 *
 * <p>C12-03 现场：{@code state_version} 落后几拍时发一个本屏不存在的动作，回的是
 * {@code ILLEGAL_ACTION}。优先级没写下来，看起来像看运气。现在由
 * {@link ToolCatalog#preflightRejectionCode} 定死：载入中 → 版本过期 → 动作在飞 → 才轮到执行器。</p>
 */
public class RejectContractTest {

    private static JsonObject json(String raw) {
        return new JsonParser().parse(raw).getAsJsonObject();
    }

    // ------------------------------------------------------------ C12-02 形状

    @Test
    public void aRejectionWithoutAPlanStillAnswersUiReadyAndActions() {
        JsonObject payload = json("{\"ok\":false,\"outcome\":\"rejected\","
                + "\"error\":{\"code\":\"ACTION_IN_FLIGHT\",\"message\":\"m\"}}");
        ToolCatalog.withRejectContext(payload, "combat");
        assertEquals("ui 取缓存快照", "combat", payload.get("ui").getAsString());
        assertFalse("刚拒签，此刻没有可执行动作", payload.get("ready").getAsBoolean());
        assertNotNull(payload.get("actions"));
        assertTrue(payload.get("actions").isJsonArray());
        assertEquals(0, payload.getAsJsonArray("actions").size());
    }

    @Test
    public void freshPlanValuesAreNeverOverwritten() {
        JsonObject payload = json("{\"ok\":false,\"ui\":\"map\",\"ready\":true,"
                + "\"actions\":[{\"type\":\"choose_map_node\"}]}");
        ToolCatalog.withRejectContext(payload, "combat");
        assertEquals("attachPlan 拿到的鲜活 ui 必须赢", "map", payload.get("ui").getAsString());
        assertTrue("鲜活 ready 也必须赢", payload.get("ready").getAsBoolean());
        assertEquals("鲜活动作表必须保留", 1, payload.getAsJsonArray("actions").size());
    }

    @Test
    public void theContextFillerIsNullSafe() {
        assertNull(ToolCatalog.withRejectContext(null, "combat"));
        JsonObject noUi = json("{}");
        ToolCatalog.withRejectContext(noUi, null);
        assertEquals("没有 ui 时退回 other（与 queued 回包一致）", "other", noUi.get("ui").getAsString());
    }

    @Test
    public void bothRejectionBuildersUseTheSameFiller() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        int rejected = code.indexOf("private JsonObject rejectedPayload(");
        int reject = code.indexOf("private ToolResult rejectAct(");
        assertTrue(rejected > 0);
        assertTrue(reject > 0);
        assertTrue("rejectedPayload 必须补形状",
                code.substring(rejected, rejected + 1400).contains("attachRejectContext(payload)"));
        assertTrue("rejectAct 必须补形状",
                code.substring(reject, reject + 1200).contains("attachRejectContext(payload)"));
    }

    // ------------------------------------------------------------ C12-03 优先级

    @Test
    public void loadingBeatsEverything() {
        assertEquals("载入中：重读也没用，先等", "NOT_READY",
                ToolCatalog.preflightRejectionCode(true, false, 11, 21, true));
        assertEquals("载入中 + 版本过期也是先等", "NOT_READY",
                ToolCatalog.preflightRejectionCode(true, false, 11, 21, false));
    }

    /** Issue #123 · S2：放弃过渡期同样先等（旧屏已经不作数，重读只会读到它）。 */
    @Test
    public void leavingTheRunAlsoWaits() {
        assertEquals("NOT_READY", ToolCatalog.preflightRejectionCode(false, true, 11, 21, false));
        assertEquals("NOT_READY", ToolCatalog.preflightRejectionCode(false, true, 21, 21, true));
    }

    @Test
    public void staleVersionBeatsTheExecutorsScreenJudgement() {
        assertEquals("版本过期优先：调用方必须先重读", "STALE_VERSION",
                ToolCatalog.preflightRejectionCode(false, false, 11, 21, false));
        assertEquals("版本过期 + 动作在飞，仍然是版本号的错", "STALE_VERSION",
                ToolCatalog.preflightRejectionCode(false, false, 11, 21, true));
        assertEquals("未来的版本号同样先重读", "STALE_VERSION",
                ToolCatalog.preflightRejectionCode(false, false, 999999, 21, false));
    }

    @Test
    public void inFlightOnlyMattersWhenTheVersionMatches() {
        assertEquals("ACTION_IN_FLIGHT",
                ToolCatalog.preflightRejectionCode(false, false, 21, 21, true));
        assertNull("版本对、也没在飞 → 交给执行器（它才可能回 ILLEGAL_ACTION）",
                ToolCatalog.preflightRejectionCode(false, false, 21, 21, false));
    }

    /** 优先级必须在 act 里真的按这个顺序落地（不是只写在文档里）。 */
    @Test
    public void theActPreflightFollowsTheTable() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        int act = code.indexOf("private ToolResult act(JsonObject arguments)");
        assertTrue(act > 0);
        String body = code.substring(act, Math.min(code.length(), act + 9000));
        int decide = body.indexOf("preflightRejectionCode(");
        int loading = body.indexOf("\"NOT_READY\".equals(refusal)");
        int stale = body.indexOf("\"STALE_VERSION\".equals(refusal)");
        int inFlight = body.indexOf("\"ACTION_IN_FLIGHT\".equals(refusal)");
        assertTrue("要用同一个判据函数", decide > 0);
        assertTrue(loading > decide);
        assertTrue("载入分支要为它自己的文案负责", body.contains("loadingMessage(\"act\")"));
        assertTrue(stale > loading);
        assertTrue(inFlight > stale);
        assertTrue("执行器只能在四道预检之后才被叫到",
                body.indexOf("ActionExecutor.reach(") > inFlight);
    }
}
