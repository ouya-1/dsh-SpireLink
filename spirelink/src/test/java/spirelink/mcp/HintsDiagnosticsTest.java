package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import spirelink.util.CrashGuard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issue #88: {@code hints} 的「上一次动作怎么了」这组字段必须现读现算。
 *
 * <p>现场（QA 黑盒）：任何一次 act 被拒之后再 get_state，{@code hints.last_error} 一直停在最早那次
 * 拒绝上（例如 {@code act → BAD_REQUEST}），{@code act_seq} / {@code last_error_seq} /
 * {@code last_error_age_ms} 也全都不动，age 冻在 270ms；按文档故意发 {@code state_version-1}
 * 拿到 {@code STALE_VERSION} 之后，hints 里仍是老的 {@code BAD_REQUEST}。
 *
 * <p>根因：#84 只把 liveness 数字（frames/beats/ms_since_*）改成现读现算，这组诊断字段仍然来自
 * 缓存 body —— 它们是 {@code StateBuilder.fillProbeHints} 在「建 body 那一拍」写进去的，body 不重建
 * 就一直发老值。真正的状态在 {@link spirelink.util.CrashGuard} 里，永远是最新的，所以在这里按
 * 最后一次动作结果重算（{@link ToolCatalog#refreshDiagnostics(JsonObject)}）。
 *
 * <p>断言尽量打在纯函数上（{@link ToolCatalog#mergeDiagnostics}），时间与拒绝次数都由调用方注入；
 * 另有一条走真实 {@link CrashGuard} 的用例，钉住「第二个拒绝必须盖掉第一个」。
 */
public class HintsDiagnosticsTest {

    private static JsonObject json(String raw) {
        return new JsonParser().parse(raw).getAsJsonObject();
    }

    /** QA 现场那份缓存 hints：第一次拒绝留下的四件套（age 冻在 270ms）。 */
    private static JsonObject cachedHintsAfterFirstRefusal() {
        return json("{\"ok\":true,\"ready\":true,\"state_version\":42,"
                + "\"hints\":{\"last_error\":\"act → BAD_REQUEST\",\"act_seq\":3,"
                + "\"last_error_seq\":3,\"last_error_age_ms\":270,"
                + "\"last_error_fresh\":true,\"frames\":15171}}");
    }

    // ------------------------------------------------------------ 连续两次不同拒绝 → 反映第二次

    /** 第二个拒绝必须盖掉第一个：真实 CrashGuard 走一遍（BAD_REQUEST → STALE_VERSION）。 */
    @Test
    public void secondRefusalReplacesTheFirst() {
        JsonObject payload = cachedHintsAfterFirstRefusal();

        CrashGuard.nextActionSeq();
        CrashGuard.noteActionError("BAD_REQUEST", "act");
        ToolCatalog.refreshDiagnostics(payload);
        JsonObject hints = payload.getAsJsonObject("hints");
        assertEquals("第一次拒绝要如实出现", "act → BAD_REQUEST", hints.get("last_error").getAsString());

        // QA 的操作：再发一次 act，这次是版本过期。
        CrashGuard.nextActionSeq();
        CrashGuard.noteActionError("STALE_VERSION", "act");
        ToolCatalog.refreshDiagnostics(payload);

        assertEquals("第二个拒绝必须盖掉第一个（旧行为：永远停在 BAD_REQUEST）",
                "act → STALE_VERSION", hints.get("last_error").getAsString());
        assertEquals("act_seq 必须是此刻的计数", CrashGuard.actionSeq(),
                hints.get("act_seq").getAsLong());
        assertEquals("last_error_seq 必须指向最新那次拒绝", CrashGuard.lastErrorSeq(),
                hints.get("last_error_seq").getAsLong());
        assertTrue("最新这次拒绝就是最后一个 act，所以是 fresh",
                hints.get("last_error_fresh").getAsBoolean());
        assertNotEquals("age 不许再冻在 270ms", 270L,
                hints.get("last_error_age_ms").getAsLong());
        assertTrue("刚发生的错误 age 应该是毫秒级", hints.get("last_error_age_ms").getAsLong() < 5000L);
    }

    /** 成功的一次 act 让「fresh」变成 false，但 last_error 仍保留为历史（同一份缓存 body）。 */
    @Test
    public void successfulActMakesTheOldErrorHistory() {
        JsonObject payload = json("{\"hints\":{\"last_error\":\"act → BAD_REQUEST\","
                + "\"act_seq\":3,\"last_error_seq\":3,\"last_error_age_ms\":12}}");

        ToolCatalog.mergeDiagnostics(payload, 9L, 3L, "act → BAD_REQUEST", 1_000L, 5_000L, 0L);

        JsonObject hints = payload.getAsJsonObject("hints");
        assertEquals(9L, hints.get("act_seq").getAsLong());
        assertEquals(3L, hints.get("last_error_seq").getAsLong());
        assertEquals("act → BAD_REQUEST", hints.get("last_error").getAsString());
        assertFalse("后面的 act 成功了，这条错误只是历史，不再 fresh",
                hints.get("last_error_fresh").getAsBoolean());
        assertEquals(4_000L, hints.get("last_error_age_ms").getAsLong());
    }

    // ------------------------------------------------------------------ age 随时间变化

    /** 同一份缓存 hints，时间往前走 age 必须跟着走（旧行为是冻住的 270ms）。 */
    @Test
    public void ageFollowsTheClockNotTheCache() {
        JsonObject payload = json("{\"hints\":{\"last_error\":\"act → BAD_REQUEST\","
                + "\"last_error_age_ms\":270}}");

        ToolCatalog.mergeDiagnostics(payload, 4L, 4L, "act → BAD_REQUEST", 1_000L, 1_270L, 0L);
        assertEquals("第一次读到的年龄", 270L,
                payload.getAsJsonObject("hints").get("last_error_age_ms").getAsLong());

        ToolCatalog.mergeDiagnostics(payload, 4L, 4L, "act → BAD_REQUEST", 1_000L, 6_270L, 0L);
        assertEquals("同样的错误，5 秒后 age 必须是 5270ms，不许还是 270",
                5_270L, payload.getAsJsonObject("hints").get("last_error_age_ms").getAsLong());
    }

    /** 真实时钟同理：两次 serve 之间 age 只增不减（不依赖注入的 now）。 */
    @Test
    public void ageGrowsBetweenTwoServes() throws Exception {
        JsonObject payload = json("{\"hints\":{\"last_error\":\"act → BAD_REQUEST\"}}");
        CrashGuard.nextActionSeq();
        CrashGuard.noteActionError("BAD_REQUEST", "act");

        ToolCatalog.refreshDiagnostics(payload);
        long first = payload.getAsJsonObject("hints").get("last_error_age_ms").getAsLong();
        Thread.sleep(60L);
        ToolCatalog.refreshDiagnostics(payload);
        long second = payload.getAsJsonObject("hints").get("last_error_age_ms").getAsLong();

        assertTrue("两次 serve 之间 age 必须变大: " + first + " -> " + second, second > first);
    }

    /** 时钟往回跳（NTP 校时）不许把 age 变成负数。 */
    @Test
    public void backwardsClockIsClampedToZero() {
        JsonObject payload = json("{\"hints\":{\"last_error\":\"act → BAD_REQUEST\","
                + "\"last_error_age_ms\":270}}");
        ToolCatalog.mergeDiagnostics(payload, 3L, 3L, "act → BAD_REQUEST", 9_000L, 1_000L, 0L);
        assertEquals(0L, payload.getAsJsonObject("hints").get("last_error_age_ms").getAsLong());
    }

    // ------------------------------------------------------------ get_state / legal_actions 同拍一致

    /**
     * 两个工具各拿自己那份缓存 body（两份旧值还不一样），同一拍 refresh 之后必须字字相同 ——
     * agent 不该因为「先问哪个工具」而看到不同的拒绝原因。
     */
    @Test
    public void getStateAndLegalActionsAgreeOnTheSameTick() {
        JsonObject getState = cachedHintsAfterFirstRefusal();
        JsonObject legalActions = json("{\"ready\":true,\"state_version\":42,"
                + "\"hints\":{\"last_error\":\"act → NOT_READY\",\"act_seq\":1,"
                + "\"last_error_seq\":1,\"last_error_age_ms\":999999}}");

        CrashGuard.nextActionSeq();
        CrashGuard.noteActionError("STALE_VERSION", "act");
        // 同一拍：两个工具各自 serve 时都从同一个真源重算（时钟参数相同 = 同一拍）。
        long now = System.currentTimeMillis();
        long actSeq = CrashGuard.actionSeq();
        long errorSeq = CrashGuard.lastErrorSeq();
        String lastError = CrashGuard.lastError();
        long errorAt = CrashGuard.lastErrorAt();
        long worldMovedAt = spirelink.state.StateBuilder.lastDecisionChangeAt();
        ToolCatalog.mergeDiagnostics(getState, actSeq, errorSeq, lastError, errorAt, now, worldMovedAt);
        ToolCatalog.mergeDiagnostics(legalActions, actSeq, errorSeq, lastError, errorAt, now,
                worldMovedAt);

        for (String key : new String[]{"act_seq", "last_error_seq", "last_error",
                "last_error_age_ms", "last_error_fresh"}) {
            assertEquals("两工具同拍的 " + key + " 必须一致",
                    getState.getAsJsonObject("hints").get(key),
                    legalActions.getAsJsonObject("hints").get(key));
        }
        assertEquals("act → STALE_VERSION",
                getState.getAsJsonObject("hints").get("last_error").getAsString());
    }

    // ------------------------------------------------------------ 不许凭空造 / 不许动别的字段

    /** #66/#68：没有 hints 就不许被装饰器造出来（hints 是「有事要解释」的信号）。 */
    @Test
    public void hintsAreNeverInvented() {
        JsonObject payload = json("{\"ok\":true,\"ready\":true,\"state_version\":42}");
        CrashGuard.nextActionSeq();
        CrashGuard.noteActionError("BAD_REQUEST", "act");
        ToolCatalog.refreshDiagnostics(payload);
        assertFalse("ready 回包不许长出 hints: " + payload, payload.has("hints"));
    }

    /** hints 显式为 null 时也不许被替换成对象。 */
    @Test
    public void nullHintsIsLeftAlone() {
        JsonObject payload = json("{\"ok\":false,\"hints\":null}");
        ToolCatalog.refreshDiagnostics(payload);
        assertTrue("null hints 不许被替换", payload.get("hints").isJsonNull());
    }

    /** payload 传 null 不炸（防御式）。 */
    @Test
    public void nullPayloadIsIgnored() {
        ToolCatalog.refreshDiagnostics(null);
        ToolCatalog.mergeDiagnostics(null, 1L, 1L, "x", 0L, 0L, 0L);
        // 走到这里没抛异常就是通过：装饰器不许把一次状态读取炸掉。
    }

    /** 只改这四个诊断字段，hints 里的其它内容（帧数、选区提示等）原样不动。 */
    @Test
    public void otherHintFieldsSurvive() {
        JsonObject payload = json("{\"hints\":{\"frames\":15171,\"beats\":22,"
                + "\"needs_inspect\":true,\"grid_selected\":1}}");
        ToolCatalog.mergeDiagnostics(payload, 2L, 2L, "act → BAD_REQUEST", 1_000L, 1_100L, 0L);
        JsonObject hints = payload.getAsJsonObject("hints");
        assertEquals(15171L, hints.get("frames").getAsLong());
        assertEquals(22L, hints.get("beats").getAsLong());
        assertTrue(hints.get("needs_inspect").getAsBoolean());
        assertEquals(1, hints.get("grid_selected").getAsInt());
        assertEquals(100L, hints.get("last_error_age_ms").getAsLong());
    }

    /** 进程里从没失败过：缓存 body 里残留的 last_error 属于「不实的旧账」，要清掉。 */
    @Test
    public void staleErrorIsDroppedWhenNothingEverFailed() {
        JsonObject payload = json("{\"hints\":{\"last_error\":\"act → BAD_REQUEST\","
                + "\"last_error_age_ms\":270,\"last_error_fresh\":true,\"frames\":7}}");
        ToolCatalog.mergeDiagnostics(payload, 0L, 0L, null, 0L, 1_000L, 0L);

        JsonObject hints = payload.getAsJsonObject("hints");
        assertFalse(hints.has("last_error"));
        assertFalse(hints.has("last_error_age_ms"));
        assertFalse(hints.has("last_error_fresh"));
        assertEquals("计数仍然是事实", 0L, hints.get("act_seq").getAsLong());
        assertEquals(7L, hints.get("frames").getAsLong());
    }
}
