package spirelink.actions;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * One-shot delayed card actions. The gaze animation needs ~0.7 s before the card actually leaves
 * the hand; blocking the MCP thread or the render loop for that is not acceptable, so the action is
 * re-queued and fired from the per-frame main-thread tick instead.
 */
public final class DelayedActions {

    private static final List<Entry> PENDING = new ArrayList<Entry>();
    private static boolean replaying;

    private DelayedActions() {
    }

    private static final class Entry {
        private final long dueAt;
        private final String type;
        private final JsonObject payload;

        private Entry(long dueAt, String type, JsonObject payload) {
            this.dueAt = dueAt;
            this.type = type;
            this.payload = payload;
        }
    }

    public static synchronized void schedule(long delayMs, String type, JsonObject payload) {
        PENDING.add(new Entry(System.currentTimeMillis() + delayMs, type, payload));
    }

    public static synchronized boolean hasPending() {
        return !PENDING.isEmpty();
    }

    /** True while we are executing a delayed action, so it is not delayed again. */
    public static boolean replaying() {
        return replaying;
    }

    /** Main thread, once per frame. */
    public static void tick() {
        Entry due = null;
        synchronized (DelayedActions.class) {
            long now = System.currentTimeMillis();
            Iterator<Entry> it = PENDING.iterator();
            while (it.hasNext()) {
                Entry entry = it.next();
                if (entry.dueAt <= now) {
                    due = entry;
                    it.remove();
                    break;
                }
            }
        }
        if (due == null) {
            return;
        }
        replaying = true;
        try {
            ActionExecutor.execute(due.type, due.payload);
        } catch (Throwable t) {
            spirelink.util.Diag.warn("延迟动作执行失败: " + t);
        } finally {
            replaying = false;
        }
    }
}
