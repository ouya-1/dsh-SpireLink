package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import spirelink.actions.ActionTypes;
import spirelink.util.TestMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Issues #81 / #80: {@code inputSchema} must say what the implementation actually accepts.
 *
 * <p>#81: the enumerations were missing everywhere (note kinds, dev_room.type, dev_reward.kind,
 * dev_card.to, act.type) and {@code card_info.uuid} was not marked required although the tool
 * refuses a call without it. #80: {@code act}'s description claimed {@code action_id} was mandatory
 * while {@code required} listed only {@code type}/{@code state_version}, and {@code dev_event}'s
 * {@code force} (which {@code DevEventTool} honours) was absent from the schema.
 *
 * <p>Every enumeration is asserted against its live source of truth, so a new kind or action type
 * cannot silently leave the schema behind.
 */
public class ToolSchemaEnumTest {

    private static final List<String> NOTE_KINDS = Arrays.asList(
            "event", "potion", "card", "monster", "relic", "general");

    private static JsonArray tools() {
        TestMode.setEnabled(true);
        try {
            return new ToolCatalog(null, null, null).listTools();
        } finally {
            TestMode.setEnabled(false);
        }
    }

    // ------------------------------------------------------------------ note 族 kind

    /** note_set / note_get / notes_list 的 kind 都要有 6 值 enum。 */
    @Test
    public void noteToolsEnumerateTheirKinds() {
        JsonArray tools = tools();
        for (String name : new String[]{ToolCatalog.TOOL_NOTE_SET, ToolCatalog.TOOL_NOTE_GET,
                ToolCatalog.TOOL_NOTES_LIST}) {
            JsonObject tool = find(tools, name);
            assertNotNull(name + " 不在 tools/list 里", tool);
            JsonObject kind = tool.getAsJsonObject("inputSchema")
                    .getAsJsonObject("properties").getAsJsonObject("kind");
            assertNotNull(name + ".kind 缺失", kind);
            assertEquals(name + ".kind 的 enum", NOTE_KINDS, values(kind));
        }
    }

    // ------------------------------------------------------------------ dev_* 枚举

    @Test
    public void devRoomEnumeratesItsRoomTypes() {
        JsonObject room = find(tools(), ToolCatalog.TOOL_DEV_ROOM);
        JsonObject type = room.getAsJsonObject("inputSchema")
                .getAsJsonObject("properties").getAsJsonObject("type");
        assertEquals(Arrays.asList("monster", "elite", "boss", "rest", "shop", "treasure",
                "event", "victory"), values(type));
    }

    /** dev_reward.kind 的 enum 必须等于工具自己的白名单（不是手抄的第二份）。 */
    @Test
    public void devRewardEnumeratesTheToolWhitelist() {
        JsonObject reward = find(tools(), ToolCatalog.TOOL_DEV_REWARD);
        JsonObject kind = reward.getAsJsonObject("inputSchema")
                .getAsJsonObject("properties").getAsJsonObject("kind");
        assertEquals(new ArrayList<String>(DevRewardTool.KINDS), values(kind));
    }

    @Test
    public void devCardEnumeratesItsDestination() {
        JsonObject card = find(tools(), ToolCatalog.TOOL_DEV_CARD);
        JsonObject to = card.getAsJsonObject("inputSchema")
                .getAsJsonObject("properties").getAsJsonObject("to");
        assertEquals(Arrays.asList("hand", "deck"), values(to));
    }

    // ------------------------------------------------------------------ #80 dev_event.force

    /** tools.md 承诺的 dev_event{force:true} 必须真的在 schema 里（实现已在 DevEventTool 侧）。 */
    @Test
    public void devEventExposesForce() {
        JsonObject event = find(tools(), ToolCatalog.TOOL_DEV_EVENT);
        JsonObject force = event.getAsJsonObject("inputSchema")
                .getAsJsonObject("properties").getAsJsonObject("force");
        assertNotNull("dev_event.force 缺失（issue #80）", force);
        assertEquals("boolean", force.get("type").getAsString());
        assertTrue(force.get("description").getAsString().contains("动作队列"));
    }

    // ------------------------------------------------------------------ act.type / required

    /** act.type 的 enum 必须覆盖 ActionTypes 的全部常量（反射来的，不会漏项）。 */
    @Test
    public void actEnumeratesEveryActionType() {
        String[] names = ToolCatalog.actionTypeNames();
        assertTrue("ActionTypes 一个常量都没读到", names.length > 20);
        JsonObject act = find(tools(), ToolCatalog.TOOL_ACT);
        JsonObject type = act.getAsJsonObject("inputSchema")
                .getAsJsonObject("properties").getAsJsonObject("type");
        List<String> advertised = values(type);
        assertEquals("act.type 的 enum 必须与 ActionTypes 完全一致",
                Arrays.asList(names), advertised);
        for (String expected : new String[]{ActionTypes.END_TURN, ActionTypes.PLAY_CARD,
                ActionTypes.CHOOSE_MAP_NODE, ActionTypes.TAKE_REWARD}) {
            assertTrue("act.type 漏了 " + expected, advertised.contains(expected));
        }
    }

    /** #80：action_id 的说法与 required 必须一致——现在两边都说「可选」。 */
    @Test
    public void actActionIdIsOptionalInBothPlaces() {
        JsonObject act = find(tools(), ToolCatalog.TOOL_ACT);
        JsonObject schema = act.getAsJsonObject("inputSchema");
        List<String> required = values(schema.getAsJsonArray("required"));
        assertEquals(Arrays.asList("type", "state_version"), required);

        String actionId = schema.getAsJsonObject("properties").getAsJsonObject("action_id")
                .get("description").getAsString();
        assertTrue("必须说清是可选: " + actionId, actionId.contains("可选"));
        assertFalse("不许再自称必须: " + actionId, actionId.contains("必须带"));

        String description = act.get("description").getAsString();
        assertFalse("工具描述里不许再说「必须带 action_id」: " + description,
                description.contains("必须带 action_id"));
    }

    // ------------------------------------------------------------------ card_info.uuid

    /** card_info 不带 uuid 运行时会 BAD_REQUEST，所以 schema 必须要求它。 */
    @Test
    public void cardInfoRequiresUuid() {
        JsonObject cardInfo = find(tools(), ToolCatalog.TOOL_CARD_INFO);
        JsonArray required = cardInfo.getAsJsonObject("inputSchema").getAsJsonArray("required");
        assertNotNull("card_info 必须有 required", required);
        assertTrue("card_info.uuid 必须进 required（issue #81）",
                values(required).contains("uuid"));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject find(JsonArray tools, String name) {
        for (int i = 0; i < tools.size(); i++) {
            JsonObject tool = tools.get(i).getAsJsonObject();
            if (name.equals(tool.get("name").getAsString())) {
                return tool;
            }
        }
        return null;
    }

    private static List<String> values(JsonObject holder) {
        return values(holder.getAsJsonArray("enum"));
    }

    private static List<String> values(JsonArray array) {
        List<String> out = new ArrayList<String>();
        if (array == null) {
            return out;
        }
        for (int i = 0; i < array.size(); i++) {
            out.add(array.get(i).getAsString());
        }
        return out;
    }
}
