package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #122 · C13-04：{@code act} 的 payload 摊平写（不带 {@code payload} 包装）时，只得到
 * 「payload.uuid 必填」这类**字段级**错误，从不提示「参数要放进 payload 对象」。
 *
 * <p>判据是纯的：{@code act} 的顶层只认 {@code type / state_version / payload / action_id}，其它键都是
 * 动作参数。因此「有陌生顶层键、又没有 payload」几乎必然是摊平写 —— 直接 {@code BAD_REQUEST} 并给出
 * 正确形状，比让执行器去抱怨一个调用方压根没创建过的字段好。</p>
 */
public class ActArgumentShapeTest {

    private static JsonObject json(String raw) {
        return new JsonParser().parse(raw).getAsJsonObject();
    }

    @Test
    public void flattenedPayloadIsNamedAsSuch() {
        String error = ToolCatalog.actArgumentError(json("{\"type\":\"play_card\","
                + "\"state_version\":7,\"uuid\":\"abc\",\"index\":0}"));
        assertNotNull("摊平写要被拦在参数校验，而不是交给执行器", error);
        assertTrue("要点名哪些键是动作参数: " + error, error.contains("uuid") && error.contains("index"));
        assertTrue("要说清顶层只认四个字段: " + error, error.contains("payload"));
        assertTrue("要给出正确形状: " + error, error.contains("\"payload\""));
        assertTrue("要说明不能摊平: " + error, error.contains("摊平"));
    }

    @Test
    public void aWrappedPayloadPasses() {
        assertNull(ToolCatalog.actArgumentError(json("{\"type\":\"play_card\",\"state_version\":7,"
                + "\"payload\":{\"uuid\":\"abc\",\"index\":0}}")));
    }

    @Test
    public void actionsWithoutPayloadStillPass() {
        assertNull("end_turn 这类不要载荷的动作不许被这条规则误伤",
                ToolCatalog.actArgumentError(json("{\"type\":\"end_turn\",\"state_version\":7}")));
        assertNull(ToolCatalog.actArgumentError(json("{\"type\":\"abandon_run\","
                + "\"state_version\":7,\"action_id\":\"c0ffee\"}")));
    }

    @Test
    public void theStrayKeyScanIgnoresActsOwnArguments() {
        List<String> stray = ToolCatalog.strayActArguments(json("{\"type\":\"play_card\","
                + "\"state_version\":7,\"payload\":{},\"action_id\":\"x\",\"uuid\":\"abc\"}"));
        assertEquals("只有陌生键算摊平, 实际 " + stray, 1, stray.size());
        assertEquals("uuid", stray.get(0));
        assertTrue(ToolCatalog.strayActArguments(json("{}")).isEmpty());
        assertTrue(ToolCatalog.strayActArguments(null).isEmpty());
    }

    @Test
    public void theMessageSpellsOutTheShape() {
        String message = ToolCatalog.flattenedPayloadMessage(
                java.util.Arrays.asList("index", "uuid"));
        assertTrue(message, message.contains("index") && message.contains("uuid"));
        assertTrue("要给出可直接抄的模板: " + message,
                message.contains("\"state_version\"") && message.contains("legal_actions"));
    }

    /** 真问题在 act 里：参数校验必须早于执行器（否则错误又变成执行器的字段级抱怨）。 */
    @Test
    public void theCheckRunsBeforeAnythingIsExecuted() throws java.io.IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        int act = code.indexOf("private ToolResult act(JsonObject arguments)");
        String body = code.substring(act, Math.min(code.length(), act + 5000));
        int check = body.indexOf("actArgumentError(arguments)");
        int reach = body.indexOf("ActionExecutor.reach(");
        assertTrue(check > 0);
        assertTrue("参数校验必须早于执行器", reach > check);
    }
}
