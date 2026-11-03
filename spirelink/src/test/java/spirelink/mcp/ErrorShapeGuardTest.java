package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import spirelink.state.StateBuilder;
import spirelink.util.ModLog;
import spirelink.util.TestMode;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #86 - the error shape guard: every tool failure must answer the same object.
 *
 * <p>Before this, the failure contract depended on the tool: {@code act} (and
 * {@code get_state}/{@code legal_actions}) returned {@code "error":{"code":…,"message":…}}, while
 * {@code note_set} / {@code glossary} / {@code card_info} / {@code event_note} / every {@code dev_*}
 * returned a bare string code (plus a top-level {@code message} when they bothered). A client had to
 * branch on {@code typeof error} before it could read the code at all - the QA harness hit exactly
 * that on six different tools.
 *
 * <p>Every sample below fails <em>before</em> anything is queued on the game thread, so this test
 * runs with no game: act rejects the wrong {@code state_version}, note/card tools reject arguments,
 * glossary misses a term, event_note is not in an event room, and with {@code test_mode} off every
 * {@code dev_*} tool answers {@code DEV_TOOL_DISABLED}.
 */
public class ErrorShapeGuardTest {

    /** Tools whose only failure path needs the live game; their shape is the object from error(). */
    private static final List<String> NOT_SAMPLED = new ArrayList<String>();

    static {
        NOT_SAMPLED.add(ToolCatalog.TOOL_GET_STATE);
        NOT_SAMPLED.add(ToolCatalog.TOOL_LEGAL_ACTIONS);
        NOT_SAMPLED.add(ToolCatalog.TOOL_NOTES_LIST);
    }

    private ToolCatalog catalog;

    @Before
    public void setUp() {
        TestMode.setEnabled(false);
        ModLog log = new ModLog(new File("target/error-shape-test"), false);
        catalog = new ToolCatalog(new MainThreadQueue(), new StateBuilder(log, "test"), log);
    }

    @After
    public void tearDown() {
        // This test really calls act(), which touches process-wide state (see #84's report).
        spirelink.state.StateBuilder.noteAcceptance();
        spirelink.ui.AgentHud.clear();
        TestMode.setEnabled(false);
    }

    /** 全部失败样例 → 期望的错误码。 */
    private static Map<String, String> samples() {
        Map<String, String> samples = new LinkedHashMap<String, String>();
        samples.put(ToolCatalog.TOOL_ACT, "{\"type\":\"end_turn\",\"state_version\":\"30\"}");
        samples.put(ToolCatalog.TOOL_NOTE_SET, "{\"kind\":\"general\",\"id\":\"qa-86\"}");
        samples.put(ToolCatalog.TOOL_NOTE_GET, "{\"kind\":\"bogus\",\"id\":\"x\"}");
        samples.put(ToolCatalog.TOOL_CARD_INFO, "{}");
        samples.put(ToolCatalog.TOOL_GLOSSARY, "{\"term\":\"不存在的术语xyz\"}");
        samples.put(ToolCatalog.TOOL_EVENT_NOTE, "{\"text\":\"x\"}");
        for (String name : new String[]{ToolCatalog.TOOL_DEV_EVENT, ToolCatalog.TOOL_DEV_MAP,
                ToolCatalog.TOOL_DEV_ROOM, ToolCatalog.TOOL_DEV_ENCOUNTER, ToolCatalog.TOOL_DEV_POTION,
                ToolCatalog.TOOL_DEV_CARD, ToolCatalog.TOOL_DEV_RELIC, ToolCatalog.TOOL_DEV_REWARD,
                ToolCatalog.TOOL_DEV_HEAL, ToolCatalog.TOOL_DEV_USE_CARD}) {
            samples.put(name, "{}");
        }
        return samples;
    }

    private ToolCatalog.ToolResult call(String tool, String arguments) {
        JsonObject args = new JsonParser().parse(arguments).getAsJsonObject();
        ToolCatalog.ToolResult result = catalog.call(tool, args);
        assertNotNull("工具没被认出来: " + tool, result);
        return result;
    }

    // ------------------------------------------------------------------ 形状守卫

