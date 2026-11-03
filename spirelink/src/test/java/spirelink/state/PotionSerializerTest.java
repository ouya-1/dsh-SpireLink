package spirelink.state;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Belt-slot mapping (design 7.5 v1.1). Fake views only: the game's empty belt slots are
 * {@code PotionSlot} placeholders and must never be serialized as a potion with id/name/text.
 */
public class PotionSerializerTest {

    private static PotionSerializer.PotionView firePotion() {
        PotionSerializer.PotionView view = new PotionSerializer.PotionView();
        view.slot = 1;
        view.empty = false;
        view.id = "Fire Potion";
        view.name = "火焰药水";
        view.canUse = Boolean.TRUE;
        view.canDiscard = Boolean.TRUE;
        view.isThrown = Boolean.TRUE;
        view.targetRequired = Boolean.TRUE;
        view.text = "造成 20 点伤害。";
        view.color = "FIRE";
        return view;
    }

    @Test
    public void emptySlotCarriesOnlySlotAndEmpty() {
        JsonObject json = PotionSerializer.toJson(PotionSerializer.emptySlot(2));
        assertEquals(2, json.entrySet().size());
        assertEquals(2, json.get("slot").getAsInt());
        assertTrue(json.get("empty").getAsBoolean());
        assertNull(json.get("id"));
        assertNull(json.get("name"));
        assertNull(json.get("text"));
        assertNull(json.get("can_use"));
        assertNull(json.get("target_required"));
    }

    @Test
    public void realPotionCarriesAllFields() {
        JsonObject json = PotionSerializer.toJson(firePotion());
        assertEquals(1, json.get("slot").getAsInt());
        assertFalse(json.get("empty").getAsBoolean());
        assertEquals("Fire Potion", json.get("id").getAsString());
        assertEquals("火焰药水", json.get("name").getAsString());
        assertTrue(json.get("can_use").getAsBoolean());
        assertTrue(json.get("can_discard").getAsBoolean());
        assertTrue(json.get("is_thrown").getAsBoolean());
        assertTrue(json.get("target_required").getAsBoolean());
        assertEquals("造成 20 点伤害。", json.get("text").getAsString());
        assertEquals("FIRE", json.get("color").getAsString());
    }

    @Test
    public void placeholderIdIsNotAPotion() {
        // The game's unused-slot placeholder carries this id; it must count as empty.
        assertTrue(PotionSerializer.isPlaceholderId("Potion Slot"));
        assertTrue(PotionSerializer.isPlaceholder(null));
        assertFalse(PotionSerializer.isPlaceholderId("Fire Potion"));
        assertFalse(PotionSerializer.isPlaceholderId(""));
        assertFalse(PotionSerializer.isPlaceholderId(null));
    }

    @Test
    public void emptySlotsFromMixedBeltSerializeAsEmpty() {
        // Belt with: placeholder, real potion, placeholder -> only slot 1 has content.
        PotionSerializer.PotionView[] belt = {
                PotionSerializer.emptySlot(0),
                firePotion(),
                PotionSerializer.emptySlot(2)
        };
        JsonObject potions = new JsonObject();
        com.google.gson.JsonArray slots = new com.google.gson.JsonArray();
        for (PotionSerializer.PotionView view : belt) {
            slots.add(PotionSerializer.toJson(view));
        }
        potions.addProperty("slot_count", belt.length);
        potions.add("slots", slots);

        JsonObject slot0 = slots.get(0).getAsJsonObject();
        JsonObject slot1 = slots.get(1).getAsJsonObject();
        JsonObject slot2 = slots.get(2).getAsJsonObject();
        assertEquals(2, slot0.entrySet().size());
        assertTrue(slot0.get("empty").getAsBoolean());
        assertEquals("Fire Potion", slot1.get("id").getAsString());
        assertFalse(slot1.get("empty").getAsBoolean());
        assertEquals(2, slot2.entrySet().size());
        assertTrue(slot2.get("empty").getAsBoolean());
        for (Map.Entry<String, com.google.gson.JsonElement> ignored : slot0.entrySet()) {
            // no exception: iteration only proves the object is a plain map
        }
    }
}
