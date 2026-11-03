package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #119 · C10-05，服务端那一半：{@code hints.last_error_fresh} 由
 * {@link ToolCatalog#mergeDiagnostics} 在回包那一刻重算，所以新规则必须在这里也生效，否则
 * {@code get_state} 现算出来的 fresh 又会把 state 层的判断盖掉。
 *
 * <p>三条规则（见 {@code state/ErrorFreshness}）：序号相等 + 是动作的错误（不是读状态失败）+
 * 从此世界没再动过。QA 现场是「act 回 queued → 轮询 get_state 十几秒 fresh 一直 true」：轮询期间记的
 * 是 {@code get_state → NOT_READY}，读工具不推进 {@code act_seq}，所以旧规则永远判 true。</p>
 */
public class HintsFreshnessTest {

    private static JsonObject json(String raw) {
        return new JsonParser().parse(raw).getAsJsonObject();
    }

    private static JsonObject merge(long actSeq, long errorSeq, String lastError,
            long errorAt, long now, long worldMovedAt) {
        JsonObject payload = json("{\"hints\":{\"frames\":7}}");
        ToolCatalog.mergeDiagnostics(payload, actSeq, errorSeq, lastError, errorAt, now, worldMovedAt);
        return payload.getAsJsonObject("hints");
    }

    @Test
    public void aReadRefusalWhilePollingIsNotFresh() {
        JsonObject hints = merge(7L, 7L, "get_state → NOT_READY", 1_000L, 1_100L, 0L);
        assertEquals("错误原文照旧保留，它是历史", "get_state → NOT_READY",
                hints.get("last_error").getAsString());
        assertEquals("age 照旧现算", 100L, hints.get("last_error_age_ms").getAsLong());
        assertFalse("读状态失败不是「刚才那次 act 的错」（C10-05）",
                hints.get("last_error_fresh").getAsBoolean());
    }

    @Test
    public void anActRejectionIsFreshUntilTheWorldMoves() {
        JsonObject fresh = merge(7L, 7L, "proceed → ILLEGAL_ACTION", 1_000L, 1_100L, 900L);
        assertTrue("真正的动作拒绝，且此后世界没动", fresh.get("last_error_fresh").getAsBoolean());

        // 排队的那条动作落地了：state_version 往前走，旧错误变成历史。
        JsonObject stale = merge(7L, 7L, "proceed → ILLEGAL_ACTION", 1_000L, 1_100L, 2_000L);
        assertFalse("动作落地后旧错误必须不再 fresh", stale.get("last_error_fresh").getAsBoolean());
    }

    @Test
    public void anErrorFromAnOlderActStaysHistory() {
        JsonObject hints = merge(9L, 7L, "proceed → ILLEGAL_ACTION", 1_000L, 1_100L, 0L);
        assertFalse(hints.get("last_error_fresh").getAsBoolean());
    }

    @Test
    public void legacyFieldsAreUntouched() {
        JsonObject hints = merge(3L, 3L, "act → BAD_REQUEST", 1_000L, 1_270L, 0L);
        assertEquals(3L, hints.get("act_seq").getAsLong());
        assertEquals(3L, hints.get("last_error_seq").getAsLong());
        assertEquals(270L, hints.get("last_error_age_ms").getAsLong());
        assertEquals("其它 hints 字段不能被这次合并冲掉", 7L, hints.get("frames").getAsLong());
    }
}
