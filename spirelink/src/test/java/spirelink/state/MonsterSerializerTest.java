package spirelink.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Monster / intent mapping (design 7.7 v1.0) with fake views: the {@code Intent} enum set contains
 * {@code MAGIC}, attack intents carry damage/hits/base_damage, non-attack intents carry only the id.
 */
public class MonsterSerializerTest {

    private static MonsterSerializer.MonsterView gremlinNob() {
        MonsterSerializer.MonsterView view = new MonsterSerializer.MonsterView();
        view.index = 0;
        view.id = "GremlinNob";
        view.name = "地精大王";
        view.currentHp = 82;
        view.maxHp = 86;
        view.block = 0;
        view.gone = false;
        view.halfDead = false;
        MonsterSerializer.IntentView intent = new MonsterSerializer.IntentView();
        intent.id = "ATTACK";
        intent.damage = 16;
        intent.baseDamage = 14;
        intent.hits = 1;
        view.intent = intent;

        PowerSerializer.PowerView anger = new PowerSerializer.PowerView();
        anger.id = "Anger";
        anger.name = "愤怒";
        anger.amount = 2;
        anger.text = "每当你打出技能牌，获得 2 点力量。";
        view.powers.add(anger);
        return view;
    }

    @Test
    public void serializesAttackIntent() {
        JsonObject json = MonsterSerializer.toJson(gremlinNob());
        assertEquals(0, json.get("index").getAsInt());
        assertEquals("GremlinNob", json.get("id").getAsString());
        assertEquals("地精大王", json.get("name").getAsString());
        assertEquals(82, json.get("current_hp").getAsInt());
        assertEquals(86, json.get("max_hp").getAsInt());
        assertEquals(0, json.get("block").getAsInt());
        assertFalse(json.get("is_gone").getAsBoolean());
        assertFalse(json.get("half_dead").getAsBoolean());

        JsonObject intent = json.getAsJsonObject("intent");
        assertEquals("ATTACK", intent.get("id").getAsString());
        assertEquals(16, intent.get("damage").getAsInt());
        assertEquals(1, intent.get("hits").getAsInt());
        assertEquals(14, intent.get("base_damage").getAsInt());

        JsonArray powers = json.getAsJsonArray("powers");
        assertEquals(1, powers.size());
        assertEquals("Anger", powers.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("愤怒", powers.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void magicIntentIsKeptAndHasNoDamageKeys() {
        MonsterSerializer.MonsterView view = gremlinNob();
        view.id = "TheGuardian";
        view.intent.id = "MAGIC";
        view.intent.damage = 0;
        view.intent.baseDamage = 0;
        view.intent.hits = 1;
        JsonObject intent = MonsterSerializer.toJson(view).getAsJsonObject("intent");
        assertEquals("MAGIC", intent.get("id").getAsString());
        assertNull(intent.get("damage"));
        assertNull(intent.get("hits"));
        assertNull(intent.get("base_damage"));
    }

    @Test
    public void multiHitAttackKeepsHitCount() {
        MonsterSerializer.MonsterView view = gremlinNob();
        view.intent.damage = 6;
        view.intent.hits = 3;
        JsonObject intent = MonsterSerializer.toJson(view).getAsJsonObject("intent");
        assertEquals(3, intent.get("hits").getAsInt());
        assertEquals(6, intent.get("damage").getAsInt());
    }

    @Test
    public void hiddenIntentIsOmitted() {
        MonsterSerializer.MonsterView view = gremlinNob();
        view.intent = null;
        JsonObject json = MonsterSerializer.toJson(view);
        assertNull(json.get("intent"));
    }

    @Test
    public void deadMonsterKeepsIndex() {
        MonsterSerializer.MonsterView view = gremlinNob();
        view.index = 2;
        view.gone = true;
        view.halfDead = true;
        view.currentHp = 0;
        // 死了还挂 intent 会误导 agent（实测：「0 血敌人仍带 ATTACK 意图」）：serializer 直接吞掉。
        MonsterSerializer.IntentView intent = new MonsterSerializer.IntentView();
        intent.id = "ATTACK";
        intent.damage = 12;
        intent.hits = 1;
        view.intent = intent;
        JsonObject json = MonsterSerializer.toJson(view);
        assertEquals(2, json.get("index").getAsInt());
        assertTrue(json.get("is_gone").getAsBoolean());
        assertTrue(json.get("half_dead").getAsBoolean());
        assertEquals(0, json.get("current_hp").getAsInt());
        assertNull("死掉的怪不该再输出 intent", json.get("intent"));
    }

    @Test
    public void powerWithoutStackOmitsAmount() {
        PowerSerializer.PowerView power = new PowerSerializer.PowerView();
        power.id = "Curiosity";
        power.name = "好奇";
        power.amount = null;
        JsonObject json = PowerSerializer.toJson(power);
        assertEquals("Curiosity", json.get("id").getAsString());
        assertNull(json.get("amount"));
    }
}
