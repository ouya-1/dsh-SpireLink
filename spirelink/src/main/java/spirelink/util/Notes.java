package spirelink.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One note store for every kind of object the agent meets: event / potion / card / monster / relic /
 * general. Keyed kind:id, loaded once into memory, written atomically to notes.json.
 *
 * <p>Two kinds of writer: {@link #set(String, String, String)} is the agent's (one key at a time,
 * through {@code note_set}), and {@link #clearAll()} is the human's, reachable only from the
 * in-game notes panel's "清空全部" button - it has no MCP counterpart and no batch mode.</p>
 */
public final class Notes {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String FILE_NAME = "notes.json";
    private static final int MAX_NOTE_CHARS = 2000;
    private static final long MAX_FILE_BYTES = 256L * 1024L;

    /** Backup suffix pattern of the human-only "clear all" (issue #47). */
    public static final String BACKUP_PREFIX = FILE_NAME + ".bak-";
    /** {@code notes.json.bak-<yyyyMMdd-HHmmss>}. */
    public static final String BACKUP_TIME_PATTERN = "yyyyMMdd-HHmmss";

    private static final Map<String, String> TEXTS = new LinkedHashMap<String, String>();
    private static final Map<String, Long> UPDATED = new LinkedHashMap<String, Long>();
    private static boolean loaded;

    /** Test hook: when set, every read and write goes here instead of the real data directory. */
    private static File testDir;

    private Notes() {
    }

    public static String key(String kind, String id) {
        return (kind == null ? "general" : kind) + ":" + (id == null ? "" : id);
    }

    private static File file() {
        File dir = testDir;
        if (dir != null) {
            return new File(dir, FILE_NAME);
        }
        return new File(DataPaths.resolveDataDir(Notes.class), FILE_NAME);
    }

    /**
     * Test hook: points the store at {@code dir} (a JUnit temporary folder) so a unit test can
     * exercise the real file code without ever touching the human's {@code notes.json}.
     * {@code null} restores the normal data directory.
     *
     * <p>Issue #125: public so tests outside {@code spirelink.util} (e.g. {@code state.NoteShapeTest},
     * which exercises {@code NoteAnnotator} against a real store) can isolate the file too.</p>
     */
    public static synchronized void useDirectoryForTests(File dir) {
        testDir = dir;
        TEXTS.clear();
        UPDATED.clear();
        loaded = false;
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        File f = file();
        if (!f.isFile() || f.length() == 0) {
            return;
        }
        if (f.length() > MAX_FILE_BYTES) {
            Diag.warn("notes.json 超过 256KB，已忽略并备份");
            quarantine(f);
            return;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            com.google.gson.JsonElement parsed =
                    new JsonParser().parse(new InputStreamReader(in, UTF8));
            if (parsed == null || !parsed.isJsonObject()) {
                quarantine(f);
                return;
            }
            for (Map.Entry<String, com.google.gson.JsonElement> e
                    : parsed.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject entry = e.getValue().getAsJsonObject();
                if (!entry.has("text") || entry.get("text").isJsonNull()) {
                    continue;
                }
                TEXTS.put(e.getKey(), entry.get("text").getAsString());
                UPDATED.put(e.getKey(), entry.has("updated_at")
                        ? entry.get("updated_at").getAsLong() : 0L);
            }
        } catch (Throwable t) {
            Diag.warn("读取 notes.json 失败，已备份重建: " + t);
            TEXTS.clear();
            UPDATED.clear();
            quarantine(f);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // nothing
                }
            }
        }
    }

    private static void quarantine(File f) {
        try {
            File bad = new File(f.getParentFile(), FILE_NAME + ".bad");
            if (bad.exists()) {
                bad.delete();
            }
            f.renameTo(bad);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** Note text for one object, or null. */
    public static String get(String kind, String id) {
        ensureLoaded();
        return TEXTS.get(key(kind, id));
    }

    public static long updatedAt(String kind, String id) {
        ensureLoaded();
        Long value = UPDATED.get(key(kind, id));
        return value == null ? 0L : value.longValue();
    }

    /** Writes (or clears, when text is blank) one note. Throws IllegalStateException on IO error. */
    public static String set(String kind, String id, String text) {
        ensureLoaded();
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("缺少 id");
        }
        String k = key(kind, id);
        String value = text == null ? "" : text;
        if (value.length() > MAX_NOTE_CHARS) {
            value = value.substring(0, MAX_NOTE_CHARS);
        }
        if (value.trim().isEmpty()) {
            TEXTS.remove(k);
            UPDATED.remove(k);
            flush();
            return null;
        }
        TEXTS.put(k, value);
        // Issue #127: updated_at is the note's *version* (the delivery dedupe keys on it), so an edit
        // must always bump it - two writes inside the same second would otherwise look like the same
        // version and the new text would never be delivered.
        Long previous = UPDATED.get(k);
        long now = System.currentTimeMillis() / 1000L;
        UPDATED.put(k, previous == null || now > previous.longValue() ? now : previous.longValue() + 1L);
        flush();
        return value;
    }

    /** All notes, optionally filtered by kind. */
    public static JsonObject list(String kindFilter) {
        ensureLoaded();
        JsonObject out = new JsonObject();
        for (Map.Entry<String, String> e : TEXTS.entrySet()) {
            String k = e.getKey();
            if (kindFilter != null && !kindFilter.isEmpty() && !k.startsWith(kindFilter + ":")) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("text", e.getValue());
            Long updated = UPDATED.get(k);
            entry.addProperty("updated_at", updated == null ? 0L : updated.longValue());
            out.add(k, entry);
        }
        return out;
    }

    // ---- human-only full reset (issue #47) ----

    /**
     * Wipes every note (all six kinds) and writes {@code {}}. <b>Human interface only:</b> the
     * caller is the "清空全部" button of the in-game notes panel; no MCP tool maps onto this method,
     * so an agent can never reach it - {@code note_set} still deletes a single key and nothing else.
     *
     * <p>Order matters: the current {@code notes.json} is copied to
     * {@code notes.json.bak-<yyyyMMdd-HHmmss>} in the same directory <em>first</em>. If that copy
     * fails the reset is abandoned ({@link IllegalStateException}) rather than destroying the only
     * copy of the notes, so "回头还能后悔" always holds.</p>
     *
     * @return how many notes were wiped (0 for an already empty store, which also skips the backup:
     *         there is nothing to recover)
     */
    public static synchronized int clearAll() {
        ensureLoaded();
        int count = TEXTS.size();
        File backupFile = null;
        if (count > 0) {
            backupFile = backup(file());
        }
        TEXTS.clear();
        UPDATED.clear();
        flush();
        if (count > 0) {
            // Fixed shape, grep-able by the human acceptance run:
            // 便签已全部清空（人类操作）：55 条 → 0，备份 notes.json.bak-20261103-021500
            Diag.info("便签已全部清空（人类操作）：" + count + " 条 → 0，备份 "
                    + (backupFile == null ? "（无，notes.json 不存在）" : backupFile.getName()));
        } else {
            Diag.info("便签已全部清空（人类操作）：0 条 → 0，无需备份");
        }
        return count;
    }

    /**
     * Copies {@code source} next to itself as {@code notes.json.bak-yyyyMMdd-HHmmss}. A second
     * reset inside the same second must not overwrite the first backup, so the suffix then grows
     * {@code -2}, {@code -3}, ... (the timestamped base name stays exactly as documented).
     *
     * @return the backup file, or {@code null} when there was nothing on disk to back up
     * @throws IllegalStateException when the copy failed
     */
    static File backup(File source) {
        if (source == null || !source.isFile()) {
            return null;
        }
        String base = BACKUP_PREFIX + timestamp(System.currentTimeMillis());
        File target = new File(source.getParentFile(), base);
        for (int suffix = 2; target.exists(); suffix++) {
            target = new File(source.getParentFile(), base + "-" + suffix);
        }
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(target);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return target;
        } catch (Throwable t) {
            Diag.warn("备份 notes.json 失败，已取消清空: " + t);
            throw new IllegalStateException("备份 notes.json 失败: " + t);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** {@code notes.json.bak-} suffix for one instant, e.g. {@code 20261103-021500}. */
    static String timestamp(long millis) {
        return new SimpleDateFormat(BACKUP_TIME_PATTERN, Locale.ROOT).format(new Date(millis));
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // nothing
        }
    }

    private static synchronized void flush() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, String> e : TEXTS.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("text", e.getValue());
            Long updated = UPDATED.get(e.getKey());
            entry.addProperty("updated_at", updated == null ? 0L : updated.longValue());
            root.add(e.getKey(), entry);
        }
        File target = file();
        File tmp = new File(target.getParentFile(), FILE_NAME + ".tmp");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new java.io.FileOutputStream(tmp), UTF8);
            writer.write(root.toString());
            writer.close();
            writer = null;
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("无法覆盖 notes.json");
            }
            if (!tmp.renameTo(target)) {
                throw new IllegalStateException("无法重命名 notes.json.tmp");
            }
        } catch (Throwable t) {
            Diag.warn("保存 notes.json 失败: " + t);
            throw new IllegalStateException("保存便签失败: " + t);
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                    // nothing
                }
            }
        }
    }

    /** Test hook. */
    public static synchronized void resetForTests() {
        TEXTS.clear();
        UPDATED.clear();
        loaded = false;
    }
}
