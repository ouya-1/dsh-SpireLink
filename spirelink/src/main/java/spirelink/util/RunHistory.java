package spirelink.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;

/**
 * Cross-run history: one record per finished run, persisted to {@code spirelink-data/history.json}.
 *
 * <p>This is what lets an agent "get better like a human" instead of starting from zero every run:
 * the next run can read how the previous ones ended.
 */
public final class RunHistory {

    private static final String FILE_NAME = "history.json";
    private static final int MAX_RECORDS = 100;

    private static JsonArray runs;

    private RunHistory() {
    }

    private static File file() {
        return new File(DataPaths.resolveDataDir(RunHistory.class), FILE_NAME);
    }

    private static synchronized void ensureLoaded() {
        if (runs != null) {
            return;
        }
        runs = new JsonArray();
        File f = file();
        if (!f.isFile() || f.length() == 0) {
            return;
        }
        try {
            com.google.gson.JsonElement parsed = new com.google.gson.JsonParser().parse(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f),
                            java.nio.charset.Charset.forName("UTF-8")));
            if (parsed != null && parsed.isJsonObject() && parsed.getAsJsonObject().has("runs")
                    && parsed.getAsJsonObject().get("runs").isJsonArray()) {
                runs = parsed.getAsJsonObject().getAsJsonArray("runs");
            }
        } catch (Throwable t) {
            Diag.warn("读取 history.json 失败，按空历史处理: " + t);
            runs = new JsonArray();
        }
    }

    /** All recorded runs, oldest first (copy). */
    public static synchronized JsonArray records() {
        ensureLoaded();
        // Old Gson: no public deepCopy, round-trip through JSON instead.
        return new com.google.gson.JsonParser().parse(runs.toString()).getAsJsonArray();
    }

    public static synchronized int count() {
        ensureLoaded();
        return runs.size();
    }

    /** Appends one finished run and persists immediately. */
    public static synchronized void append(JsonObject record) {
        ensureLoaded();
        if (record == null) {
            return;
        }
        while (runs.size() >= MAX_RECORDS) {
            runs.remove(0);
        }
        runs.add(record);
        flush();
    }

    private static void flush() {
        JsonObject root = new JsonObject();
        root.add("runs", runs);
        File target = file();
        File tmp = new File(target.getParentFile(), FILE_NAME + ".tmp");
        java.io.Writer writer = null;
        try {
            writer = new java.io.OutputStreamWriter(new java.io.FileOutputStream(tmp),
                    java.nio.charset.Charset.forName("UTF-8"));
            writer.write(root.toString());
            writer.close();
            writer = null;
            if (target.exists() && !target.delete()) {
                throw new IllegalStateException("无法覆盖 history.json");
            }
            if (!tmp.renameTo(target)) {
                throw new IllegalStateException("无法重命名 history.json");
            }
        } catch (Throwable t) {
            Diag.warn("写 history.json 失败: " + t);
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
}
