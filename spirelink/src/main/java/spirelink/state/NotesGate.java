package spirelink.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import spirelink.util.Notes;

import java.util.HashMap;
import java.util.Map;

/**
 * 0.1.7: 一局结束后，"回主菜单"之前必须先写复盘便签。
 *
 * <p>现场数据：23 局 0 胜，便签 24 条（约 1 条/局），而最近 4 条全是 {@code monster:*} ——
 * 死在谁手上就记谁，宏观规则（路线、血线、药水）五个小时没更新过。
 *
 * <p>所以死亡/胜利那一屏不再给 {@code proceed}：先把两条写下来 —— 一条
 * {@code monster:<杀死你的那只>}，一条 {@code general:<任意>} —— 才放行。
 *
 * <p>判定不看时间戳，而是比对"这一屏出现那一刻的便签版本"：{@link Notes#set} 保证版本号
 * 单调递增（同一秒重复写也会 +1），所以只要键的版本变了，就是这一屏写的新内容。用时间戳
 * 判定会被秒级精度坑到 —— 死亡和写入经常落在同一秒。
 *
 * <p>不会死锁：{@code note_set} 是常驻工具，任何时候都能调；欠哪几条就写在
 * {@code hints.notes_pending} 里。
 */
public final class NotesGate {

    /** 拦截中为 true。 */
    private static boolean armed;

    /** 这局杀死你的那只怪（可能是 null：胜利、或读不到）。 */
    private static String killer;

    /** 拦截开始那一刻所有便签的版本号。 */
    private static final Map<String, Long> baseline = new HashMap<String, Long>();

    private NotesGate() {
    }

    /** 死亡/胜利屏出现时调一次；重复调用不会重置基准。 */
    public static synchronized void arm(String killerId) {
        if (armed) {
            return;
        }
        killer = killerId;
        baseline.clear();
        baseline.putAll(versions());
        armed = true;
    }

    public static synchronized void disarm() {
        armed = false;
        killer = null;
        baseline.clear();
    }

    public static synchronized boolean armed() {
        return armed;
    }

    /** 还欠哪几条；空列表 = 可以走了（顺手解除拦截）。 */
    public static java.util.List<String> pending() {
        java.util.List<String> missing = new java.util.ArrayList<String>();
        String killedBy;
        Map<String, Long> before;
        synchronized (NotesGate.class) {
            if (!armed) {
                return missing;
            }
            killedBy = killer;
            before = new HashMap<String, Long>(baseline);
        }
        Map<String, Long> now = versions();
        if (killedBy != null && !killedBy.isEmpty()
                && !changed("monster:" + killedBy, before, now)) {
            missing.add("monster:" + killedBy);
        }
        // 第二条写哪一类都算：全局（global/general，旧名）或本角色（character:<角色名>）。
        if (!anyChanged("general:", before, now) && !anyChanged("global:", before, now)
                && !anyChanged("character:", before, now)) {
            missing.add("global:<任意 id> 或 character:<角色名>");
        }
        if (missing.isEmpty()) {
            disarm();
        }
        return missing;
    }

    private static boolean changed(String key, Map<String, Long> before, Map<String, Long> now) {
        Long current = now.get(key);
        return current != null && !current.equals(before.get(key));
    }

    private static boolean anyChanged(String prefix, Map<String, Long> before,
                                      Map<String, Long> now) {
        for (Map.Entry<String, Long> e : now.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            if (!e.getValue().equals(before.get(e.getKey()))) {
                return true;
            }
        }
        return false;
    }

    /** key -> updated_at 的全部便签版本。 */
    private static Map<String, Long> versions() {
        Map<String, Long> out = new HashMap<String, Long>();
        JsonObject all = Notes.list(null);
        for (Map.Entry<String, JsonElement> e : all.entrySet()) {
            if (e.getValue() == null || !e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject entry = e.getValue().getAsJsonObject();
            out.put(e.getKey(), entry.has("updated_at") ? entry.get("updated_at").getAsLong() : 0L);
        }
        return out;
    }
}
