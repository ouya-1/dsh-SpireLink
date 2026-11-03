package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issues #62 / #72: {@code act} must refuse wrongly typed arguments instead of coercing them.
 *
 * <p>The old path went through Gson's {@code getAsInt()}, so {@code state_version:"30"} parsed to 30
 * and the action <b>really executed</b> (issue #62), while an array payload was silently treated as
 * "no payload" and a null payload as an empty one (issue #72). Both answered {@code ok:true}, which
 * is the worst possible answer: the client believed it had been understood.
 *
 * <p>Every assertion here is on the pure argument contract
 * ({@link ToolCatalog#actArgumentError(JsonObject)} / {@link ToolCatalog#strictIntArg}), so no game
 * object is created and no action is executed.
 */
public class ActArgumentValidationTest {

    private static JsonObject args(String json) {
        return new JsonParser().parse(json).getAsJsonObject();
    }

    // ------------------------------------------------------------------ state_version

    /** 正确类型照常通过：整数 state_version + 对象 payload。 */
    @Test
    public void wellTypedArgumentsPass() {
        JsonObject ok = args("{\"type\":\"end_turn\",\"state_version\":42,"
                + "\"payload\":{\"x\":0},\"action_id\":\"qa-1\"}");
        assertNull(ToolCatalog.actArgumentError(ok));
        assertEquals(Integer.valueOf(42), ToolCatalog.strictIntArg(ok, "state_version"));

        // payload 可缺省（不是错），type 与 state_version 都在
        assertNull(ToolCatalog.actArgumentError(args("{\"type\":\"end_turn\",\"state_version\":0}")));
    }

    /** 类型矩阵：字符串 / 浮点 / null / 布尔 / 数组 / 对象 / 缺失 一律 BAD_REQUEST。 */
    @Test
    public void stateVersionTypeMatrixIsRejected() {
        String[] bad = new String[]{"\"30\"", "\"abc\"", "30.5", "30.0", "3e2", "null",
                "true", "[]", "{}"};
        for (String raw : bad) {
            JsonObject a = args("{\"type\":\"end_turn\",\"state_version\":" + raw + "}");
            String message = ToolCatalog.actArgumentError(a);
            assertNotNull("state_version=" + raw + " 必须被拒", message);
            assertTrue("文案要点名 state_version 与整数: " + message,
                    message.contains("state_version") && message.contains("整数"));
            assertNull("state_version=" + raw + " 不许被宽松解析成整数",
                    ToolCatalog.strictIntArg(a, "state_version"));
        }
        // 缺失：仍然要 state_version（既有契约），但是另一条文案
        String missing = ToolCatalog.actArgumentError(args("{\"type\":\"end_turn\"}"));
        assertNull("缺 state_version 交给 act() 的必填检查", missing);
    }

    /** 整数矩阵：0 / 正 / 负 / 大整数都通过，且不会被截断或改写。 */
    @Test
    public void integerLiteralsPass() {
        for (String raw : new String[]{"0", "42", "-999", "2147483647"}) {
            JsonObject a = args("{\"type\":\"end_turn\",\"state_version\":" + raw + "}");
            assertNull("state_version=" + raw + " 是整数，应通过: "
                    + ToolCatalog.actArgumentError(a), ToolCatalog.actArgumentError(a));
            assertEquals(Integer.valueOf(Integer.parseInt(raw)),
                    ToolCatalog.strictIntArg(a, "state_version"));
        }
    }

    /** {@code "30"} 这种字符串即使内容是数字也不许当整数用（issue #62 的原始复现）。 */
    @Test
    public void numericStringIsNotAnInteger() {
        JsonObject a = args("{\"type\":\"choose_map_node\",\"state_version\":\"42\"}");
        assertFalse(ToolCatalog.isIntegerLiteral(a.get("state_version")));
        assertNotNull(ToolCatalog.actArgumentError(a));
    }

    // ------------------------------------------------------------------ payload

    /** payload 类型矩阵：对象通过；数组 / 字符串 / 数字 / null 一律拒；缺省也算通过。 */
    @Test
    public void payloadTypeMatrix() {
        assertNull("payload={} 是合法空参",
                ToolCatalog.actArgumentError(args("{\"type\":\"end_turn\",\"state_version\":1,"
                        + "\"payload\":{}}")));
        assertNull("payload 缺省 = 没有载荷，合法",
                ToolCatalog.actArgumentError(args("{\"type\":\"end_turn\",\"state_version\":1}")));

        String[] bad = new String[]{"[]", "[1,2]", "\"x\"", "123", "true", "null"};
        for (String raw : bad) {
            String message = ToolCatalog.actArgumentError(args(
                    "{\"type\":\"end_turn\",\"state_version\":1,\"payload\":" + raw + "}"));
            assertNotNull("payload=" + raw + " 必须被拒", message);
            assertTrue("文案要点名 payload 必须是对象: " + message,
                    message.contains("payload") && message.contains("对象"));
        }
    }

    // ------------------------------------------------------------------ type / action_id

    /** type 必须是字符串：数字/布尔/数组都不是动作类型。 */
    @Test
    public void typeMustBeAString() {
        for (String raw : new String[]{"123", "true", "[]", "{}"}) {
            String message = ToolCatalog.actArgumentError(args(
                    "{\"type\":" + raw + ",\"state_version\":1}"));
            assertNotNull("type=" + raw + " 必须被拒", message);
            assertTrue(message.contains("type") && message.contains("字符串"));
        }
        assertNull(ToolCatalog.actArgumentError(args("{\"type\":\"end_turn\",\"state_version\":1}")));
    }

    /** action_id 只用于幂等，必须是字符串；给对象直接拒。 */
    @Test
    public void actionIdMustBeAString() {
        String message = ToolCatalog.actArgumentError(args(
                "{\"type\":\"end_turn\",\"state_version\":1,\"action_id\":{}}"));
        assertNotNull(message);
        assertTrue(message.contains("action_id"));
    }

    /** 完全没给 arguments：也要有话说，不能 NPE。 */
    @Test
    public void nullArgumentsStillExplainTheContract() {
        String message = ToolCatalog.actArgumentError(null);
        assertNotNull(message);
        assertTrue(message.contains("type") && message.contains("state_version"));
    }
}
