package spirelink;

import spirelink.util.DataPaths;
import spirelink.util.ModLog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Properties;

/**
 * Loads {@code config.properties} (UTF-8).
 *
 * <p>Lookup order (design 15.1 + compatibility with the earlier draft):
 * <ol>
 *   <li>{@code %LOCALAPPDATA%\\ModTheSpire\\SpireLink\\config.properties} (preferred)</li>
 *   <li>{@code <game dir>/mods/spirelink-data/config.properties} (fallback)</li>
 *   <li>neither exists — built-in defaults, no error and no file is created</li>
 * </ol>
 *
 * <p>When neither location exists a commented template is created at the preferred path
 * ({@code %LOCALAPPDATA%\\ModTheSpire\\SpireLink\\config.properties}) so the keys are discoverable; a
 * failure to write it is logged and the built-in defaults are used.
 *
 * <p>P0 consumes {@code host}, {@code port}, {@code token}, {@code debug_log}. P0.5 adds
 * {@code hud} and {@code hud_lines}. Unknown keys are ignored so later milestones can add keys
 * without breaking this build.
 */
public final class SpireLinkConfig {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 8731;

    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DEBUG_LOG = "debug_log";
    private static final String KEY_HUD = "hud";
    private static final String KEY_HUD_LINES = "hud_lines";
    private static final String KEY_HUD_IDLE_FADE = "hud_idle_fade";
    private static final String KEY_TEST_MODE = "test_mode";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_GAZE_MS = "gaze_ms";
    private static final String KEY_CURSOR = "cursor";
    private static final String KEY_CURSOR_SCALE = "cursor_scale";
    private static final String KEY_NOTES_PANEL = "notes_panel";

    public static final int DEFAULT_HUD_LINES = 4;
    public static final int MIN_HUD_LINES = 1;
    public static final int MAX_HUD_LINES = 6;

    /** Written when no config file exists yet; every key is documented inline. */
    static final String CONFIG_TEMPLATE =
            "# SpireLink 配置（UTF-8，改完重启游戏生效）\n"
            + "# 只允许环回地址；填其它地址会被强制改回 127.0.0.1\n"
            + "host=127.0.0.1\n"
            + "# 端口被占用时模组会明确报错，不会静默换端口\n"
            + "port=8731\n"
            + "# 非空时要求请求带 Authorization: Bearer <token>；留空 = 不做鉴权\n"
            + "token=\n"
            + "# true: 调试信息写入 mods/spirelink-data/spirelink-debug.log\n"
            + "debug_log=true\n"
            + "# true: 游戏左上角显示 Agent 行为小面板（P0.5）\n"
            + "hud=true\n"
            + "# 面板保留几行，范围 1-6（默认 4）\n"
            + "hud_lines=4\n"
            + "# true: agent 闲置时面板自动淡出（8 秒后 55%，20 秒后 30%）\n"
            + "hud_idle_fade=true\n"
            + "# true: 游戏里画一只大肥鱼当指针，跟着 agent 的动作飞（不碰系统鼠标）\n"
            + "cursor=true\n"
            + "# 指针缩放：1.0 = 148px；默认 0.5（约 74px）\n"
            + "cursor_scale=0.5\n"
            + "# true: 主菜单左下角显示「便签（N）」入口，人类只读翻看全部便签（issue #46）\n"
            + "notes_panel=true\n";

    private final File file;
    private final String host;
    private final int port;
    private final String token;
    private final boolean debugLog;
    private final boolean hud;
    private final int hudLines;
    private final boolean hudIdleFade;
    private final boolean cursor;
    private final float cursorScale;
    private final boolean notesPanel;

    private SpireLinkConfig(File file, String host, int port, String token, boolean debugLog,
                            boolean hud, int hudLines, boolean hudIdleFade, boolean cursor,
                            float cursorScale, boolean notesPanel) {
        this.file = file;
        this.host = host;
        this.port = port;
        this.token = token;
        this.debugLog = debugLog;
        this.hud = hud;
        this.hudLines = hudLines;
        this.hudIdleFade = hudIdleFade;
        this.cursor = cursor;
        this.cursorScale = cursorScale;
        this.notesPanel = notesPanel;
    }

