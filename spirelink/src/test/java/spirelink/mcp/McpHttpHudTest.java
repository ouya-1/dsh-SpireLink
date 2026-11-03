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
import spirelink.util.ModLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * HUD bridge over real HTTP (issue #28).
 *
 * <p>These are the two non-MCP routes plus the promise that they change nothing for agents:
 * {@code POST /hud/say} feeds the top-left thought block, {@code GET /hud/driver} names the DSH
 * session that is driving the game, and {@code tools/list} answers byte-for-byte what the same
 * process answered before the HUD traffic (9 base tools, or 19 with test_mode on).
 *
 * <p>The server binds port 0 (the OS picks a free one) so a running game and a test run never
 * fight over 8731.
 */
public class McpHttpHudTest {

    private static final String SESSION = "60c14a68-7d83-492b-94ee-30d3c785d19d";

    private McpHttpServer server;
    private ToolCatalog catalog;

    @Before
    public void setUp() throws IOException {
        AgentHud.configure(true, 4);
        AgentHud.clear();
        McpHttpServer.resetDriverForTest();
        ModLog log = new ModLog(new File("target/hud-http-test"), false);
        catalog = new ToolCatalog(new MainThreadQueue(), new StateBuilder(log, "test"), log);
        server = startServer(catalog, log, "");
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

    private static McpHttpServer startServer(ToolCatalog catalog, ModLog log, String token)
            throws IOException {
        McpHttpServer httpServer = new McpHttpServer("127.0.0.1", 0, token, catalog, log);
        httpServer.start();
        return httpServer;
    }

    // ------------------------------------------------------------------ /hud/say

    @Test
    public void hudSayDrawsTheThought() throws IOException {
        Reply reply = call(server, "POST", "/hud/say",
                "{\"text\":\"先打打击，再看它加不加力量\",\"ttlMs\":8000}",
                "X-DSH-Session", SESSION);
        assertEquals(200, reply.status);
        JsonObject body = new JsonParser().parse(reply.body).getAsJsonObject();
        assertTrue(body.get("shown").getAsBoolean());
        assertEquals(SESSION, body.get("sessionId").getAsString());
        assertEquals(8000L, body.get("ttlMs").getAsLong());

        assertEquals("先打打击，再看它加不加力量", AgentHud.thoughtText());
        assertTrue(AgentHud.thoughtAlpha() > 0.9f);
    }

    @Test
    public void hudSayWithoutTtlUsesTheDefault() throws IOException {
        Reply reply = call(server, "POST", "/hud/say", "{\"text\":\"默认 TTL\"}", null, null);
        assertEquals(200, reply.status);
        JsonObject body = new JsonParser().parse(reply.body).getAsJsonObject();
        assertEquals(AgentHud.DEFAULT_THOUGHT_TTL_MS, body.get("ttlMs").getAsLong());
        assertNotNull(AgentHud.thoughtText());
    }

    @Test
    public void hudSayIgnoresBlankTextAndKeepsTheOldThought() throws IOException {
        call(server, "POST", "/hud/say", "{\"text\":\"第一条\",\"ttlMs\":8000}", null, null);
        Reply reply = call(server, "POST", "/hud/say", "{\"text\":\"   \",\"ttlMs\":8000}",
                null, null);
        assertEquals(200, reply.status);
        assertFalse(new JsonParser().parse(reply.body).getAsJsonObject()
                .get("shown").getAsBoolean());
        assertEquals("第一条", AgentHud.thoughtText());
    }

    /**
     * 二次验收（真机乱码）的回归：中文 Windows 控制台里的 curl 发的是 GBK 字节，按 UTF-8 硬解会
     * 变成 U+FFFD + 零星拉丁/希腊字母，画到屏幕上就是「Ç÷» Σ」。这里确认 GBK 体也能正确上屏。
     */
    @Test
    public void hudSayAcceptsGbkBodyFromAChineseWindowsConsole() throws IOException {
        String text = "先把这波小怪清掉，再留一张防御牌过回合";
        String json = "{\"text\":\"" + text + "\",\"ttlMs\":9000}";
        byte[] gbk = json.getBytes(Charset.forName("GBK"));

        Reply reply = callRaw(server, "POST", "/hud/say", gbk, "X-DSH-Session", SESSION);
        assertEquals(200, reply.status);
        JsonObject body = new JsonParser().parse(reply.body).getAsJsonObject();
        assertTrue(body.get("shown").getAsBoolean());
        assertFalse("GBK 兜底后不该再有替换字符告警", body.has("warning"));

        assertEquals(text, AgentHud.thoughtText());
        assertEquals("不该有替换字符", -1, AgentHud.thoughtText().indexOf('\uFFFD'));
    }

    @Test
    public void bomInTheBodyDoesNotBreakParsing() throws IOException {
        String json = "{\"text\":\"带 BOM 的请求体\",\"ttlMs\":5000}";
        byte[] withBom = ("\uFEFF" + json).getBytes(StandardCharsets.UTF_8);
        Reply reply = callRaw(server, "POST", "/hud/say", withBom, null, null);
        assertEquals(200, reply.status);
        assertEquals("带 BOM 的请求体", AgentHud.thoughtText());
    }

    @Test
    public void decodeBodyPrefersUtf8ThenFallsBackToGbk() {
        String text = "思考";
        assertEquals(text, McpHttpServer.decodeBody(text.getBytes(StandardCharsets.UTF_8)));

        Charset[] utf8Used = new Charset[1];
        McpHttpServer.decodeBody(text.getBytes(StandardCharsets.UTF_8), utf8Used);
        assertEquals(StandardCharsets.UTF_8, utf8Used[0]);

        Charset[] gbkUsed = new Charset[1];
        assertEquals(text, McpHttpServer.decodeBody(text.getBytes(Charset.forName("GBK")), gbkUsed));
        assertEquals(Charset.forName("GBK"), gbkUsed[0]);

        assertEquals("", McpHttpServer.decodeBody(new byte[0]));
        // 既不是 UTF-8 也不是 GBK：兜底成替换字符，但绝不抛异常
        assertNotNull(McpHttpServer.decodeBody(new byte[]{(byte) 0x81, (byte) 0x20}));
    }

    @Test
    public void hudSayRejectsBrokenJson() throws IOException {
        Reply reply = call(server, "POST", "/hud/say", "{not json", null, null);
        assertEquals(400, reply.status);
        assertTrue(new JsonParser().parse(reply.body).getAsJsonObject().has("error"));

        Reply nonObject = call(server, "POST", "/hud/say", "[1,2,3]", null, null);
        assertEquals(400, nonObject.status);
        assertNull(AgentHud.thoughtText());
    }

    // ------------------------------------------------------------------ /hud/driver

    @Test
    public void driverReportsTheSessionThatSaysSomething() throws IOException {
        call(server, "POST", "/hud/say", "{\"text\":\"报个到\",\"ttlMs\":8000}",
                "X-DSH-Session", SESSION);

        Reply reply = call(server, "GET", "/hud/driver", null, null, null);
        assertEquals(200, reply.status);
        JsonObject body = new JsonParser().parse(reply.body).getAsJsonObject();
        assertEquals(SESSION, body.get("sessionId").getAsString());
        assertTrue(body.get("lastSeenAt").getAsLong() > 0L);
        assertEquals(0L, body.get("actions").getAsLong());
    }

    @Test
    public void driverIsEmptyBeforeAnybodyDrives() throws IOException {
        Reply reply = call(server, "GET", "/hud/driver", null, null, null);
        assertEquals(200, reply.status);
        JsonObject body = new JsonParser().parse(reply.body).getAsJsonObject();
        assertTrue(body.get("sessionId").isJsonNull());
        assertEquals(0L, body.get("lastSeenAt").getAsLong());
        assertEquals(0L, body.get("actions").getAsLong());
    }

    @Test
    public void toolsCallCountsAsDrivingTheGame() throws IOException {
        call(server, "POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"no_such_tool\",\"arguments\":{}}}",
                "X-DSH-Session", SESSION);

        JsonObject driver = new JsonParser().parse(
                call(server, "GET", "/hud/driver", null, null, null).body).getAsJsonObject();
        assertEquals(SESSION, driver.get("sessionId").getAsString());
        assertEquals(1L, driver.get("actions").getAsLong());
    }

    // ------------------------------------------------------------------ zero side effects

    /**
     * 硬约束（issue #28）: the HUD bridge must not touch {@code tools/list} at all.
     *
     * <p>Deliberately independent of {@code test_mode}: the baseline is "whatever this very process
     * would answer without any HUD traffic" ({@link ToolCatalog#listTools()}), not a hard-coded tool
     * count. 10 base tools are asserted by name because they must exist in every configuration; the
     * 10 {@code dev_*} tools come and go with test_mode and are not this test's business.
     */
    @Test
    public void toolsListIsUnchangedByTheHudBridge() throws IOException {
        JsonArray toolsBefore = toolsList();
        assertEquals("tools/list 必须等于同进程 ToolCatalog 的答案",
                catalog.listTools().toString(), toolsBefore.toString());
        assertTrue("常驻工具数量应为 10（test_mode 打开时 20；0.1.1 起多了 say）",
                toolsBefore.size() == 10 || toolsBefore.size() == 20);

        call(server, "POST", "/hud/say", "{\"text\":\"我在这里想事情\",\"ttlMs\":8000}",
                "X-DSH-Session", SESSION);
        call(server, "GET", "/hud/driver", null, null, null);

        JsonArray toolsAfter = toolsList();
        assertEquals("HUD 端点绝不能改动 tools/list", toolsBefore.toString(), toolsAfter.toString());
        assertFalse("HUD 端点绝不能变成工具", toolsAfter.toString().contains("hud"));

        java.util.Set<String> names = new java.util.HashSet<String>();
        for (int i = 0; i < toolsAfter.size(); i++) {
            names.add(toolsAfter.get(i).getAsJsonObject().get("name").getAsString());
        }
        for (String expected : new String[]{
                ToolCatalog.TOOL_GET_STATE, ToolCatalog.TOOL_LEGAL_ACTIONS, ToolCatalog.TOOL_ACT,
                ToolCatalog.TOOL_CARD_INFO, ToolCatalog.TOOL_GLOSSARY, ToolCatalog.TOOL_NOTE_SET,
                ToolCatalog.TOOL_NOTE_GET, ToolCatalog.TOOL_NOTES_LIST, ToolCatalog.TOOL_EVENT_NOTE}) {
            assertTrue("常驻工具缺失: " + expected, names.contains(expected));
        }
    }

    private JsonArray toolsList() throws IOException {
        Reply reply = call(server, "POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", null, null);
        assertEquals(200, reply.status);
        return new JsonParser().parse(reply.body).getAsJsonObject()
                .getAsJsonObject("result").getAsJsonArray("tools");
    }

    // ------------------------------------------------------------------ routing

    @Test
    public void hudRoutesRejectWrongMethodsAndUnknownPaths() throws IOException {
        assertEquals(405, call(server, "GET", "/hud/say", null, null, null).status);
        assertEquals(405, call(server, "POST", "/hud/driver", "{}", null, null).status);
        assertEquals(404, call(server, "GET", "/hud/nope", null, null, null).status);
    }

    @Test
    public void tokenGuardCoversTheHudRoutesToo() throws IOException {
        ModLog log = new ModLog(new File("target/hud-http-test"), false);
        ToolCatalog catalog = new ToolCatalog(new MainThreadQueue(), new StateBuilder(log, "test"),
                log);
        McpHttpServer guarded = startServer(catalog, log, "s3cret");
        try {
            assertEquals(401, call(guarded, "POST", "/hud/say",
                    "{\"text\":\"没带 token\"}", null, null).status);
            assertEquals(401, call(guarded, "GET", "/hud/driver", null, null, null).status);
            assertNull(AgentHud.thoughtText());

            Reply ok = call(guarded, "POST", "/hud/say", "{\"text\":\"带了 token\"}",
                    "Authorization", "Bearer s3cret");
            assertEquals(200, ok.status);
            assertEquals("带了 token", AgentHud.thoughtText());
        } finally {
            guarded.stop();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final class Reply {
        final int status;
        final String body;

        Reply(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private Reply call(McpHttpServer target, String method, String path, String body,
                       String headerName, String headerValue) throws IOException {
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        return callRaw(target, method, path, payload, headerName, headerValue);
    }

    /** Byte-level variant: lets a test send a body in GBK, exactly like a Windows console curl. */
    private Reply callRaw(McpHttpServer target, String method, String path, byte[] payload,
                          String headerName, String headerValue) throws IOException {
        URL url = new URL("http://127.0.0.1:" + target.boundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Connection", "close");
            if (headerName != null && headerValue != null) {
                connection.setRequestProperty(headerName, headerValue);
            }
            if (payload != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(payload);
                } finally {
                    out.close();
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            return new Reply(status, readAll(stream));
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
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