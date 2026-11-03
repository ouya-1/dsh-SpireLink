package spirelink.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import spirelink.ui.AgentHud;
import spirelink.util.ModLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP transport only: JSON-RPC 2.0 over Streamable HTTP on {@code 127.0.0.1:<port>/mcp}
 * (design 4 / 13).
 *
 * <ul>
 *   <li>{@code POST /mcp}: JSON-RPC requests; notifications answered with {@code 202}</li>
 *   <li>{@code initialize} issues the {@code Mcp-Session-Id} header; unknown session ids rejected</li>
 *   <li>Responses are {@code application/json; charset=utf-8}; an SSE {@code Accept} is tolerated
 *       but this server does not offer a GET stream</li>
 *   <li>Optional {@code Authorization: Bearer <token>}</li>
 *   <li>Port conflicts fail loudly; the server never silently switches ports</li>
 *   <li>Off-MCP HUD bridge (issue #28): {@code POST /hud/say} and {@code GET /hud/driver}. These
 *       are deliberately <em>not</em> tools, so {@code tools/list} and every existing agent prompt
 *       stay byte-identical and the per-turn token cost is exactly zero.</li>
 * </ul>
 *
 * <p>No game object is touched here: every tool call goes through {@link MainThreadQueue}.
 */
public final class McpHttpServer {

    public static final String SERVER_NAME = "spirelink";
    private static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = new LinkedHashSet<>(
            Arrays.asList("2024-11-05", "2025-03-26", "2025-06-18"));
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";
    /** Request-body ceiling; over it the answer is 413 (client error), never 500 (issue #71). */
    static final int MAX_REQUEST_BYTES = 1024 * 1024;
    /** {@code /hud/say} text ceiling: the thought block shows a couple of lines, not an essay (#78). */
    static final int MAX_SAY_CHARS = 2000;

    /**
     * A request body that is bigger than {@link #MAX_REQUEST_BYTES}.
     *
     * <p>Issue #71: this used to be a plain {@link IOException}, which the generic handler turned
     * into {@code HTTP 500 + -32603 "内部错误: java.io.IOException: 请求体超过 …"}. An agent reads 500
     * as "the server is broken" and retries the very same huge body. Oversized body = the caller's
     * mistake: 413 + a transport-level error code, with the limit spelled out.
     */
    static final class BodyTooLargeException extends IOException {
        final long limit;
        final long size;

        BodyTooLargeException(long limit, long size) {
            super("请求体超过 " + limit + " 字节（已读 " + size + " 字节）");
            this.limit = limit;
            this.size = size;
        }
    }

    private final String host;
    private final int port;
    private final String token;
    private final ToolCatalog toolCatalog;
    private final ModLog log;
    // serializeNulls(): the design examples require explicit nulls (meta.awaiting, stance,
    // unplayable_reason, combat: null). Gson drops JsonNull members unless this is enabled.
    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    // ---------------------------------------------------------------- HUD bridge state (#28)
    /** Header a driver (pc.py / the tee) uses to claim a DSH session id. */
    public static final String HEADER_DSH_SESSION = "X-DSH-Session";
    public static final String HUD_PATH_SAY = "/hud/say";
    public static final String HUD_PATH_DRIVER = "/hud/driver";

    /**
     * The DSH session that last touched this game, and how many tool calls it has made. Process-wide
     * on purpose: there is exactly one server per game process, and {@code AgentHud} is static too.
     *
     * <p>The tee reads this through {@code GET /hud/driver} and only mirrors the session named here,
     * which is what keeps two parallel DSH sessions from scribbling over each other's HUD.
     */
    private static volatile String lastDriverSession;
    private static volatile long lastDriverSeenAt;
    private static final AtomicLong DRIVER_ACTIONS = new AtomicLong();

    private HttpServer httpServer;
    private ExecutorService executor;

    public McpHttpServer(String host, int port, String token, ToolCatalog toolCatalog, ModLog log) {
        this.host = host;
        this.port = port;
        this.token = token == null ? "" : token;
        this.toolCatalog = toolCatalog;
        this.log = log;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String endpoint() {
        return "http://" + host + ":" + port + "/mcp";
    }

    /**
     * The port actually bound. Tests start the server on port 0 (let the OS pick) so two test runs
     * can never fight over 8731; the HUD bridge test reads the real port from here.
     */
    int boundPort() {
        HttpServer server = httpServer;
        return server == null ? port : server.getAddress().getPort();
    }

    /**
     * Binds and starts the HTTP server.
     *
     * @throws IOException when the address is already in use (with a clear, non-retried message).
     */
    public void start() throws IOException {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (BindException e) {
            throw new IOException("端口 " + port + "（" + host + "）已被占用，SpireLink 不会自动换端口；"
                    + "请修改 config.properties 里的 port 后重启游戏", e);
        }
        httpServer.createContext("/mcp", new McpHandler());
        httpServer.createContext("/health", new HealthHandler());
        // Off-MCP: one context for both HUD endpoints (longest-prefix match).
        httpServer.createContext("/hud", new HudHandler());
        executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "spirelink-http-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
        httpServer.setExecutor(executor);
        httpServer.start();
        log.info("MCP 服务已启动: " + endpoint() + "（Streamable HTTP）");
        if (token.isEmpty()) {
            log.warn("config token 为空：只监听 " + host + "，请求不做鉴权。");
        } else {
            log.info("已启用 Bearer token 鉴权。");
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        sessions.clear();
        log.info("MCP 服务已停止。");
    }

    // ------------------------------------------------------------------ handlers

    private final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("status", "ok");
                body.addProperty("server", SERVER_NAME);
                body.addProperty("endpoint", "/mcp");
                writeJson(exchange, 200, body);
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * Off-MCP HUD bridge (issue #28). Two plain-JSON routes, neither of them a tool:
     *
     * <ul>
     *   <li>{@code POST /hud/say} — body {@code {"text":"…","ttlMs":12000}} plus the
     *       {@code X-DSH-Session} header: draws the text in the top-left thought block.</li>
     *   <li>{@code GET /hud/driver} — {@code {sessionId,lastSeenAt,actions}}: which DSH session is
     *       driving this game. The tee mirrors only that session's log, so two parallel sessions
     *       can never interleave on the HUD.</li>
     * </ul>
     */
    private final class HudHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                addCors(exchange);
                String method = exchange.getRequestMethod();
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!authorized(exchange)) {
                    log.warn("拒绝未授权的 HUD 请求: " + exchange.getRemoteAddress());
                    // Issue #78: /hud/* is not JSON-RPC, so it must not answer in a JSON-RPC envelope.
                    writePlainError(exchange, 401, "UNAUTHORIZED: token 不匹配");
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                if (HUD_PATH_SAY.equals(path)) {
                    if (!"POST".equalsIgnoreCase(method)) {
                        exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
                        writePlainError(exchange, 405, "请使用 POST " + HUD_PATH_SAY);
                        return;
                    }
                    handleHudSay(exchange);
                    return;
                }
                if (HUD_PATH_DRIVER.equals(path)) {
                    if (!"GET".equalsIgnoreCase(method)) {
                        exchange.getResponseHeaders().set("Allow", "GET, OPTIONS");
                        writePlainError(exchange, 405, "请使用 GET " + HUD_PATH_DRIVER);
                        return;
                    }
                    writeJson(exchange, 200, driverSnapshot());
                    return;
                }
                writePlainError(exchange, 404, "未知 HUD 端点: " + path);
            } catch (BodyTooLargeException tooLarge) {
                // Issue #71: the caller's body was too big - say 413, do not pretend the server broke.
                log.warn("拒绝超大 HUD 请求体: " + tooLarge.getMessage());
                drainQuietly(exchange.getRequestBody());
                try {
                    writePlainError(exchange, 413,
                            bodyTooLargeMessage(tooLarge.limit, tooLarge.size));
                } catch (Throwable ignored) {
                    // response already started
                }
            } catch (Throwable t) {
                log.error("处理 HUD 请求失败", t);
                try {
                    writePlainError(exchange, 500, "内部错误: " + t);
                } catch (Throwable ignored) {
                    // response already started
                }
            } finally {
                exchange.close();
            }
        }
    }

    private void handleHudSay(HttpExchange exchange) throws IOException {
        String body = readBody(exchange.getRequestBody());
        JsonObject result = applyHudSay(body, header(exchange, HEADER_DSH_SESSION));
        boolean failed = result.has("error");
        writeJson(exchange, failed ? 400 : 200, result);
    }

    /**
     * Parses one {@code /hud/say} body and pushes it into the HUD.
     *
     * <p>Kept out of the HTTP handler on purpose: this is the part with the rules (blank text is
     * ignored, TTL defaults, session bookkeeping), and unit tests can call it without a socket.
     *
     * @return the JSON body sent back to the caller
     */
    static JsonObject applyHudSay(String body, String sessionId) {
        JsonObject out = new JsonObject();
        String text = null;
        String session = sessionId;
        String sessionFrom = session == null || session.trim().isEmpty() ? null : "header";
        long ttlMs = AgentHud.DEFAULT_THOUGHT_TTL_MS;
        boolean truncated = false;
        int charsIn = 0;
        try {
            JsonElement parsed = new JsonParser().parse(body == null ? "" : body);
            if (parsed == null || !parsed.isJsonObject()) {
                out.addProperty("error", "需要 JSON 对象：{\"text\":\"…\",\"ttlMs\":12000}");
                return out;
            }
            JsonObject request = parsed.getAsJsonObject();
            if (request.has("text") && !request.get("text").isJsonNull()) {
                JsonElement raw = request.get("text");
                // Issue #78: a non-string used to be dropped silently (shown:false, no explanation).
                if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) {
                    out.addProperty("error", "text 必须是字符串，收到 " + raw
                            + "；对象/数组/数字不会被当成思考显示，请改用 \"text\":\"…\"");
                    out.addProperty("chars", 0);
                    return out;
                }
                text = raw.getAsString();
            }
            if (request.has("ttlMs") && request.get("ttlMs").isJsonPrimitive()) {
                ttlMs = request.get("ttlMs").getAsLong();
            }
            if (sessionFrom == null && request.has("sessionId")
                    && request.get("sessionId").isJsonPrimitive()) {
                session = request.get("sessionId").getAsString();
                sessionFrom = "body";
            }
        } catch (RuntimeException e) {
            // JsonSyntaxException, IllegalStateException (wrong value type), NumberFormatException
            out.addProperty("error", "JSON 解析失败: " + e.getMessage());
            return out;
        }
        if (text != null) {
            charsIn = text.length();
            // Issue #78: the block shows a couple of lines; 100k characters is not a thought, and
            // silently accepting it hid the mistake. Cut it and say so.
            if (charsIn > MAX_SAY_CHARS) {
                text = text.substring(0, MAX_SAY_CHARS);
                truncated = true;
            }
        }
        noteDriver(session);
        boolean shown = AgentHud.thought(text, ttlMs);
        int accepted = text == null ? 0 : text.length();
        // The HUD has its own, much smaller display cap (AgentHud.THOUGHT_MAX_CHARS): without this
        // the caller would think all 2000 characters are on screen when only the head is (issue #78).
        String stored = shown ? AgentHud.thoughtText() : null;
        int shownChars = stored == null ? 0 : stored.length();
        out.addProperty("shown", shown);
        out.addProperty("chars", accepted);
        if (shownChars != accepted) {
            out.addProperty("shown_chars", shownChars);
            out.addProperty("hud_max_chars", AgentHud.THOUGHT_MAX_CHARS);
        }
        if (truncated) {
            out.addProperty("truncated", true);
            out.addProperty("chars_in", charsIn);
            out.addProperty("max_chars", MAX_SAY_CHARS);
        }
        out.addProperty("ttlMs", ttlMs);
        out.add("sessionId", session == null || session.trim().isEmpty()
                ? JsonNull.INSTANCE : new JsonPrimitive(session.trim()));
        out.add("session_source", sessionFrom == null
                ? JsonNull.INSTANCE : new JsonPrimitive(sessionFrom));
        if (text != null && text.indexOf('\uFFFD') >= 0) {
            // The tell-tale of a wrong request encoding: the font has no glyph for U+FFFD, so the
            // block would just look empty/garbled. Say it out loud instead of guessing later.
            out.addProperty("warning", "文本里有替换字符 U+FFFD，多半是请求体编码不对；请按 UTF-8 发送");
        }
        return out;
    }

    /** {@code {sessionId,lastSeenAt,actions}} for {@code GET /hud/driver} (issue #28). */
    static JsonObject driverSnapshot() {
        String session = lastDriverSession;
        JsonObject body = new JsonObject();
        body.add("sessionId", session == null ? JsonNull.INSTANCE : new JsonPrimitive(session));
        body.addProperty("lastSeenAt", lastDriverSeenAt);
        body.addProperty("actions", DRIVER_ACTIONS.get());
        return body;
    }

    /**
     * Remembers {@code sessionId} as the session driving this game.
     *
     * <p>A missing header leaves the <em>name</em> alone but still moves the clock: issue #78 found
     * {@code /hud/driver} answering {@code lastSeenAt:0} forever because every caller that did not
     * send {@code X-DSH-Session} was treated as "nobody drove". {@code lastSeenAt} now means "when we
     * last heard from a driver", {@code sessionId} means "who last named themselves".
     */
    static void noteDriver(String sessionId) {
        if (sessionId != null) {
            String trimmed = sessionId.trim();
            if (!trimmed.isEmpty()) {
                lastDriverSession = trimmed;
            }
        }
        lastDriverSeenAt = System.currentTimeMillis();
    }

    /** One more game interaction from the current driver. */
    static void noteAction() {
        DRIVER_ACTIONS.incrementAndGet();
    }

    /** Test seam: forgets the process-wide driver bookkeeping. */
    static void resetDriverForTest() {
        lastDriverSession = null;
        lastDriverSeenAt = 0L;
        DRIVER_ACTIONS.set(0L);
    }

    private final class McpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                addCors(exchange);
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if (!authorized(exchange)) {
                    log.warn("拒绝未授权请求: " + exchange.getRemoteAddress());
                    AgentHud.push(AgentHud.MSG_UNAUTHORIZED);
                    writeJsonRpcError(exchange, 401, null, -32001, "UNAUTHORIZED: token 不匹配");
                    return;
                }
                if ("POST".equalsIgnoreCase(method)) {
                    handlePost(exchange);
                    return;
                }
                exchange.getResponseHeaders().set("Allow", "POST, DELETE, OPTIONS");
                if ("DELETE".equalsIgnoreCase(method)) {
                    String sessionId = header(exchange, "Mcp-Session-Id");
                    if (sessionId != null) {
                        sessions.remove(sessionId);
                    }
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                if ("GET".equalsIgnoreCase(method)) {
                    // Streamable HTTP: a server may decline the standalone SSE stream with 405.
                    writeJsonRpcError(exchange, 405, null, -32600,
                            "本服务不提供 GET 事件流，请使用 POST " + endpoint());
                    return;
                }
                writeJsonRpcError(exchange, 405, null, -32600, "不支持的方法: " + method);
            } catch (BodyTooLargeException tooLarge) {
                // Issue #71: 413 + a transport-level JSON-RPC error. A 500/-32603 here made agents
                // believe the server was broken and retry the identical 1 MB+ body.
                log.warn("拒绝超大 MCP 请求体: " + tooLarge.getMessage());
                // Swallow the rest of the body first: answering mid-upload makes the client see a
                // connection reset instead of our 413.
                drainQuietly(exchange.getRequestBody());
                try {
                    writeJsonRpcError(exchange, 413, null, -32600,
                            bodyTooLargeMessage(tooLarge.limit, tooLarge.size));
                } catch (Throwable ignored) {
                    // response already started
                }
            } catch (Throwable t) {
                log.error("处理 MCP 请求失败", t);
                try {
                    writeJsonRpcError(exchange, 500, null, -32603, "内部错误: " + t);
                } catch (Throwable ignored) {
                    // response already started
                }
            } finally {
                exchange.close();
            }
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        String body = readBody(exchange.getRequestBody());
        log.info("收到 MCP 请求: " + abbreviate(body));
        JsonElement parsed;
        try {
            parsed = new JsonParser().parse(body);
        } catch (JsonSyntaxException e) {
            writeJsonRpcError(exchange, 400, null, -32700, "解析 JSON 失败: " + e.getMessage());
            return;
        }
        if (parsed == null || !parsed.isJsonObject()) {
            writeJsonRpcError(exchange, 400, null, -32600, "只接受单个 JSON-RPC 对象");
            return;
        }
        JsonObject request = parsed.getAsJsonObject();
        JsonElement id = request.has("id") ? request.get("id") : null;
        JsonElement methodElement = request.get("method");
        if (methodElement == null || !methodElement.isJsonPrimitive()) {
            writeJsonRpcError(exchange, 200, id, -32600, "缺少 method");
            return;
        }
        String method = methodElement.getAsString();

        if ("initialize".equals(method)) {
            String sessionId = UUID.randomUUID().toString();
            sessions.add(sessionId);
            exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
            writeJson(exchange, 200, response(id, initializeResult(request)));
            log.info("MCP 会话建立: " + sessionId);
            AgentHud.push(AgentHud.MSG_CONNECTED);
            return;
        }

        String sessionId = header(exchange, "Mcp-Session-Id");
        if (sessionId != null && !sessions.contains(sessionId)) {
            // 游戏重启后这一步必然发生：会话表在进程内存里，新进程一个都不认识，
            // 而客户端还揣着上个进程发的 id。MCP 允许服务端回 404 让客户端重新 initialize，
            // 但现成的 agent 客户端（含 DSH 的 MCP transport）不重试，于是整局卡在 -32001 上。
            // 这个 server 本来就不在会话上存任何状态（鉴权靠 token，幂等靠 state_version），
            // 所以直接认下这个 id 比把活儿推给客户端更划算：重启对 agent 变成无感。
            sessions.add(sessionId);
            log.warn("未知会话 Mcp-Session-Id，按新会话接受（多半是游戏重启过）: " + sessionId);
            AgentHud.push(AgentHud.MSG_SESSION_LOST);
        }
        if (sessionId != null) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
        }

        if (method.startsWith("notifications/") || id == null || id.isJsonNull()) {
            // JSON-RPC notification: 202 with no body (design: notifications/initialized).
            exchange.sendResponseHeaders(202, -1);
            return;
        }

        if ("ping".equals(method)) {
            writeJson(exchange, 200, response(id, new JsonObject()));
            return;
        }
        if ("tools/list".equals(method)) {
            JsonObject result = new JsonObject();
            result.add("tools", toolCatalog.listTools());
            writeJson(exchange, 200, response(id, result));
            return;
        }
        if ("tools/call".equals(method)) {
            handleToolsCall(exchange, id, request);
            return;
        }
        writeJsonRpcError(exchange, 200, id, -32601, "未知方法: " + method);
    }

    private void handleToolsCall(HttpExchange exchange, JsonElement id, JsonObject request)
            throws IOException {
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") && params.get("name").isJsonPrimitive()
                ? params.get("name").getAsString() : null;
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        if (name == null) {
            writeJsonRpcError(exchange, 200, id, -32602, "tools/call 缺少 params.name");
            return;
        }
        // Whoever calls a tool is "driving" the game; /hud/driver answers from this (issue #28).
        noteDriver(header(exchange, HEADER_DSH_SESSION));
        noteAction();

        ToolCatalog.ToolResult toolResult = toolCatalog.call(name, arguments);
        if (toolResult == null) {
            AgentHud.push(AgentHud.MSG_UNKNOWN_TOOL);
            writeJsonRpcError(exchange, 200, id, -32602, unknownToolMessage(name));
            return;
        }

        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", gson.toJson(toolResult.payload));
        content.add(text);
        result.add("content", content);
        result.add("structuredContent", toolResult.payload);
        if (toolResult.isError) {
            result.addProperty("isError", true);
        }
        writeJson(exchange, 200, response(id, result));
    }

    /**
     * Message for a {@code tools/call} name that is not in the catalogue.
     *
     * <p>Deliberately free of any fixed tool list: the exposed set is 9 base tools, 19 with
     * {@code test_mode} on, and enumerating it in a string literal is exactly how the old text went
     * stale (it still claimed the P1 quartet, issue #54). {@code tools/list} is the only authority,
     * so this sentence points there instead of guessing.
     */
    static String unknownToolMessage(String name) {
        return "未知工具: " + name
                + "（用 tools/list 取当前可用的工具清单，再按里面的 name 调用）";
    }

    private JsonObject initializeResult(JsonObject request) {
        String requested = null;
        if (request.has("params") && request.get("params").isJsonObject()) {
            JsonObject params = request.getAsJsonObject("params");
            if (params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()) {
                requested = params.get("protocolVersion").getAsString();
            }
        }
        String protocol = requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested)
                ? requested : DEFAULT_PROTOCOL_VERSION;

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", protocol);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", spirelink.SpireLink.MOD_VERSION);
        result.add("serverInfo", serverInfo);
        if (requested != null && !requested.equals(protocol)) {
            log.info("客户端请求协议 " + requested + "，本服务回退到 " + protocol);
        }
        return result;
    }

    // ------------------------------------------------------------------ helpers

    private boolean authorized(HttpExchange exchange) {
        if (token.isEmpty()) {
            return true;
        }
        String header = header(exchange, "Authorization");
        return header != null && header.equals("Bearer " + token);
    }

    private static String header(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                "Content-Type, Accept, Mcp-Session-Id, Authorization, " + HEADER_DSH_SESSION);
        exchange.getResponseHeaders().set("Access-Control-Expose-Headers",
                "Mcp-Session-Id, " + HEADER_DSH_SESSION);
    }

    private String readBody(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        byte[] chunk = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > MAX_REQUEST_BYTES) {
                // Typed on purpose: the handlers answer 413 for exactly this (issue #71).
                throw new BodyTooLargeException(MAX_REQUEST_BYTES, total);
            }
            buffer.write(chunk, 0, read);
        }
        Charset[] used = new Charset[1];
        String body = stripBom(decodeBody(buffer.toByteArray(), used));
        if (used[0] != null && !StandardCharsets.UTF_8.equals(used[0])) {
            log.warn("请求体不是合法 UTF-8（中文 Windows 控制台里的 curl 发的是 GBK），已按 "
                    + used[0].name() + " 解码；建议客户端统一按 UTF-8 发送");
        }
        return body;
    }

    /**
     * Charset tried when a body is not valid UTF-8: PowerShell 5 / cmd on a Chinese Windows hands
     * native programs GBK bytes, and decoding those as UTF-8 turned a whole thought into U+FFFD
     * plus random Latin/Greek letters - the mojibake a human saw in the top-left block (#28).
     */
    static final Charset FALLBACK_BODY_CHARSET = Charset.forName("GBK");

    /**
     * Decodes one request body: strict UTF-8 first (the JSON default), GBK second (Chinese Windows
     * console), lossy UTF-8 last so a mangled body can never fail a request.
     *
     * @param used optional one-element out parameter receiving the charset that won
     */
    static String decodeBody(byte[] bytes, Charset[] used) {
        if (bytes == null || bytes.length == 0) {
            if (used != null && used.length > 0) {
                used[0] = StandardCharsets.UTF_8;
            }
            return "";
        }
        try {
            String text = decodeStrict(bytes, StandardCharsets.UTF_8);
            if (used != null && used.length > 0) {
                used[0] = StandardCharsets.UTF_8;
            }
            return text;
        } catch (CharacterCodingException notUtf8) {
            // fall through: the shell sent bytes in the console code page
        }
        try {
            String text = decodeStrict(bytes, FALLBACK_BODY_CHARSET);
            if (used != null && used.length > 0) {
                used[0] = FALLBACK_BODY_CHARSET;
            }
            return text;
        } catch (CharacterCodingException notGbk) {
            if (used != null && used.length > 0) {
                used[0] = StandardCharsets.UTF_8;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    static String decodeBody(byte[] bytes) {
        return decodeBody(bytes, null);
    }

    /** Drops a UTF-8 BOM, which some Windows clients prepend and Gson then refuses to parse. */
    static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    private static String decodeStrict(byte[] bytes, Charset charset)
            throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static JsonObject response(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("result", result);
        return response;
    }

    private void writeJsonRpcError(HttpExchange exchange, int status, JsonElement id, int code,
                                   String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("error", error);
        writeJson(exchange, status, response);
    }

    /**
     * Reads and discards whatever is left of a request body (bounded), so a client that is still
     * uploading can read our answer instead of hitting a connection reset (issue #71).
     */
    private static void drainQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            byte[] chunk = new byte[8192];
            long total = 0L;
            int read;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > 64L * 1024L * 1024L) {
                    // Never read forever on a hostile client.
                    return;
                }
            }
        } catch (Throwable ignored) {
            // best effort: the answer matters more than the drain
        }
    }

    /**
     * The one sentence every oversized-body answer carries: what the limit is, that it is a client
     * error, and what to do instead (issue #71).
     */
    static String bodyTooLargeMessage(long limit, long size) {
        return "请求体超过上限 " + limit + " 字节（" + (limit / 1024) + " KiB）：已读 " + size
                + " 字节就被拒绝了，这条请求没有执行。这是客户端错误（HTTP 413），"
                + "请缩小请求体（分页 / 裁剪样本 / 只发改动的字段）后用新的 id 重发。";
    }

    /**
     * Plain error body for the off-MCP routes ({@code /hud/*}, issue #78): they are not JSON-RPC
     * endpoints, so answering with a JSON-RPC envelope ({@code {jsonrpc,id,error:{code}}}) was a lie
     * about the protocol. {@code {"error":…,"status":…}} is the honest shape here.
     */
    private void writePlainError(HttpExchange exchange, int status, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        body.addProperty("status", status);
        writeJson(exchange, status, body);
    }

    private void writeJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE_JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        try {
            out.write(bytes);
            out.flush();
        } finally {
            out.close();
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }
}
