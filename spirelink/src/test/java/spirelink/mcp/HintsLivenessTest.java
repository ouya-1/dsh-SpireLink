package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import spirelink.util.RuntimeProbe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issues #66 / #68: {@code hints} must not be invented by the liveness decorator.
 *
 * <p>{@code ToolCatalog.decorateLiveness} used to create a hints object whenever the payload had
 * none, so every {@code ready:true} answer carried
 * {@code hints:{frames,beats,ms_since_frame,ms_since_beat}}. That contradicted the design ("hints
 * only when not ready, or when the last act was refused"), cost tokens on every call,
 * survived {@code exclude:["hints"]}, and taught agents that "hints present = my last step failed".
 *
 * <p>Assertions are on the pure merge ({@link ToolCatalog#mergeLiveness(JsonObject)}), which is what
 * the payload builder calls.
 */
public class HintsLivenessTest {

    private static JsonObject json(String raw) {
        return new JsonParser().parse(raw).getAsJsonObject();
    }

    /** ready + 没有 hints：合并之后仍然没有 hints（不再凭空造一个四件套）。 */
    @Test
    public void readyPayloadWithoutHintsStaysWithoutHints() {
        JsonObject payload = json("{\"ok\":true,\"ready\":true,\"state_version\":42}");
        ToolCatalog.mergeLiveness(payload);
        assertFalse("ready 回包不许长出 hints: " + payload, payload.has("hints"));

        // 幂等：再来一次也不许造
        ToolCatalog.mergeLiveness(payload);
        assertFalse("第二次合并也不许造 hints: " + payload, payload.has("hints"));
    }

    /** 有 hints 时才合并：liveness 数字进去，原有字段原样保留。 */
    @Test
    public void existingHintsGetTheLiveCounters() {
        JsonObject payload = json("{\"ok\":false,\"ready\":false,\"state_version\":7,"
                + "\"hints\":{\"needs_inspect\":true,\"screen\":\"MAP\"}}");
        ToolCatalog.mergeLiveness(payload);

        JsonObject hints = payload.getAsJsonObject("hints");
        assertEquals("MAP", hints.get("screen").getAsString());
        assertTrue(hints.get("needs_inspect").getAsBoolean());
        assertEquals(RuntimeProbe.frames(), hints.get("frames").getAsLong());
        assertEquals(RuntimeProbe.beats(), hints.get("beats").getAsLong());
        assertTrue("必须带 ms_since_frame", hints.has("ms_since_frame"));
        assertTrue("必须带 ms_since_beat", hints.has("ms_since_beat"));
    }

    /** exclude:["hints"] 删掉之后不许被装饰器复活（这正是旧行为的 bug 之一）。 */
    @Test
    public void excludedHintsAreNotResurrected() {
        // exclude 的实现就是把 key 删掉，再交给 decorate；这里复现同样的输入。
        JsonObject payload = json("{\"ok\":true,\"ready\":true,\"state_version\":42,"
                + "\"player\":{\"hp\":70}}");
        ToolCatalog.mergeLiveness(payload);
        assertFalse("exclude 之后不许复活 hints", payload.has("hints"));
        assertTrue("别的区块不许被动", payload.has("player"));
    }

    /** hints 显式为 null 时也不许被替换成对象。 */
    @Test
    public void nullHintsIsLeftAlone() {
        JsonObject payload = json("{\"ok\":false,\"hints\":null}");
        ToolCatalog.mergeLiveness(payload);
        assertTrue("null hints 不许被替换", payload.get("hints").isJsonNull());
    }

    /** payload 传 null 不炸（防御式）。 */
    @Test
    public void nullPayloadIsIgnored() {
        ToolCatalog.mergeLiveness(null);
        // 走到这里没抛异常就是通过：装饰器不许把一次状态读取炸掉。
    }
}