    /** 核心断言：每个工具的失败回包，error 都是 {code:String, message:String}。 */
    @Test
    public void everyToolFailureUsesTheSameErrorShape() {
        Map<String, String> samples = samples();
        List<String> problems = new ArrayList<String>();
        for (Map.Entry<String, String> sample : samples.entrySet()) {
            ToolCatalog.ToolResult result = call(sample.getKey(), sample.getValue());
            JsonObject payload = result.payload;
            assertTrue("样例必须真的失败: " + sample.getKey() + " → " + payload, result.isError);
            assertFalse("失败回包的 ok 必须是 false: " + sample.getKey(), payload.get("ok").getAsBoolean());

            JsonElement error = payload.get("error");
            if (error == null || !error.isJsonObject()) {
                problems.add(sample.getKey() + " 的 error 不是对象: " + error);
                continue;
            }
            JsonObject object = error.getAsJsonObject();
            if (!object.has("code") || !object.get("code").isJsonPrimitive()
                    || !object.get("code").getAsJsonPrimitive().isString()) {
                problems.add(sample.getKey() + " 的 error.code 不是字符串: " + object);
                continue;
            }
            String code = object.get("code").getAsString();
            if (!ToolCatalog.isErrorCode(code)) {
                problems.add(sample.getKey() + " 的 code 不像机器码: " + code);
            }
            if (!object.has("message") || !object.get("message").isJsonPrimitive()
                    || object.get("message").getAsString().trim().isEmpty()) {
                problems.add(sample.getKey() + " 缺 error.message: " + object);
            }
            if (payload.has("message")) {
                problems.add(sample.getKey() + " 还留着顶层 message（必须收进 error 里）");
            }
        }
        assertTrue("错误形状不一致：" + problems, problems.isEmpty());
    }

    /** 具体码别退回去：这几个是 QA 实测过的老形态。 */
    @Test
    public void failuresKeepTheirMachineCodes() {
        assertEquals("BAD_REQUEST", codeOf(call(ToolCatalog.TOOL_ACT,
                "{\"type\":\"end_turn\",\"state_version\":\"30\"}")));
        assertEquals("BAD_REQUEST", codeOf(call(ToolCatalog.TOOL_NOTE_SET,
                "{\"kind\":\"general\",\"id\":\"qa-86\"}")));
        assertEquals("BAD_REQUEST", codeOf(call(ToolCatalog.TOOL_CARD_INFO, "{}")));
        assertEquals("TERM_NOT_FOUND", codeOf(call(ToolCatalog.TOOL_GLOSSARY,
                "{\"term\":\"不存在的术语xyz\"}")));
        assertEquals("NOT_READY", codeOf(call(ToolCatalog.TOOL_EVENT_NOTE, "{\"text\":\"x\"}")));
        assertEquals("DEV_TOOL_DISABLED", codeOf(call(ToolCatalog.TOOL_DEV_REWARD, "{}")));
        assertEquals("DEV_TOOL_DISABLED", codeOf(call(ToolCatalog.TOOL_DEV_CARD, "{}")));
    }

    /** 覆盖率守卫：新工具必须进样例表（或明确列进 NOT_SAMPLED），否则本测试变红。 */
    @Test
    public void everyAdvertisedToolIsCovered() {
        TestMode.setEnabled(true);
        JsonArray tools;
        try {
            tools = new ToolCatalog(null, null, null).listTools();
        } finally {
            TestMode.setEnabled(false);
        }
        Map<String, String> samples = samples();
        List<String> uncovered = new ArrayList<String>();
        for (int i = 0; i < tools.size(); i++) {
            String name = tools.get(i).getAsJsonObject().get("name").getAsString();
            if (!samples.containsKey(name) && !NOT_SAMPLED.contains(name)) {
                uncovered.add(name);
            }
        }
        assertTrue("这些工具的失败形状没有被覆盖（新增工具请补样例）: " + uncovered,
                uncovered.isEmpty());
        assertEquals("样例 + 豁免 = 全部 19 个工具", 19, samples.size() + NOT_SAMPLED.size());
    }

    // ------------------------------------------------------------------ 归一化规则

