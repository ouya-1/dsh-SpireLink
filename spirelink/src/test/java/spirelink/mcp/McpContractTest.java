package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import spirelink.state.StateBuilder;
import spirelink.ui.AgentHud;
import spirelink.ui.HudModel;
import spirelink.util.ActionInFlight;
import spirelink.util.ModLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * MCP 契约与实现一致性的回归锁（issue #54）。
 *
 * <p>三件事都是「实现是对的、对外说法是错的」，所以断言全部打在线上报文与 schema 上，而不是内部字段：
 * 未知工具的 {@code -32602} 文案不许再枚举固定工具名（要指向 {@code tools/list}）；
 * {@code tools/list} 的 {@code notes_list.inputSchema} 必须带 {@code sort}（实现早就认这个参数）；
 * 在飞护栏的墙钟只许有一个真源 {@link ActionInFlight#TIMEOUT_MS}，{@code ToolCatalog} 里那份
 * 30 秒死常量不许复活。
 *
 * <p>服务器绑 0 端口，和真机 8731 互不打扰。
 */
public class McpContractTest {

    private McpHttpServer server;
    private ToolCatalog catalog;

    @Before
    public void setUp() throws IOException {
        AgentHud.configure(true, 4);
        AgentHud.clear();
        McpHttpServer.resetDriverForTest();
        ModLog log = new ModLog(new File("target/mcp-contract-test"), false);
        catalog = new ToolCatalog(new MainThreadQueue(), new StateBuilder(log, "test"), log);
        server = new McpHttpServer("127.0.0.1", 0, "", catalog, log);
        server.start();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        AgentHud.clear();
        AgentHud.configure(true, HudModel.DEFAULT_LINES);
        McpHttpServer.resetDriverForTest();
    }

    // ------------------------------------------------------------------ 1) 未知工具文案

    /**
     * 旧文案硬编码「当前暴露 get_state / legal_actions / act / card_info」，实际是 9 或 19 个工具，
     * 于是每次读它都会被误导。新文案只认 {@code tools/list}，不许再出现任何固定名字。
     */
    @Test
    public void unknownToolMessagePointsAtToolsListInsteadOfAFixedEnumeration() throws IOException {
        JsonObject error = errorOf(post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":11,"
                + "\"method\":\"tools/call\",\"params\":{\"name\":\"no_such_tool\","
                + "\"arguments\":{}}}"));

        assertEquals(-32602, error.get("code").getAsInt());
        String message = error.get("message").getAsString();
        assertEquals(McpHttpServer.unknownToolMessage("no_such_tool"), message);
        assertTrue("要报出是哪个名字: " + message, message.contains("no_such_tool"));
        assertTrue("必须把 agent 引到 tools/list: " + message, message.contains("tools/list"));

        for (String stale : new String[]{"get_state", "legal_actions", "card_info"}) {
            assertFalse("文案里不许再有固定工具名 " + stale + ": " + message,
                    message.contains(stale));
        }
        assertFalse("文案里不许再单独枚举 act: " + message, message.matches("(?s).*\\bact\\b.*"));
    }

    // ------------------------------------------------------------------ 2) notes_list.sort schema

    /** 实现认 sort，schema 却只说 kind —— 客户端照 schema 写永远调不到 name 排序。 */
    @Test
    public void toolsListGivesNotesListASortField() throws IOException {
        JsonObject response = post("/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/list\"}");
        JsonArray tools = response.getAsJsonObject("result").getAsJsonArray("tools");
        JsonObject notesList = find(tools, ToolCatalog.TOOL_NOTES_LIST);
        assertNotNull("tools/list 里没有 notes_list", notesList);

        JsonObject schema = notesList.getAsJsonObject("inputSchema");
        assertEquals("object", schema.get("type").getAsString());
        JsonObject properties = schema.getAsJsonObject("properties");
        assertTrue("notes_list.inputSchema 必须有 kind", properties.has("kind"));
        assertTrue("notes_list.inputSchema 必须补上 sort（issue #54）", properties.has("sort"));

        JsonObject sort = properties.getAsJsonObject("sort");
        assertEquals("string", sort.get("type").getAsString());
        assertEquals("recent", sort.get("default").getAsString());
        JsonArray values = sort.getAsJsonArray("enum");
        assertEquals(2, values.size());
        assertEquals(ToolCatalog.NOTES_SORT_RECENT, values.get(0).getAsString());
        assertEquals(ToolCatalog.NOTES_SORT_NAME, values.get(1).getAsString());
        assertTrue("schema 里要写清两种排序的区别",
                sort.get("description").getAsString().contains("updated_at"));
    }

    /** 同一份便签数据，sort=name 走 kind:id 字典序，回包自称 name。 */
    @Test
    public void notesListSortNameOrdersByKeyAndEchoesName() {
        JsonObject payload = ToolCatalog.notesListPayload(threeNotes(), "name");

        assertEquals(true, payload.get("ok").getAsBoolean());
        assertEquals(ToolCatalog.NOTES_SORT_NAME, payload.get("sort").getAsString());
        assertEquals(3, payload.get("count").getAsInt());
        assertEquals("card:Strike_R", keyAt(payload, 0));
        assertEquals("event:BigFish", keyAt(payload, 1));
        assertEquals("monster:Louse", keyAt(payload, 2));
        // items[] 里的 kind / id / updated_at 仍按 key 拆开，和以前一样
        JsonObject first = payload.getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("card", first.get("kind").getAsString());
        assertEquals("Strike_R", first.get("id").getAsString());
        assertEquals(300L, first.get("updated_at").getAsLong());
    }

    /** 不给 sort（或给 recent）仍是最近更新在前，回包自称 recent。 */
    @Test
    public void notesListDefaultsToRecentAndEchoesRecent() {
        for (String requested : new String[]{null, "", "recent", "bogus"}) {
            JsonObject payload = ToolCatalog.notesListPayload(threeNotes(), requested);
            assertEquals("sort=" + requested, ToolCatalog.NOTES_SORT_RECENT,
                    payload.get("sort").getAsString());
            assertEquals("sort=" + requested, "card:Strike_R", keyAt(payload, 0));
            assertEquals("sort=" + requested, "monster:Louse", keyAt(payload, 1));
            assertEquals("sort=" + requested, "event:BigFish", keyAt(payload, 2));
        }
        JsonObject empty = ToolCatalog.notesListPayload(null, "name");
        assertEquals(0, empty.get("count").getAsInt());
        assertEquals(0, empty.getAsJsonArray("items").size());
        assertEquals(ToolCatalog.NOTES_SORT_NAME, empty.get("sort").getAsString());
    }

    /** 旧写法 id 仍可用（等价 name），但回包统一成 name，契约只有一套词汇。 */
    @Test
    public void legacySortIdIsAnAliasOfName() {
        assertEquals(ToolCatalog.NOTES_SORT_NAME, ToolCatalog.normalizeNotesSort("id"));
        assertEquals(ToolCatalog.NOTES_SORT_NAME, ToolCatalog.normalizeNotesSort("name"));
        assertEquals(ToolCatalog.NOTES_SORT_RECENT, ToolCatalog.normalizeNotesSort("recent"));
        assertEquals(ToolCatalog.NOTES_SORT_RECENT, ToolCatalog.normalizeNotesSort(null));
        assertEquals(ToolCatalog.NOTES_SORT_RECENT, ToolCatalog.normalizeNotesSort(""));

        JsonObject payload = ToolCatalog.notesListPayload(threeNotes(), "id");
        assertEquals(ToolCatalog.NOTES_SORT_NAME, payload.get("sort").getAsString());
        assertEquals("card:Strike_R", keyAt(payload, 0));
    }

    // ------------------------------------------------------------------ 3) 在飞护栏单一真源

    /**
     * {@code ToolCatalog.IN_FLIGHT_TIMEOUT_MS=30_000} + {@code queuedAt}/{@code queuedVersion} 是
     * 只写不读的死代码，真正生效的是 {@link ActionInFlight#TIMEOUT_MS}（3 秒）。删掉之后用反射钉住：
     * 谁再在 ToolCatalog 里造第二个超时常量，这条测试就红。
     */
    @Test
    public void inFlightGuardHasExactlyOneTimeout() {
        for (String dead : new String[]{"IN_FLIGHT_TIMEOUT_MS", "queuedAt", "queuedVersion"}) {
            try {
                ToolCatalog.class.getDeclaredField(dead);
                fail("死代码又回来了: ToolCatalog." + dead + "（真源只有 ActionInFlight.TIMEOUT_MS）");
            } catch (NoSuchFieldException expected) {
                // 正是我们要的：ToolCatalog 不再自己记在飞状态
            }
        }
        assertEquals("在飞护栏的墙钟是 3 秒", 3000L, ActionInFlight.TIMEOUT_MS);
    }

    // ------------------------------------------------------------------ helpers

    /** 三条便签：键名顺序（card < event < monster）与 updated_at 顺序（100 < 200 < 300）故意错开。 */
    private static JsonObject threeNotes() {
        JsonObject notes = new JsonObject();
        addNote(notes, "card:Strike_R", "打击：先打最疼的", 300L);
        addNote(notes, "event:BigFish", "大肥鱼：拿遗物别拿钱", 100L);
        addNote(notes, "monster:Louse", "虱子：先杀叫的", 200L);
        return notes;
    }

    private static void addNote(JsonObject notes, String key, String text, long updatedAt) {
        JsonObject entry = new JsonObject();
        entry.addProperty("text", text);
        entry.addProperty("updated_at", updatedAt);
        notes.add(key, entry);
    }

    private static String keyAt(JsonObject payload, int index) {
        return payload.getAsJsonArray("items").get(index).getAsJsonObject()
                .get("key").getAsString();
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

    private static JsonObject errorOf(JsonObject response) {
        assertNotNull("回包必须是 JSON-RPC 错误: " + response, response.get("error"));
        return response.getAsJsonObject("error");
    }

    private JsonObject post(String path, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        URL url = new URL("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
            } finally {
                out.close();
            }
            assertEquals(200, connection.getResponseCode());
            InputStream stream = connection.getInputStream();
            return new JsonParser().parse(readAll(stream)).getAsJsonObject();
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            stream.close();
        }
    }
}
