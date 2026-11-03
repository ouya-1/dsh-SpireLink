package spirelink;

import basemod.BaseMod;
import basemod.interfaces.PostUpdateSubscriber;
import basemod.interfaces.PreUpdateSubscriber;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import spirelink.mcp.MainThreadQueue;
import spirelink.mcp.McpHttpServer;
import spirelink.mcp.ToolCatalog;
import spirelink.state.StateBuilder;
import spirelink.ui.AgentHud;
import spirelink.util.DataPaths;
import spirelink.util.ModLog;

import java.io.File;
import java.io.IOException;

/**
 * Mod entry point (design 16): read config, start the MCP server, hook the game loop, log.
 *
 * <p>Main-thread discipline (design 4): {@link #receivePostUpdate()} is called by BaseMod every
 * frame, so it is the only place that drains the {@link MainThreadQueue} and where
 * {@link StateBuilder} may touch {@code AbstractDungeon}. HTTP threads never call into the game.
 */
@SpireInitializer
public final class SpireLink implements PostUpdateSubscriber, PreUpdateSubscriber {

    public static final String MOD_ID = "spirelink";
    public static final String MOD_NAME = "SpireLink";
    public static final String MOD_VERSION = "0.1.4";

    private static SpireLink instance;

    private final ModLog log;
    private final SpireLinkConfig config;
    private final MainThreadQueue queue;
    private final StateBuilder stateBuilder;
    private final McpHttpServer server;

    public SpireLink() {
        // Bootstrap log first: it has no config dependency and lets failures be visible.
        File dataDir = DataPaths.resolveDataDir(SpireLink.class);
        ModLog bootstrapLog = new ModLog(dataDir, false);
        this.config = SpireLinkConfig.load(DataPaths.modConfigFile(),
                DataPaths.configFile(dataDir), bootstrapLog);
        this.log = new ModLog(dataDir, config.debugLog());
        spirelink.util.Diag.setLog(log);

        // HUD first: the MCP layer may push lines as soon as the server starts.
        AgentHud.configure(config.hud(), config.hudLines(), config.hudIdleFade());
        spirelink.ui.AgentCursor.configure(config.cursor(), config.cursorScale());
        spirelink.util.TestMode.applySpeed();

        this.queue = new MainThreadQueue();
        this.stateBuilder = new StateBuilder(log, MOD_VERSION);
        ToolCatalog toolCatalog = new ToolCatalog(queue, stateBuilder, log);
        this.server = startServer(toolCatalog);

        log.info("SpireLink " + MOD_VERSION + " 初始化：数据目录=" + dataDir.getAbsolutePath());
        log.info("配置文件=" + (config.file() == null ? "（未找到，使用默认值）"
                : config.file().getAbsolutePath()));
        log.info("语言=" + com.megacrit.cardcrawl.core.Settings.language
                + "，HTTP 编码=UTF-8，日志编码=UTF-8");
        log.info("Agent HUD=" + (config.hud() ? "开" : "关") + "，行数=" + config.hudLines()
                + "，闲置淡出=" + (config.hudIdleFade() ? "开" : "关"));
        log.info("大肥鱼指针=" + (config.cursor() ? "开" : "关") + "，缩放=" + config.cursorScale());

        BaseMod.subscribe(this);
        instance = this;
    }

    public static void initialize() {
        spirelink.util.CrashGuard.installHandlers();
        instance = new SpireLink();
    }

    private McpHttpServer startServer(ToolCatalog toolCatalog) {
        McpHttpServer httpServer = new McpHttpServer(config.host(), config.port(),
                config.token(), toolCatalog, log);
        try {
            httpServer.start();
            return httpServer;
        } catch (IOException e) {
            // Port in use (or another bind failure): report loudly, do not retry on another port.
            log.error("MCP 服务启动失败: " + e.getMessage(), e);
            return null;
        }
    }

    @Override
    public void receivePreUpdate() {
        spirelink.util.RuntimeProbe.beat();
        try {
            queue.drainOnMainThread();
        } catch (Throwable t) {
            log.error("主线程队列 drain（PreUpdate）失败", t);
        }
    }

    @Override
    public void receivePostUpdate() {
        spirelink.util.RuntimeProbe.tick();
        spirelink.util.TestMode.tick();
        // Nothing from this mod may escape into CardCrawlGame.update(): an exception there kills the
        // render loop and the window just disappears without a crash log.
        try {
            spirelink.util.VirtualMouse.tick();
            spirelink.ui.DeckPeek.tick();
            spirelink.actions.DelayedActions.tick();
            queue.drainOnMainThread();
            stateBuilder.tickOnMainThread();
        } catch (Throwable t) {
            spirelink.util.CrashGuard.report("receivePostUpdate", t);
        }
    }

    /** Called from the CardCrawlGame.dispose patch when the game shuts down. */
    public static void dispose() {
        // Do NOT stop the HTTP server here. CardCrawlGame.dispose() also runs when the render loop
        // dies (crash screen), and stopping the server took the agent's only channel with it: a
        // crashed game must still answer get_state / act(NOT_READY). The OS closes the socket when the
        // JVM really exits, so keeping it alive costs nothing.
        SpireLink current = instance;
        if (current != null) {
            current.log.info("CardCrawlGame.dispose 被调用：保留 MCP 服务（崩溃时 agent 仍需可连）");
        }
    }

    public McpHttpServer server() {
        return server;
    }

    public StateBuilder stateBuilder() {
        return stateBuilder;
    }

    /**
     * The one state cache, for patches that change what {@code get_state} returns without the game
     * state itself moving - the notes panel's human-only "清空全部" is the case (issue #47):
     * {@code notes_total} / {@code recent_notes} must drop to zero on the very next read instead of
     * serving the snapshot from before the reset. {@code null} before {@link #initialize()} ran.
     */
    public static StateBuilder stateBuilderOrNull() {
        SpireLink current = instance;
        return current == null ? null : current.stateBuilder;
    }
}
