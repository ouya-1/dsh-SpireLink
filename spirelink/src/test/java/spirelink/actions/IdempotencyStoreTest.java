package spirelink.actions;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** {@code action_id} idempotency (design 5 / review R6). Pure bookkeeping, no game classes. */
public class IdempotencyStoreTest {

    private static JsonObject payload(String outcome) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("outcome", outcome);
        return json;
    }

    @Test
    public void firstResultIsReplayed() {
        IdempotencyStore store = new IdempotencyStore();
        store.put("id-1", payload("applied"));
        JsonObject replay = store.get("id-1");
        assertNotNull(replay);
        assertEquals("applied", replay.get("outcome").getAsString());
        assertEquals(1, store.size());
    }

    @Test
    public void unknownOrBlankIdsReturnNull() {
        IdempotencyStore store = new IdempotencyStore();
        assertNull(store.get(null));
        assertNull(store.get(""));
        assertNull(store.get("never-seen"));
        store.put(null, payload("applied"));
        store.put("", payload("applied"));
        assertEquals(0, store.size());
    }

    @Test
    public void storedCopiesAreIsolated() {
        IdempotencyStore store = new IdempotencyStore();
        JsonObject original = payload("queued");
        store.put("id-2", original);
        original.addProperty("outcome", "tampered");
        JsonObject replay = store.get("id-2");
        assertEquals("queued", replay.get("outcome").getAsString());
        replay.addProperty("outcome", "tampered-again");
        assertEquals("queued", store.get("id-2").get("outcome").getAsString());
    }

    @Test
    public void oldestEntriesAreEvicted() {
        IdempotencyStore store = new IdempotencyStore();
        for (int i = 0; i < IdempotencyStore.MAX_ENTRIES + 5; i++) {
            store.put("id-" + i, payload("applied"));
        }
        assertEquals(IdempotencyStore.MAX_ENTRIES, store.size());
        assertNull(store.get("id-0"));
        assertNotNull(store.get("id-" + (IdempotencyStore.MAX_ENTRIES + 4)));
    }
}
