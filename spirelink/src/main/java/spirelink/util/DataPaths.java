package spirelink.util;

import java.io.File;
import java.net.URI;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Resolves the on-disk data directory of the mod.
 *
 * <p>Design doc 15: data lives in {@code <game dir>/mods/spirelink-data/}. Because the mod jar
 * itself is loaded from {@code mods/SpireLink.jar}, the jar location is the most reliable anchor:
 * it works no matter what the process working directory is.
 */
public final class DataPaths {

    public static final String DATA_DIR_NAME = "spirelink-data";
    public static final String CONFIG_FILE_NAME = "config.properties";
    public static final String STATE_LOG_FILE_NAME = "spirelink.log";
    public static final String DEBUG_LOG_FILE_NAME = "spirelink-debug.log";

    private DataPaths() {
    }

    /**
     * @param anchor class living in the mod jar (used to find the jar).
     * @return the data directory, created if needed. Never {@code null}.
     */
    public static File resolveDataDir(Class<?> anchor) {
        File modsDir = modsDirFromCodeSource(anchor);
        File dataDir = modsDir != null ? new File(modsDir, DATA_DIR_NAME) : devFallbackDataDir();
        ensureDir(dataDir);
        return dataDir;
    }

    private static File modsDirFromCodeSource(Class<?> anchor) {
        if (anchor == null) {
            return null;
        }
        try {
            ProtectionDomain domain = anchor.getProtectionDomain();
            if (domain == null) {
                return null;
            }
            CodeSource source = domain.getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            URI uri = source.getLocation().toURI();
            File location = new File(uri);
            if (location.isFile() && location.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                return location.getParentFile();
            }
        } catch (Throwable ignored) {
            // fall through to the working-directory based fallback
        }
        return null;
    }

    private static File devFallbackDataDir() {
        File userDir = new File(System.getProperty("user.dir", "."));
        File[] candidates = {
                new File(userDir, "mods"),
                new File(userDir, "../mods")
        };
        for (File mods : candidates) {
            if (mods.isDirectory()) {
                return new File(mods, DATA_DIR_NAME);
            }
        }
        return new File(new File(userDir, "mods"), DATA_DIR_NAME);
    }

    /**
     * Mod config directory per design 15.1: {@code %LOCALAPPDATA%\\ModTheSpire\\SpireLink}.
     */
    public static File modConfigDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        File base;
        if (localAppData != null && !localAppData.trim().isEmpty()) {
            base = new File(localAppData.trim());
        } else {
            base = new File(new File(System.getProperty("user.home", ".")), "AppData/Local");
        }
        return new File(new File(base, "ModTheSpire"), "SpireLink");
    }

    /** Primary config file location (design 15.1). */
    public static File modConfigFile() {
        return new File(modConfigDir(), CONFIG_FILE_NAME);
    }

    public static File ensureDir(File dir) {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            // Nothing better to do than hand back the path; callers log the failure.
            return dir;
        }
        return dir;
    }

    public static File configFile(File dataDir) {
        return new File(dataDir, CONFIG_FILE_NAME);
    }

    public static File stateLogFile(File dataDir) {
        return new File(dataDir, STATE_LOG_FILE_NAME);
    }

    /** One debug log may grow to this before it is rotated to {@code .1}. */
    public static final long MAX_LOG_BYTES = 8L * 1024L * 1024L;

    /**
     * Keeps logs from eating the user's disk: once a file passes {@link #MAX_LOG_BYTES} it is moved to
     * {@code <name>.1} (replacing the previous one), so at most two files exist per log.
     */
    public static void rotateIfNeeded(File file) {
        if (file == null || !file.isFile() || file.length() <= MAX_LOG_BYTES) {
            return;
        }
        try {
            File old = new File(file.getParentFile(), file.getName() + ".1");
            if (old.exists() && !old.delete()) {
                // keep going: a failed delete must not block the current write
            }
            if (!file.renameTo(old)) {
                // fall back to truncating, still better than a runaway file
                java.nio.file.Files.write(file.toPath(), new byte[0],
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (Throwable ignored) {
            // logging must never fail the caller
        }
    }

    public static File debugLogFile(File dataDir) {
        return new File(dataDir, DEBUG_LOG_FILE_NAME);
    }
}
