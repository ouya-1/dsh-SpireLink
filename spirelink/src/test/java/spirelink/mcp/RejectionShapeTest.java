package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #120 · C11-01 / C11-05：拒绝回包的**形状**与**措辞**。
 *
 * <p>C11-01 现场：撞单飞队列的那次 {@code act} 把 {@code queue_status} / {@code waited_ms} /
 * {@code version_advanced} 挂在回包顶层，3 秒墙钟护栏把 {@code retry_after_ms} 也挂顶层 —— QA 按
 * 文档从 {@code error.*} 取，拿到的是 undefined。现在两处都给（顶层保留、镜像进 error）。</p>
 *
 * <p>C11-05 现场：{@code state_version:999999} 回「已过期（当前 21）」。未来版本号不可能是过期，
 * 措辞改成「比当前更新 / 还不存在」，错误码仍是 {@code STALE_VERSION}。</p>
 */
public class RejectionShapeTest {

    private static JsonObject json(String raw) {
        return new JsonParser().parse(raw).getAsJsonObject();
    }

    private static JsonObject rejection() {
        return json("{\"ok\":false,\"outcome\":\"rejected\",\"state_version\":21,"
                + "\"error\":{\"code\":\"ACTION_IN_FLIGHT\",\"message\":\"m\","
                + "\"state_version\":21},\"actions\":[]}");
    }

    @Test
    public void diagnosticsAreReadableFromErrorAndFromTheTopLevel() {
        JsonObject payload = rejection();
        payload.addProperty("queue_status", "BUSY");
        payload.addProperty("waited_ms", 616L);
        payload.addProperty("version_advanced", false);

        ToolCatalog.mirrorIntoError(payload, "queue_status", "waited_ms", "version_advanced");

        JsonObject error = payload.getAsJsonObject("error");
        assertEquals("QA 从 error.queue_status 取到 undefined 就是这条", "BUSY",
                error.get("queue_status").getAsString());
        assertEquals(616L, error.get("waited_ms").getAsLong());
        assertFalse(error.get("version_advanced").getAsBoolean());
        // 顶层保留：老客户端不用改。
        assertEquals("BUSY", payload.get("queue_status").getAsString());
        assertEquals(616L, payload.get("waited_ms").getAsLong());
        assertFalse(payload.get("version_advanced").getAsBoolean());
        // error 自己的字段不许被碰。
        assertEquals("ACTION_IN_FLIGHT", error.get("code").getAsString());
        assertEquals(21, error.get("state_version").getAsInt());
    }

    @Test
    public void anExistingErrorFieldWins() {
        JsonObject payload = rejection();
        payload.addProperty("waited_ms", 90L);
        payload.getAsJsonObject("error").addProperty("waited_ms", 12L);
        ToolCatalog.mirrorIntoError(payload, "waited_ms");
        assertEquals("error 里已有的值就是权威，不覆盖", 12L,
                payload.getAsJsonObject("error").get("waited_ms").getAsLong());
    }

    @Test
    public void mirroringIsHarmlessWithoutAnErrorObject() {
        JsonObject noError = json("{\"ok\":false,\"actions\":[]}");
        noError.addProperty("waited_ms", 5L);
        ToolCatalog.mirrorIntoError(noError, "waited_ms");
        assertFalse(noError.has("error"));

        JsonObject nullError = json("{\"error\":null}");
        nullError.addProperty("waited_ms", 5L);
        ToolCatalog.mirrorIntoError(nullError, "waited_ms");
        assertTrue(nullError.get("error").isJsonNull());

        ToolCatalog.mirrorIntoError(null, "waited_ms");
        ToolCatalog.mirrorIntoError(rejection(), (String[]) null);
    }

    @Test
    public void theFutureVersionIsNotCalledExpired() {
        String future = ToolCatalog.staleVersionMessage(999999, 21);
        assertTrue(future, future.contains("999999"));
        assertTrue(future, future.contains("21"));
        assertFalse("未来的版本号不能说「已过期」: " + future, future.contains("已过期"));
        assertTrue("要说清它还不存在: " + future, future.contains("还不存在"));

        String past = ToolCatalog.staleVersionMessage(11, 21);
        assertTrue("真正过期的那种仍按老口径说: " + past, past.contains("已过期"));
        assertTrue(past, past.contains("11"));
    }

    /** act 的 STALE_VERSION 分支必须走这个措辞函数，别再在分支里手写「已过期」。 */
    @Test
    public void actUsesTheSharedWording() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        int stale = code.indexOf("rejectedPayload(type, args, \"STALE_VERSION\"");
        assertTrue("找不到 STALE_VERSION 分支", stale > 0);
        String around = code.substring(Math.max(0, stale - 200), Math.min(code.length(), stale + 120));
        assertTrue("STALE_VERSION 的文案要来自 staleVersionMessage: " + around,
                around.contains("staleVersionMessage("));
        assertFalse("分支里不许再手写「已过期」", around.contains("已过期"));
    }

    /** 两个诊断字段必须真的被镜像进 error（不是只写了注释）。 */
    @Test
    public void bothGuardShapesMirrorTheirDetails() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        assertTrue("队列 BUSY/TIMEOUT 的三个字段要镜像进 error",
                code.contains("mirrorIntoError(payload, \"queue_status\", \"waited_ms\", "
                        + "\"version_advanced\")"));
        assertTrue("3 秒墙钟护栏的 retry_after_ms 也要镜像进 error",
                code.contains("mirrorIntoError(payload, \"retry_after_ms\")"));
    }
}
