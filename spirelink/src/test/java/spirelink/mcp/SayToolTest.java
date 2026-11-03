package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import spirelink.ui.AgentHud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code say}: the HUD thought channel exposed as a tool.
 *
 * <p>Only the contract is checked here - the payload/counters are pure data, and the renderer is
 * covered by {@code McpHttpHudTest}. What matters is that the tool is listed (agents discover it
 * through {@code tools/list}) and that a call reaches {@link AgentHud#thought(String, long)}.
 */
public class SayToolTest {

    @Test
    public void sayIsListedAlongsideTheGameTools() {
        JsonArray tools = new ToolCatalog(null, null, null).listTools();
        boolean found = false;
        for (int i = 0; i < tools.size(); i++) {
            if (ToolCatalog.TOOL_SAY.equals(tools.get(i).getAsJsonObject().get("name").getAsString())) {
                found = true;
                break;
            }
        }
        assertTrue("say 必须在 tools/list 里：agent 只认这份清单", found);
    }

    @Test
    public void saySchemaRequiresText() {
        JsonArray tools = new ToolCatalog(null, null, null).listTools();
        JsonObject say = null;
        for (int i = 0; i < tools.size(); i++) {
            JsonObject t = tools.get(i).getAsJsonObject();
            if (ToolCatalog.TOOL_SAY.equals(t.get("name").getAsString())) {
                say = t;
                break;
            }
        }
        assertNotNull(say);
        JsonObject schema = say.getAsJsonObject("inputSchema");
        assertEquals("object", schema.get("type").getAsString());
        assertEquals("text", schema.getAsJsonArray("required").get(0).getAsString());
        assertTrue(schema.getAsJsonObject("properties").has("ttl_ms"));
    }

    @Test
    public void actCarriesAnOptionalSay() {
        JsonArray tools = new ToolCatalog(null, null, null).listTools();
        JsonObject act = null;
        for (int i = 0; i < tools.size(); i++) {
            JsonObject t = tools.get(i).getAsJsonObject();
            if (ToolCatalog.TOOL_ACT.equals(t.get("name").getAsString())) {
                act = t;
                break;
            }
        }
        assertNotNull(act);
        assertTrue("act 应该能顺带说一句话（0.1.3）：agent 不会为了说话离开工具流",
                act.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("say"));
    }

    @Test
    public void sayRejectsEmptyText() {
        JsonObject args = new JsonObject();
        args.addProperty("text", "   ");
        ToolCatalog.ToolResult result = new ToolCatalog(null, null, null).call(ToolCatalog.TOOL_SAY, args);
        assertTrue("空 text 必须报错", result.isError);
        assertTrue("错误码要能被 agent 看懂: " + result.payload,
                result.payload.toString().contains("BAD_REQUEST"));
    }

    @Test
    public void sayReportsWhatItShowed() {
        JsonObject args = new JsonObject();
        args.addProperty("text", "测试：这一步先挡 8 点");
        args.addProperty("ttl_ms", 5000);
        ToolCatalog.ToolResult result = new ToolCatalog(null, null, null).call(ToolCatalog.TOOL_SAY, args);
        assertFalse(result.isError);
        assertTrue(result.payload.has("shown"));
        assertEquals(5000L, result.payload.get("ttl_ms").getAsLong());
        assertTrue(result.payload.get("chars").getAsInt() > 0);
    }
}
