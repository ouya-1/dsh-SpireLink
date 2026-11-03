package spirelink.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Small UTF-8 file logger (design 15.3: every write names the charset, never the platform
 * default — this machine's JVM runs with file.encoding=GBK).
 *
 * <ul>
 *   <li>{@code <game dir>/mods/spirelink-data/spirelink.log} — one raw line per entered stable
 *       state, exactly {@code version=<n> ui=<x> hp=<cur/max> hand_uuids=<csv>}; the smoke test
 *       parses this line, so it carries no timestamp prefix.</li>
 *   <li>{@code spirelink-debug.log} — timestamped INFO/WARN/ERROR, only when
 *       {@code debug_log=true}.</li>
 * </ul>
 *
 * <p>Logging must never break the game: file errors are reported through log4j only.
 */
public final class ModLog {

    private static final Logger LOGGER = LogManager.getLogger("SpireLink");
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";

    private final File dataDir;
    private final boolean debugFileEnabled;
    private final Object fileLock = new Object();

    public ModLog(File dataDir, boolean debugFileEnabled) {
        this.dataDir = dataDir;
        this.debugFileEnabled = debugFileEnabled;
    }

    public File dataDir() {
        return dataDir;
    }

    public boolean debugFileEnabled() {
        return debugFileEnabled;
    }

    public void info(String message) {
        LOGGER.info(message);
        appendDebug("INFO", message, null);
    }

    public void warn(String message) {
        LOGGER.warn(message);
        appendDebug("WARN", message, null);
    }

    public void error(String message, Throwable error) {
        LOGGER.error(message, error);
        appendDebug("ERROR", message, error);
    }

    /** Appends one state line verbatim (UTF-8). Never gated by debug_log. */
    public void stateLine(String line) {
        appendRaw(DataPaths.stateLogFile(dataDir), line);
    }

    private void appendDebug(String level, String message, Throwable error) {
        if (!debugFileEnabled) {
            return;
        }
        StringBuilder sb = new StringBuilder(64 + message.length());
        sb.append(timestamp()).append(' ').append(level).append(' ').append(message);
        if (error != null) {
            sb.append(" | ").append(error.getClass().getName()).append(": ").append(error.getMessage());
        }
        appendRaw(DataPaths.debugLogFile(dataDir), sb.toString());
    }

    private void appendRaw(File file, String line) {
        if (file == null) {
            return;
        }
        synchronized (fileLock) {
            try {
                DataPaths.rotateIfNeeded(file);
                Path path = file.toPath();
                Files.write(path, (line + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOGGER.error("无法写入日志 " + file.getAbsolutePath(), e);
            }
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat(TIME_PATTERN, Locale.ROOT).format(new Date());
    }
}