    /** 纯文本 error（例如把异常对象直接塞进 error）→ INTERNAL + 原文进 message。 */
    @Test
    public void freeTextErrorBecomesInternalWithTheTextAsMessage() {
        JsonObject payload = new JsonParser().parse(
                "{\"ok\":false,\"error\":\"java.io.IOException: boom\"}").getAsJsonObject();
        ToolCatalog.normalizeError(payload);

        JsonObject error = payload.getAsJsonObject("error");
        assertEquals("INTERNAL", error.get("code").getAsString());
        assertTrue(error.get("message").getAsString().contains("java.io.IOException: boom"));
    }

    /** 已经是对象的（act 形态）原样不动，连 state_version 这种额外键也不动。 */
    @Test
    public void objectErrorIsLeftUntouched() {
        JsonObject payload = new JsonParser().parse("{\"ok\":false,\"error\":{\"code\":\"STALE_VERSION\","
                + "\"message\":\"版本过期\",\"state_version\":42}}").getAsJsonObject();
        ToolCatalog.normalizeError(payload);

        JsonObject error = payload.getAsJsonObject("error");
        assertEquals("STALE_VERSION", error.get("code").getAsString());
        assertEquals("版本过期", error.get("message").getAsString());
        assertEquals(42, error.get("state_version").getAsInt());
    }

    /** 没有 message 的码要补一条默认文案（客户端至少能打印点东西）。 */
    @Test
    public void missingMessageGetsTheDefaultForThatCode() {
        JsonObject payload = new JsonParser().parse(
                "{\"ok\":false,\"error\":\"CARD_NOT_FOUND\"}").getAsJsonObject();
        ToolCatalog.normalizeError(payload);

        JsonObject error = payload.getAsJsonObject("error");
        assertEquals("CARD_NOT_FOUND", error.get("code").getAsString());
        assertFalse(error.get("message").getAsString().trim().isEmpty());
        assertEquals(ToolCatalog.defaultErrorMessage("CARD_NOT_FOUND"),
                error.get("message").getAsString());
    }

    /** 顶层 message 折叠进 error 后必须删掉，回包里只有一处 message。 */
    @Test
    public void topLevelMessageIsFoldedIn() {
        JsonObject payload = new JsonParser().parse(
                "{\"ok\":false,\"error\":\"BAD_REQUEST\",\"message\":\"要删除请显式传空串\"}")
                .getAsJsonObject();
        ToolCatalog.normalizeError(payload);

        JsonObject error = payload.getAsJsonObject("error");
        assertEquals("BAD_REQUEST", error.get("code").getAsString());
        assertTrue(error.get("message").getAsString().contains("要删除请显式传"));
        assertFalse(payload.has("message"));
    }

    /** 成功回包 / error:null / 完全没有 error 的回包都不动。 */
    @Test
    public void nonFailuresAreNotTouched() {
        JsonObject ok = new JsonParser().parse("{\"ok\":true,\"text\":\"好\"}").getAsJsonObject();
        ToolCatalog.normalizeError(ok);
        assertFalse(ok.has("error"));

        JsonObject nullError = new JsonParser().parse("{\"ok\":true,\"error\":null}").getAsJsonObject();
        ToolCatalog.normalizeError(nullError);
        assertTrue(nullError.get("error").isJsonNull());

        assertNull(ToolCatalog.normalizeError(null));
    }

    /** 机器码判定：只认 UPPER_SNAKE。 */
    @Test
    public void errorCodePatternIsNarrow() {
        for (String code : new String[]{"BAD_REQUEST", "NOT_READY", "CARD_NOT_HAND"}) {
            assertTrue(code, ToolCatalog.isErrorCode(code));
        }
        for (String code : new String[]{"bad_request", "BadRequest", "", "有中文的码",
                "java.io.IOException", "with space"}) {
            assertFalse(code, ToolCatalog.isErrorCode(code));
        }
        assertFalse(ToolCatalog.isErrorCode(null));
    }

    private static String codeOf(ToolCatalog.ToolResult result) {
        JsonObject error = result.payload.getAsJsonObject("error");
        assertNotNull("error 必须是对象: " + result.payload, error);
        return error.get("code").getAsString();
    }
}