    /** The file the values came from, or {@code null} when built-in defaults were used. */
    public File file() {
        return file;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String token() {
        return token;
    }

    public boolean hasToken() {
        return token != null && !token.isEmpty();
    }

    public boolean debugLog() {
        return debugLog;
    }

    /** Whether the in-game agent HUD is drawn (design P0.5). */
    public boolean hud() {
        return hud;
    }

    /** Visible HUD lines, already clamped to 1..6. */
    public int hudLines() {
        return hudLines;
    }

    /** Whether the HUD dims itself while the agent is idle. */
    public boolean hudIdleFade() {
        return hudIdleFade;
    }

    /** True when the fish pointer should be drawn inside a run. */
    public boolean cursor() {
        return cursor;
    }

    /** Sprite scale, clamped to 0.5-2.0. */
    public float cursorScale() {
        return cursorScale;
    }

    /** Whether the human-only, read-only notes panel entry is drawn on the main menu (#46). */
    public boolean notesPanel() {
        return notesPanel;
    }

    public static SpireLinkConfig defaults() {
        return new SpireLinkConfig(null, DEFAULT_HOST, DEFAULT_PORT, "", true, true,
                DEFAULT_HUD_LINES, true, true, 0.5f, true);
    }

    /**
     * @param primary  preferred config file, may be {@code null} or missing.
     * @param fallback second location tried when the primary does not exist.
     */
    public static SpireLinkConfig load(File primary, File fallback, ModLog log) {
        File source = null;
        if (primary != null && primary.isFile()) {
            source = primary;
        } else if (fallback != null && fallback.isFile()) {
            source = fallback;
        }
        if (source == null) {
            if (log != null) {
                log.info("未找到配置文件（" + describe(primary) + " / " + describe(fallback)
                        + "），使用内置默认值 " + DEFAULT_HOST + ":" + DEFAULT_PORT);
            }
            File created = writeTemplate(primary, log);
            if (created != null) {
                source = created;
            } else {
                return defaults();
            }
        }

        Properties props = new Properties();
        try {
            props.load(Files.newBufferedReader(source.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (log != null) {
                log.error("读取配置失败，使用内置默认值: " + source.getAbsolutePath(), e);
            }
            return new SpireLinkConfig(source, DEFAULT_HOST, DEFAULT_PORT, "", true, true,
                    DEFAULT_HUD_LINES, true, true, 0.5f, true);
        }

        String host = trimToNull(props.getProperty(KEY_HOST));
        if (host == null) {
            host = DEFAULT_HOST;
        } else if (!isLoopback(host)) {
            if (log != null) {
                log.warn("配置 host=" + host + " 不是环回地址；按设计只允许本机访问，已强制改为 "
                        + DEFAULT_HOST);
            }
            host = DEFAULT_HOST;
        }

        int port = DEFAULT_PORT;
        String rawPort = trimToNull(props.getProperty(KEY_PORT));
        if (rawPort != null) {
            try {
                int parsed = Integer.parseInt(rawPort);
                if (parsed >= 1 && parsed <= 65535) {
                    port = parsed;
                } else if (log != null) {
                    log.warn("配置 port=" + rawPort + " 超出 1..65535，使用默认值 " + DEFAULT_PORT);
                }
            } catch (NumberFormatException e) {
                if (log != null) {
                    log.warn("配置 port=" + rawPort + " 不是整数，使用默认值 " + DEFAULT_PORT);
                }
            }
        }

        String token = props.getProperty(KEY_TOKEN, "");
        token = token == null ? "" : token.trim();

        boolean debugLog = true;
        String rawDebug = trimToNull(props.getProperty(KEY_DEBUG_LOG));
        if (rawDebug != null) {
            debugLog = parseBool(rawDebug);
        }

        boolean hud = true;
        String rawHud = trimToNull(props.getProperty(KEY_HUD));
        if (rawHud != null) {
            hud = parseBool(rawHud);
        }

        int hudLines = DEFAULT_HUD_LINES;
        String rawHudLines = trimToNull(props.getProperty(KEY_HUD_LINES));
        if (rawHudLines != null) {
            try {
                int parsed = Integer.parseInt(rawHudLines);
                if (parsed >= MIN_HUD_LINES && parsed <= MAX_HUD_LINES) {
                    hudLines = parsed;
                } else if (log != null) {
                    log.warn("配置 hud_lines=" + rawHudLines + " 超出 " + MIN_HUD_LINES + ".."
                            + MAX_HUD_LINES + "，使用默认值 " + DEFAULT_HUD_LINES);
                }
            } catch (NumberFormatException e) {
                if (log != null) {
                    log.warn("配置 hud_lines=" + rawHudLines + " 不是整数，使用默认值 "
                            + DEFAULT_HUD_LINES);
                }
            }
        }

        boolean hudIdleFade = true;
        String rawIdleFade = trimToNull(props.getProperty(KEY_HUD_IDLE_FADE));
        if (rawIdleFade != null) {
            hudIdleFade = parseBool(rawIdleFade);
        }

        boolean cursor = true;
        String rawCursor = trimToNull(props.getProperty(KEY_CURSOR));
        if (rawCursor != null) {
            cursor = parseBool(rawCursor);
        }

        float cursorScale = 0.5f;
        String rawScale = trimToNull(props.getProperty(KEY_CURSOR_SCALE));
        if (rawScale != null) {
            try {
                cursorScale = Math.max(0.15f, Math.min(2.0f, Float.parseFloat(rawScale)));
            } catch (NumberFormatException e) {
                if (log != null) {
                    log.warn("配置 cursor_scale=" + rawScale + " 不是数字，使用 0.5");
                }
            }
        }

        boolean testMode = false;
        String rawTestMode = trimToNull(props.getProperty(KEY_TEST_MODE));
        if (rawTestMode != null) {
            testMode = parseBool(rawTestMode);
        }
        // Environment wins over the file, so automation never has to touch the user's config.
        String envTestMode = System.getenv("SPIRELINK_TEST_MODE");
        if (envTestMode != null && !envTestMode.trim().isEmpty()) {
            boolean fromEnv = parseBool(envTestMode.trim());
            log.info("配置来源: env SPIRELINK_TEST_MODE=" + envTestMode.trim()
                    + "（覆盖文件值 " + testMode + "）");
            testMode = fromEnv;
        }
        spirelink.util.TestMode.setEnabled(testMode);
        float speed = 1.0f;
        String rawSpeed = trimToNull(props.getProperty(KEY_SPEED));
        if (rawSpeed != null) {
            try {
                speed = Float.parseFloat(rawSpeed);
            } catch (NumberFormatException e) {
                log.warn("speed=" + rawSpeed + " 不是数字，回退 1.0");
                speed = 1.0f;
            }
        }
        if (speed < 0.5f || speed > 5.0f) {
            log.warn("speed=" + speed + " 超出 0.5~5.0，已夹取");
            speed = Math.max(0.5f, Math.min(5.0f, speed));
        }
        String envSpeed = System.getenv("SPIRELINK_SPEED");
        if (envSpeed != null && !envSpeed.trim().isEmpty()) {
            try {
                float fromEnv = Float.parseFloat(envSpeed.trim());
                log.info("配置来源: env SPIRELINK_SPEED=" + envSpeed.trim()
                        + "（覆盖文件值 " + speed + "）");
                speed = Math.max(0.5f, Math.min(5.0f, fromEnv));
            } catch (NumberFormatException e) {
                log.warn("SPIRELINK_SPEED=" + envSpeed + " 不是数字，忽略");
            }
        }
        int gazeMs = 2500;
        String rawGaze = trimToNull(props.getProperty(KEY_GAZE_MS));
        if (rawGaze != null) {
            try {
                gazeMs = Integer.parseInt(rawGaze);
            } catch (NumberFormatException e) {
                log.warn("gaze_ms=" + rawGaze + " 不是整数，回退 2500");
            }
        }
        if (gazeMs < 0 || gazeMs > 3000) {
            log.warn("gaze_ms=" + gazeMs + " 超出 0~3000，已夹取");
            gazeMs = Math.max(0, Math.min(3000, gazeMs));
        }
        spirelink.inspect.Inspector.setDelayMs(gazeMs);
        spirelink.util.TestMode.setSpeed(speed);
        boolean notesPanel = true;
        String rawNotesPanel = trimToNull(props.getProperty(KEY_NOTES_PANEL));
        if (rawNotesPanel != null) {
            notesPanel = parseBool(rawNotesPanel);
        }
        spirelink.ui.NotesPanel.configure(notesPanel);
        return new SpireLinkConfig(source, host, port, token, debugLog, hud, hudLines, hudIdleFade,
                cursor, cursorScale, notesPanel);
    }

    /**
     * Writes the commented template so users can discover the keys.
     *
     * @return the created file, or {@code null} when it could not be written
     */
    private static File writeTemplate(File target, ModLog log) {
        if (target == null) {
            return null;
        }
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                if (log != null) {
                    log.warn("无法创建配置目录: " + parent.getAbsolutePath());
                }
                return null;
            }
            Files.write(target.toPath(), CONFIG_TEMPLATE.getBytes(StandardCharsets.UTF_8));
            if (log != null) {
                log.info("已生成配置模板（UTF-8，含注释）: " + target.getAbsolutePath());
            }
            return target;
        } catch (IOException e) {
            if (log != null) {
                log.warn("写配置模板失败，使用内置默认值: " + target.getAbsolutePath());
            }
            return null;
        }
    }

    private static String describe(File file) {
        return file == null ? "null" : file.getAbsolutePath();
    }

    private static boolean parseBool(String raw) {
        String v = raw.toLowerCase(Locale.ROOT);
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v);
    }

    static boolean isLoopback(String host) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h)
                || h.matches("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
