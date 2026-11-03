package spirelink.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.monsters.MonsterGroup;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

/**
 * Compact pre/post snapshot used to build the {@code act} {@code log} sentence (design 5: an
 * {@code applied} answer carries a short human-readable outcome). Main thread only.
 */
public final class CombatDigest {

    private CombatDigest() {
    }

    /** @return {@code {energy, hand, hp, block, round, monsters:[{index,id,hp}]}} or null. */
    public static JsonObject capture() {
        try {
            AbstractPlayer player = AbstractDungeon.player;
            if (player == null) {
                return null;
            }
            JsonObject json = new JsonObject();
            json.addProperty("energy", EnergyPanel.getCurrentEnergy());
            json.addProperty("hand", player.hand == null ? 0 : player.hand.size());
            json.addProperty("hp", player.currentHealth);
            json.addProperty("block", player.currentBlock);
            json.addProperty("round", GameActionManager.turn);
            JsonArray monsters = new JsonArray();
            MonsterGroup group = AbstractDungeon.getMonsters();
            if (group != null && group.monsters != null) {
                for (int i = 0; i < group.monsters.size(); i++) {
                    AbstractMonster monster = group.monsters.get(i);
                    if (monster == null) {
                        continue;
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty("index", i);
                    entry.addProperty("id", monster.id);
                    entry.addProperty("name", monster.name);
                    entry.addProperty("hp", monster.currentHealth);
                    monsters.add(entry);
                }
            }
            json.add("monsters", monsters);
            return json;
        } catch (Throwable t) {
            return null;
        }
    }
}
