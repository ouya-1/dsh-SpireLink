package spirelink.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Pure payload validation for the P2 combat actions (no game classes). */
public class CombatRequestTest {

    private static JsonObject json(String key, Object value) {
        JsonObject object = new JsonObject();
        if (value instanceof String) {
            object.addProperty(key, (String) value);
        } else if (value instanceof Number) {
            object.addProperty(key, (Number) value);
        } else if (value instanceof Boolean) {
            object.addProperty(key, (Boolean) value);
        } else if (value instanceof JsonArray) {
            object.add(key, (JsonArray) value);
        } else if (value == null) {
            object.add(key, com.google.gson.JsonNull.INSTANCE);
        }
        return object;
    }

    // ---------------------------------------------------------------- play_card

    @Test
    public void playCardNeedsUuid() {
        assertCode(ErrorCodes.BAD_REQUEST, () -> CardPlayRequest.parse(null));
        assertCode(ErrorCodes.BAD_REQUEST, () -> CardPlayRequest.parse(new JsonObject()));
        assertCode(ErrorCodes.BAD_REQUEST, () -> CardPlayRequest.parse(json("uuid", "  ")));
        assertCode(ErrorCodes.BAD_REQUEST, () -> CardPlayRequest.parse(json("uuid", 7)));
    }

    @Test
    public void playCardParsesTarget() {
        CardPlayRequest noTarget = CardPlayRequest.parse(json("uuid", "abc"));
        assertEquals("abc", noTarget.cardUuid);
        assertFalse(noTarget.hasTarget());

        CardPlayRequest withTarget = CardPlayRequest.parse(
                with(json("uuid", "abc"), json("target_index", 2)));
        assertEquals(Integer.valueOf(2), withTarget.targetIndex);
        assertTrue(withTarget.hasTarget());
    }

    @Test
    public void playCardRejectsBadTarget() {
        assertCode(ErrorCodes.BAD_REQUEST,
                () -> CardPlayRequest.parse(with(json("uuid", "abc"), json("target_index", -1))));
        assertCode(ErrorCodes.BAD_REQUEST,
                () -> CardPlayRequest.parse(with(json("uuid", "abc"), json("target_index", "左"))));
        // explicit null is the same as "no target"
        assertFalse(CardPlayRequest.parse(
                with(json("uuid", "abc"), json("target_index", null))).hasTarget());
    }

    // ---------------------------------------------------------------- potions

    @Test
    public void potionNeedsSlot() {
        assertCode(ErrorCodes.BAD_REQUEST, () -> PotionRequest.parse(null));
        assertCode(ErrorCodes.BAD_REQUEST, () -> PotionRequest.parse(new JsonObject()));
        assertCode(ErrorCodes.BAD_REQUEST, () -> PotionRequest.parse(json("slot", -2)));
    }

    @Test
    public void potionParsesSlotAndTarget() {
        PotionRequest request = PotionRequest.parse(with(json("slot", 1), json("target_index", 0)));
        assertEquals(1, request.slot);
        assertEquals(Integer.valueOf(0), request.targetIndex);
        assertTrue(request.hasTarget());
        assertFalse(PotionRequest.parse(json("slot", 0)).hasTarget());
    }

    // ---------------------------------------------------------------- hand select

    @Test
    public void handSelectNeedsNonEmptyUniqueList() {
        assertCode(ErrorCodes.BAD_REQUEST, () -> HandSelectRequest.parse(null));
        assertCode(ErrorCodes.BAD_REQUEST, () -> HandSelectRequest.parse(new JsonObject()));
        assertCode(ErrorCodes.BAD_REQUEST,
                () -> HandSelectRequest.parse(json("uuids", new JsonArray())));
        JsonArray duplicates = new JsonArray();
        duplicates.add("a");
        duplicates.add("a");
        assertCode(ErrorCodes.BAD_REQUEST,
                () -> HandSelectRequest.parse(json("uuids", duplicates)));
    }

    @Test
    public void handSelectKeepsOrder() {
        JsonArray uuids = new JsonArray();
        uuids.add("c1");
        uuids.add("c2");
        HandSelectRequest request = HandSelectRequest.parse(json("uuids", uuids));
        assertEquals(2, request.count());
        assertEquals("c1", request.cardUuids.get(0));
        assertEquals("c2", request.cardUuids.get(1));
    }

    @Test
    public void errorCodesAreStable() {
        // The agent branches on these strings; changing them is a protocol break.
        assertEquals("STALE_VERSION", ErrorCodes.STALE_VERSION);
        assertEquals("CARD_NOT_IN_HAND", ErrorCodes.CARD_NOT_IN_HAND);
        assertEquals("TARGET_REQUIRED", ErrorCodes.TARGET_REQUIRED);
    }

    // ---------------------------------------------------------------- helpers

    private static JsonObject with(JsonObject base, JsonObject extra) {
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : extra.entrySet()) {
            base.add(entry.getKey(), entry.getValue());
        }
        return base;
    }

    private static void assertCode(String expected, Runnable action) {
        try {
            action.run();
            fail("expected BadRequestException(" + expected + ")");
        } catch (BadRequestException e) {
            assertEquals(expected, e.code);
        }
    }

    @Test
    public void unknownFieldsAreIgnored() {
        JsonObject payload = json("uuid", "abc");
        payload.addProperty("whatever", true);
        assertNull(null);
        assertEquals("abc", CardPlayRequest.parse(payload).cardUuid);
    }
}
