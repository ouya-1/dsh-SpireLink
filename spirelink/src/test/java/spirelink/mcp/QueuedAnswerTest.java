package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #119 · C10-02: an {@code act} that answers {@code queued} reported {@code ui:"other"} while
 * {@code get_state} — served from the same cached snapshot — reported {@code treasure}.
 *
 * <p>同一个世界，一次调用不能说两个屏幕。排队回包里的 {@code ui} 现在取自当前快照的
 * {@code meta.ui}（{@code StateBuilder#cachedView()}，请求线程安全，不碰游戏对象）；没有快照时
 * 才保留老的 {@code "other"}。动作列表仍然是空的 —— 排队期间本来就不许再发动作（墙钟护栏）。</p>
 *
 * <p>{@link ToolCatalog#uiOfBody} 是纯的，直接跑；「排队分支必须用它」这条在源码层面钉住。</p>
 */
public class QueuedAnswerTest {

    private static JsonObject json(String raw) {
        return new JsonParser().parse(raw).getAsJsonObject();
    }

    @Test
    public void theUiComesFromTheSameMetaGetStateServes() {
        assertEquals("treasure",
                ToolCatalog.uiOfBody(json("{\"meta\":{\"ui\":\"treasure\",\"ready\":true}}")));
        assertEquals("combat",
                ToolCatalog.uiOfBody(json("{\"meta\":{\"ui\":\"combat\"},\"hints\":{}}")));
    }

    @Test
    public void aBodyWithoutMetaOrUiAnswersNull() {
        assertNull(ToolCatalog.uiOfBody(null));
        assertNull(ToolCatalog.uiOfBody(json("{\"hints\":{}}")));
        assertNull(ToolCatalog.uiOfBody(json("{\"meta\":{\"ui\":null}}")));
        assertNull(ToolCatalog.uiOfBody(json("{\"meta\":{}}")));
        assertNull(ToolCatalog.uiOfBody(json("{\"meta\":null}")));
    }

    /** 排队分支必须用 cachedUi(当前快照)，不能再硬写 "other"。 */
    @Test
    public void theQueuedAnswerUsesTheSnapshotNotAConstant() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        int queued = code.indexOf("payload.addProperty(\"outcome\", settled ? \"applied\" : \"queued\")");
        assertTrue("找不到 queued 回包那段", queued > 0);
        String tail = code.substring(queued, Math.min(code.length(), queued + 1600));
        assertTrue("queued 回包的 ui 必须来自当前快照: " + tail, tail.contains("cachedUi("));
        assertFalse("不许再硬写 ui=\"other\"（那正是 C10-02）: " + tail,
                tail.contains("addProperty(\"ui\", \"other\")"));
        assertTrue("排队期间动作表仍为空", tail.contains("payload.add(\"actions\", new JsonArray())"));
    }

    /** legal_actions 的 not-ready 回包同一个规则：ui 也必须来自快照，不能写死 "other"。 */
    @Test
    public void theNotReadyLegalActionsAnswerUsesTheSnapshotToo() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        int start = code.indexOf("private JsonObject notReadyLegalActions(");
        assertTrue("找不到 notReadyLegalActions", start > 0);
        String body = code.substring(start, Math.min(code.length(), start + 1200));
        // #124·C15-01 起：优先用状态层本帧上报的 ui（过渡期是 "other"），没有时才退回缓存快照；
        // 两者都不能是硬写的常量。
        assertTrue("ui 必须取缓存快照或本帧上报值: " + body,
                body.contains("reportedUiOrCached()") || body.contains("cachedUi(cached)"));
        assertFalse("不许再硬写 ui=\"other\"", body.contains("addProperty(\"ui\", \"other\")"));
        assertTrue("screen_type 仍是原始屏名（两者并存），见 meta.screen_type 的语义",
                body.contains("screenType(cached)"));
    }
}
