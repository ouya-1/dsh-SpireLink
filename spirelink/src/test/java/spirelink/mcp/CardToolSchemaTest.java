package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import spirelink.util.TestMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * dev_card / dev_use_card 的 schema 契约：force 必须是 boolean，卡牌 ID 说明要覆盖三种写法，
 * dev_use_card 要讲清 result 三态（e2e_cards_fast.py 靠这三个值判通过/失败）。
 */
public class CardToolSchemaTest {

    @Test
    public void devCardToolsExposeForceAndIdFormats() {
        TestMode.setEnabled(true);
        try {
            ToolCatalog catalog = new ToolCatalog(null, null, null);
            JsonArray tools = catalog.listTools();
            JsonObject use = find(tools, ToolCatalog.TOOL_DEV_USE_CARD);
            JsonObject card = find(tools, ToolCatalog.TOOL_DEV_CARD);
            assertNotNull("dev_use_card 不在 tools/list 里", use);
            assertNotNull("dev_card 不在 tools/list 里", card);
            assertEquals("boolean", forceProp(use).get("type").getAsString());
            assertEquals("boolean", forceProp(card).get("type").getAsString());
            for (JsonObject tool : new JsonObject[]{use, card}) {
                String desc = tool.get("description").getAsString();
                assertTrue(desc, desc.contains("AThousandCuts"));
                assertTrue(desc, desc.contains("A Thousand Cuts"));
                assertTrue(desc, desc.contains("全限定名"));
            }
            String useDesc = use.get("description").getAsString();
            assertTrue(useDesc, useDesc.contains("played"));
            assertTrue(useDesc, useDesc.contains("blocked_by_game_rule"));
            assertTrue(useDesc, useDesc.contains("metadata_only"));
        } finally {
            TestMode.setEnabled(false);
        }
    }

    private static JsonObject find(JsonArray tools, String name) {
        for (int i = 0; i < tools.size(); i++) {
            JsonObject tool = tools.get(i).getAsJsonObject();
            if (name.equals(tool.get("name").getAsString())) {
                return tool;
            }
        }
        return null;
    }

    private static JsonObject forceProp(JsonObject tool) {
        JsonObject schema = tool.getAsJsonObject("inputSchema");
        assertNotNull(schema);
        JsonObject properties = schema.getAsJsonObject("properties");
        assertNotNull(properties);
        assertTrue(properties.has("force"));
        return properties.getAsJsonObject("force");
    }
}
