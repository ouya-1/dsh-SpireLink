package spirelink.state;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Field mapping of the compact card form (design 7.4 v1.0), using a fake view so no game object is
 * needed. Also checks that Chinese names survive the JSON layer (UTF-8 everywhere).
 */
public class CardSerializerTest {

    private static CardSerializer.CardView strike() {
        CardSerializer.CardView view = new CardSerializer.CardView();
        view.uuid = "7b54caef-9c56-4134-82a2-be8f1d5c435f";
        view.id = "Strike_R";
        view.name = "打击";
        view.type = "ATTACK";
        view.rarity = "STARTER";
        view.cost = 1;
        view.upgrades = 0;
        view.ethereal = false;
        view.exhausts = false;
        view.retain = false;
        view.target = "ENEMY";
        view.hasTarget = true;
        view.playable = Boolean.TRUE;
        view.unplayableReason = null;
        view.damage = 6;
        view.block = 0;
        view.magicNumber = 0;
        view.needsInspect = false;
        view.index = 0;
        return view;
    }

    @Test
    public void mapsCompactCardFields() {
        JsonObject json = CardSerializer.toJson(strike());

        assertEquals("7b54caef-9c56-4134-82a2-be8f1d5c435f", json.get("uuid").getAsString());
        assertEquals("Strike_R", json.get("id").getAsString());
        assertEquals("打击", json.get("name").getAsString());
        assertEquals("ATTACK", json.get("type").getAsString());
        assertEquals("STARTER", json.get("rarity").getAsString());
        assertEquals(1, json.get("cost").getAsInt());
        assertEquals(0, json.get("upgrades").getAsInt());
        assertFalse(json.get("ethereal").getAsBoolean());
        assertFalse(json.get("exhausts").getAsBoolean());
        assertFalse(json.get("retain").getAsBoolean());
        assertEquals("ENEMY", json.get("target").getAsString());
        assertTrue(json.get("has_target").getAsBoolean());
        assertTrue(json.get("is_playable").getAsBoolean());
        assertNull(json.get("unplayable_reason"));
        assertEquals(6, json.get("damage").getAsInt());
        assertEquals(0, json.get("block").getAsInt());
        assertEquals(0, json.get("magic_number").getAsInt());
        assertFalse(json.get("needs_inspect").getAsBoolean());
        assertEquals(0, json.get("index").getAsInt());
    }

    @Test
    public void keepsGameCostSemantics() {
        CardSerializer.CardView unplayable = strike();
        unplayable.cost = -2;
        unplayable.playable = Boolean.FALSE;
        unplayable.unplayableReason = "unplayable";
        assertEquals(-2, CardSerializer.toJson(unplayable).get("cost").getAsInt());
        assertEquals("unplayable",
                CardSerializer.toJson(unplayable).get("unplayable_reason").getAsString());

        CardSerializer.CardView xCost = strike();
        xCost.cost = -1;
        assertEquals(-1, CardSerializer.toJson(xCost).get("cost").getAsInt());
    }

    @Test
    public void hasTargetIsDerivedFromEnemyOnly() {
        CardSerializer.CardView selfAndEnemy = strike();
        selfAndEnemy.target = "SELF_AND_ENEMY";
        selfAndEnemy.hasTarget = false;
        JsonObject json = CardSerializer.toJson(selfAndEnemy);
        assertEquals("SELF_AND_ENEMY", json.get("target").getAsString());
        assertFalse(json.get("has_target").getAsBoolean());

        CardSerializer.CardView allEnemy = strike();
        allEnemy.target = "ALL_ENEMY";
        allEnemy.hasTarget = false;
        assertFalse(CardSerializer.toJson(allEnemy).get("has_target").getAsBoolean());
    }

    @Test
    public void outsideCombatThereIsNoPlayabilityOrReason() {
        CardSerializer.CardView deckCard = strike();
        deckCard.playable = null;
        deckCard.unplayableReason = null;
        deckCard.index = null;
        JsonObject json = CardSerializer.toJson(deckCard);
        assertNull(json.get("is_playable"));
        assertNull(json.get("unplayable_reason"));
        assertNull(json.get("index"));
        // target/has_target still describe the card definition.
        assertEquals("ENEMY", json.get("target").getAsString());
        assertTrue(json.get("has_target").getAsBoolean());
    }

    @Test
    public void upgradedCardKeepsRawNumbers() {
        CardSerializer.CardView bash = strike();
        bash.id = "Bash";
        bash.name = "重击";
        bash.cost = 2;
        bash.upgrades = 1;
        bash.damage = 12; // post-applyPowers value
        bash.magicNumber = 3;
        bash.hasTarget = true;
        JsonObject json = CardSerializer.toJson(bash);
        assertEquals(2, json.get("cost").getAsInt());
        assertEquals(1, json.get("upgrades").getAsInt());
        assertEquals(12, json.get("damage").getAsInt());
        assertEquals(3, json.get("magic_number").getAsInt());
    }
}
