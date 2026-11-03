package spirelink.actions;

import com.google.gson.JsonObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code action_id} idempotency cache (design 5 / review R6): the first response for an id is
 * remembered and replayed on retry, so a duplicated {@code act} never plays a card twice.
 *
 * <p>Pure in-memory bookkeeping: no game classes, unit testable.
 */
public final class IdempotencyStore {

    /** Keeps memory bounded; a harness at 2 actions/second never reaches this. */
    public static final int MAX_ENTRIES = 64;

    private final Map<String, JsonObject> results =
            new LinkedHashMap<String, JsonObject>(16, 0.75f, false);

    /** @return a copy of the first result for this action id, or {@code null}. */
    public synchronized JsonObject get(String actionId) {
        if (actionId == null || actionId.isEmpty()) {
            return null;
        }
        JsonObject hit = results.get(actionId);
        return hit == null ? null : copy(hit);
    }

    public synchronized void put(String actionId, JsonObject result) {
        if (actionId == null || actionId.isEmpty() || result == null) {
            return;
        }
        results.put(actionId, copy(result));
        while (results.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<String, JsonObject>> it = results.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
    }

    public synchronized int size() {
        return results.size();
    }

    public synchronized void clear() {
        results.clear();
    }

    private static JsonObject copy(JsonObject source) {
        return new com.google.gson.JsonParser().parse(source.toString()).getAsJsonObject();
    }
}
