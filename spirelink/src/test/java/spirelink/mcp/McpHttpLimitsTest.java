package spirelink.mcp;

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
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Transport limits and non-MCP error shapes (issues #71, #78).
 *
 * <p>#71: a body over {@link McpHttpServer#MAX_REQUEST_BYTES} used to come back as
 * {@code HTTP 500 + -32603 "内部错误: java.io.IOException: 请求体超过 …"}. An agent reads 500 as "the
 * server is broken" and retries the same huge body; an oversized body is the <em>caller's</em>
 * mistake, so it must be 413 with a transport-level error code and the limit spelled out.
 *
 * <p>#78: {@code /hud/say} accepted 100k characters silently, ignored a non-string {@code text}
 * without a word, and {@code /hud/driver} never moved {@code lastSeenAt} unless the caller sent
 * {@code X-DSH-Session} - and the {@code /hud/*} routes answered in a JSON-RPC envelope even though
 * they are not JSON-RPC endpoints.
 *
 * <p>The server binds port 0, so a running game on 8731 is never disturbed.
 */
public class McpHttpLimitsTest {

    private static final String SESSION = "2f1c0f0e-0c5c-4a1e-9a8f-limits-test";

    private McpHttpServer server;
    private ToolCatalog catalog;

    @Before
    public void setUp() throws IOException {
        AgentHud.configure(true, 4);
        AgentHud.clear();
        McpHttpServer.resetDriverForTest();
        ModLog log = new ModLog(new File("target/mcp-limits-test"), false);
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

    // ------------------------------------------------------------------ #71 请求体上限

    /** 超限请求体：413 + -32600 + 明确文案，不许再是 500 / -32603。 */
    @Test
    public void oversizedBodyAnswers413WithATransportError() throws IOException {
        String body = requestWithPadding(McpHttpServer.MAX_REQUEST_BYTES + 4096);
        Reply reply = call("POST", "/mcp", body.getBytes(StandardCharsets.UTF_8), null, null);

        assertEquals("超大请求体是客户端错误，HTTP 必须是 413", 413, reply.status);
        JsonObject json = new JsonParser().parse(reply.body).getAsJsonObject();
        JsonObject error = json.getAsJsonObject("error");
        assertNotNull("必须是 JSON-RPC 错误对象: " + reply.body, error);
        assertEquals("传输层请求错误码", -32600, error.get("code").getAsInt());

        String message = error.get("message").getAsString();
        assertTrue("文案要给出上限字节数: " + message, message.contains("1048576"));
        assertTrue("文案要点明是客户端错误: " + message, message.contains("客户端错误"));
        assertFalse("不许再自称内部错误: " + message, message.contains("内部错误"));
        assertFalse("不许再出现 IOException 字样: " + message, message.contains("IOException"));
    }

    /** 超限文案本身：可读、带上限、带 413 口径。 */
    @Test
    public void oversizedMessageExplainsTheLimit() {
        String message = McpHttpServer.bodyTooLargeMessage(McpHttpServer.MAX_REQUEST_BYTES, 1100000L);
        assertTrue(message.contains("1048576"));
        assertTrue(message.contains("413"));
        assertTrue(message.contains("客户端错误"));
    }

    /** 正常大小的请求不受影响。 */
    @Test
    public void normalRequestIsUnaffected() throws IOException {
        Reply reply = call("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/list\"}"
                        .getBytes(StandardCharsets.UTF_8), null, null);
        assertEquals(200, reply.status);
        JsonObject json = new JsonParser().parse(reply.body).getAsJsonObject();
        assertTrue(json.getAsJsonObject("result").has("tools"));
    }

    // ------------------------------------------------------------------ #78 /hud/say 上限

    /** 10 万字符不再照收：截断到上限并明确标注 truncated / chars_in / max_chars。 */
    @Test
    public void hudSayTruncatesLongText() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            text.append('思');
        }
        Reply reply = call("POST", "/hud/say",
                ("{\"text\":\"" + text + "\"}").getBytes(StandardCharsets.UTF_8), null, null);
        assertEquals(200, reply.status);
        JsonObject body = new JsonParser().parse(reply.body).getAsJsonObject();
        assertTrue("截断必须说出来", body.get("truncated").getAsBoolean());
        assertEquals(McpHttpServer.MAX_SAY_CHARS, body.get("chars").getAsInt());
        assertEquals(3000, body.get("chars_in").getAsInt());
        assertEquals(McpHttpServer.MAX_SAY_CHARS, body.get("max_chars").getAsInt());
        assertEquals("屏幕上再被 HUD 的显示上限截一次",
                AgentHud.THOUGHT_MAX_CHARS, AgentHud.thoughtText().length());
        // HUD 自己还有更小的一层显示上限：真正上屏多少字也必须如实回报（issue #78）。
        assertEquals("上屏字数要如实回报", AgentHud.THOUGHT_MAX_CHARS,
                body.get("shown_chars").getAsInt());
        assertEquals(AgentHud.THOUGHT_MAX_CHARS, body.get("hud_max_chars").getAsInt());
    }

    /** 正常长度不带 truncated 字段（不制造噪音）。 */
    @Test
    public void shortTextIsNotMarkedTruncated() throws IOException {
        Reply reply = call("POST", "/hud/say", "{\"text\":\"短思考\"}"
                .getBytes(StandardCharsets.UTF_8), null, null);
        assertEquals(200, reply.status);
        JsonObject body = new JsonParser().parse(reply.body).getAsJsonObject();
        assertFalse(body.has("truncated"));
        assertEquals(3, body.get("chars").getAsInt());
    }

    /** text 传对象：400 + 明确错误，不再静默忽略。 */
    @Test
    public void hudSayRejectsNonStringText() throws IOException {
        Reply reply = call("POST", "/hud/say", "{\"text\":{\"a\":1}}"
                .getBytes(StandardCharsets.UTF_8), null, null);
        assertEquals(400, reply.status);
        JsonObject body = new JsonParser().parse(reply.body).getAsJsonObject();
        assertTrue("必须给出错误", body.has("error"));
        assertTrue(body.get("error").getAsString().contains("text"));
        assertEquals("没有内容被显示", null, AgentHud.thoughtText());
    }

    /** 不带 X-DSH-Session 也算「有人驱动过」：/hud/driver 的 lastSeenAt 必须往前走。 */
    @Test
    public void driverLastSeenMovesWithoutASessionHeader() throws IOException {
        call("POST", "/hud/say", "{\"text\":\"没带 session 头\"}"
                .getBytes(StandardCharsets.UTF_8), null, null);
        Reply reply = call("GET", "/hud/driver", null, null, null);
        assertEquals(200, reply.status);
        JsonObject body = new JsonParser().parse(reply.body).getAsJsonObject();
        assertTrue("lastSeenAt 必须 > 0（issue #78）", body.get("lastSeenAt").getAsLong() > 0L);
        assertTrue("没人报过名字时 sessionId 仍是 null", body.get("sessionId").isJsonNull());
    }

    /** 带了 X-DSH-Session：sessionId 回显成它，且 session_source 说明来源。 */
    @Test
    public void driverSessionComesFromTheHeader() throws IOException {
        Reply reply = call("POST", "/hud/say", "{\"text\":\"带 session 头\"}"
                .getBytes(StandardCharsets.UTF_8), "X-DSH-Session", SESSION);
        JsonObject body = new JsonParser().parse(reply.body).getAsJsonObject();
        assertEquals(SESSION, body.get("sessionId").getAsString());
        assertEquals("header", body.get("session_source").getAsString());
    }

    // ------------------------------------------------------------------ #78 非 MCP 路由错误形状

    /** /hud/* 不是 JSON-RPC 端点：错误体不许再套 jsonrpc/id/error.code。 */
    @Test
    public void hudRoutesAnswerPlainErrorsNotJsonRpc() throws IOException {
        Reply wrongMethod = call("GET", "/hud/say", null, null, null);
        assertEquals(405, wrongMethod.status);
        JsonObject body = new JsonParser().parse(wrongMethod.body).getAsJsonObject();
        assertTrue(body.has("error"));
        assertEquals(405, body.get("status").getAsInt());
        assertFalse("不许自称 JSON-RPC", body.has("jsonrpc"));
        assertFalse("不许带 JSON-RPC 错误码", body.has("code"));

        Reply unknown = call("GET", "/hud/nope", null, null, null);
        assertEquals(404, unknown.status);
        JsonObject unknownBody = new JsonParser().parse(unknown.body).getAsJsonObject();
        assertTrue(unknownBody.has("error"));
        assertFalse(unknownBody.has("jsonrpc"));
    }

    // ------------------------------------------------------------------ #86 线上 error 形状

    /** 线上回包（structuredContent 与 text content）的 error 也必须是对象，且带 isError。 */
    @Test
    public void toolFailureErrorShapeOnTheWire() throws IOException {
        Reply reply = call("POST", "/mcp",
                ("{\"jsonrpc\":\"2.0\",\"id\":71,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"note_set\",\"arguments\":"
                        + "{\"kind\":\"general\",\"id\":\"qa-86\"}}}")
                        .getBytes(StandardCharsets.UTF_8), null, null);
        assertEquals(200, reply.status);
        JsonObject json = new JsonParser().parse(reply.body).getAsJsonObject();
        JsonObject result = json.getAsJsonObject("result");
        assertTrue("失败的工具调用必须 isError",
                result.has("isError") && result.get("isError").getAsBoolean());

        JsonObject error = result.getAsJsonObject("structuredContent").getAsJsonObject("error");
        assertNotNull("structuredContent.error 必须是对象", error);
        assertEquals("BAD_REQUEST", error.get("code").getAsString());
        assertFalse(error.get("message").getAsString().isEmpty());

        String text = result.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString();
        assertTrue("text content 里的 error 也必须是对象: " + text,
                text.contains("\"error\":{\"code\""));
    }

    // ------------------------------------------------------------------ helpers

    /** A syntactically valid tools/call padded to {@code padBytes} characters. */
    private static String requestWithPadding(int padBytes) {
        StringBuilder pad = new StringBuilder(padBytes);
        for (int i = 0; i < padBytes; i++) {
            pad.append('x');
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get_state\",\"arguments\":{\"pad\":\""
                + pad + "\"}}}";
    }

    private static final class Reply {
        final int status;
        final String body;

        Reply(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private Reply call(String method, String path, byte[] payload, String headerName,
                       String headerValue) throws IOException {
        URL url = new URL("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
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
