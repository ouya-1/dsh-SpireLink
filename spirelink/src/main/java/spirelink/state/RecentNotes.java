package spirelink.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import spirelink.util.NotesDedupe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code memory.recent_notes} 的纯逻辑部分：按 updated_at 倒序、每局每个版本只推一次、最多 8 条。
 *
 * <p>主菜单/无房间路径（{@code StateBuilder.buildRecentNotes}）与正常路径
 * （{@code StateBuilder.buildMemory}）共用这一份实现 —— 分成两份时无房间路径忘了去重，
 * 每次 get_state 都把同样的笔记再推一遍（issue #3）。
 *
 * <p>去重键是 {@code kind:id@updated_at}，窗口由 {@link NotesDedupe} 按局维护：
 * 同一局同一版本只出现一次，笔记改了（updated_at 变化）会重新出现，
 * {@code get_state{force_notes:true}} 时 NotesDedupe 全量放行。
 *
 * <p>只依赖 gson 与 NotesDedupe，不碰游戏类，所以可以直接单测。
 */
final class RecentNotes {

    /** 每次 get_state 最多推几条：再多就是纯噪声。 */
    static final int MAX_ITEMS = 8;

    private RecentNotes() {
    }

    /** 同 {@link #build(JsonObject, JsonObject, boolean)}，默认「claim」——用于真正发出去的响应。 */
    static JsonArray build(JsonObject all, JsonObject countsOut) {
        return build(all, countsOut, true);
    }

    /**
     * @param all        {@code Notes.list(null)} 的结果（key = {@code kind:id}）
     * @param countsOut  可选：按 kind 统计<b>全部</b>笔记条数（含被去重和超过 8 条的），可为 {@code null}
     * @param claim      true = 走 {@link NotesDedupe#claim}（同一局同一版本只出一次）；
     *                   false = 只排序取前 8 条，不做去重（构建 body 时用，真正发响应时会重建一次）
     * @return 可以直接塞进响应 {@code recent_notes} 的数组
     */
    static JsonArray build(JsonObject all, JsonObject countsOut, boolean claim) {
        JsonArray recent = new JsonArray();
        if (all == null) {
            return recent;
        }
        List<Map.Entry<String, JsonElement>> entries =
                new ArrayList<Map.Entry<String, JsonElement>>(all.entrySet());
        entries.sort((a, b) -> Long.compare(updatedAtOf(b.getValue()), updatedAtOf(a.getValue())));
        Map<String, Integer> byKind = new LinkedHashMap<String, Integer>();
        // 计数要覆盖全部笔记（含本轮被省略的），所以先单独统计一遍。
        for (Map.Entry<String, JsonElement> entry : entries) {
            String key = entry.getKey();
            int split = key.indexOf(':');
            String kind = split > 0 ? key.substring(0, split) : "general";
            byKind.merge(kind, 1, Integer::sum);
        }
        // 只看「最新的 MAX_ITEMS 条」：被去重掉的不再往后找老的补位。
        // 否则连续 5 次 get_state 会一批一批把 50 条笔记全推一遍（2026-11-02 实测：8/8/8/8/8）。
        int considered = 0;
        for (Map.Entry<String, JsonElement> entry : entries) {
            if (considered >= MAX_ITEMS) {
                break;
            }
            considered++;
            String key = entry.getKey();
            int split = key.indexOf(':');
            String kind = split > 0 ? key.substring(0, split) : "general";
            String text = textOf(entry.getValue());
            if (text == null) {
                continue;
            }
            long updatedAt = updatedAtOf(entry.getValue());
            // 同一局同一版本只推一次；force_notes 时 NotesDedupe 直接放行。
            // 只有「真正要发出去」的那一次才 claim：body 可能是缓存快照，构建时 claim 会白丢。
            if (claim && !NotesDedupe.claim(key + "@" + updatedAt, text)) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("kind", kind);
            item.addProperty("id", split > 0 ? key.substring(split + 1) : key);
            item.addProperty("text", text);
            item.addProperty("updated_at", updatedAt);
            recent.add(item);
        }
        if (countsOut != null) {
            for (Map.Entry<String, Integer> entry : byKind.entrySet()) {
                countsOut.addProperty(entry.getKey(), entry.getValue().intValue());
            }
        }
        return recent;
    }

    /** 单条笔记的正文；结构不对或为空时返回 null（跳过而不是整块失败）。 */
    private static String textOf(JsonElement element) {
        try {
            JsonObject object = element.getAsJsonObject();
            if (!object.has("text") || object.get("text").isJsonNull()) {
                return null;
            }
            String text = object.get("text").getAsString();
            return text == null || text.trim().isEmpty() ? null : text;
        } catch (Throwable t) {
            return null;
        }
    }

    private static long updatedAtOf(JsonElement element) {
        try {
            JsonObject object = element.getAsJsonObject();
            return object.has("updated_at") ? object.get("updated_at").getAsLong() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }
}
