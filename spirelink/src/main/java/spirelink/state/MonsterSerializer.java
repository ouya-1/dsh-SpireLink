package spirelink.state;

import basemod.ReflectionHacks;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.powers.AbstractPower;

import java.util.ArrayList;
import java.util.List;

/**
 * Monster serialization (design 7.7).
 *
 * <p>Intent is <em>this</em> hand's intent, not a move table: {@code monster.intent} for the id,
 * {@code getIntentDmg()} for the adjusted damage, {@code getIntentBaseDmg()} for the base number,
 * and the private {@code intentMultiAmt} field for the hit count (read through BaseMod's
 * {@code ReflectionHacks}, it has no getter in this game build). The {@code Intent} enum already
 * contains {@code MAGIC}, so unknown-intent guessing is never needed.
 */
public final class MonsterSerializer {

    public static final class IntentView {
        public String id;
        public int damage;
        public int baseDamage;
        public int hits;
    }

    /** Plain-value mirror of one monster. */
    public static final class MonsterView {
        public int index;
        public String id;
        public String name;
        public int currentHp;
        public int maxHp;
        public int block;
        public boolean gone;
        public boolean halfDead;
        /** {@code null} when hidden (Runic Dome) or when the monster has no intent. */
        public IntentView intent;
        public List<PowerSerializer.PowerView> powers = new ArrayList<>();
    }

    private MonsterSerializer() {
    }

    public static JsonObject toJson(MonsterView view) {
        JsonObject json = new JsonObject();
        json.addProperty("index", view.index);
        json.addProperty("id", view.id);
        if (view.name != null) {
            json.addProperty("name", view.name);
        }
        json.addProperty("current_hp", view.currentHp);
        json.addProperty("hp", view.currentHp);
        json.addProperty("max_hp", view.maxHp);
        json.addProperty("block", view.block);
        json.addProperty("is_gone", view.gone);
        json.addProperty("half_dead", view.halfDead);
        // 已经死掉/逃跑的怪不再输出 intent：试玩 agent 实测「0 血敌人还挂着 ATTACK 意图」会让人以为
        // 它下一回合还要打人。列表本身不能删（index 是 target_index 的下标，删了会错位）。
        if (view.intent != null && !view.gone) {
            JsonObject intent = new JsonObject();
            intent.addProperty("id", view.intent.id);
            if ("DEBUG".equals(view.intent.id)) {
                // Transition frames carry the engine's placeholder intent; say so instead of handing
                // the agent an intent with no damage and no meaning.
                intent.addProperty("unknown", true);
            }
            // Only ATTACK* intents carry damage. getIntentDmg() keeps the last attack's number on
            // DEFEND/ESCAPE intents, which produced contradictions like {"id":"DEFEND","damage":10}.
            boolean attackIntent = view.intent.id != null && view.intent.id.startsWith("ATTACK");
            if (!"DEBUG".equals(view.intent.id)) {
                intent.addProperty("has_damage",
                        attackIntent && (view.intent.damage > 0 || view.intent.baseDamage > 0));
            }
            if (attackIntent && view.intent.damage > 0) {
                intent.addProperty("damage", view.intent.damage);
                intent.addProperty("hits", view.intent.hits);
                intent.addProperty("total_damage", view.intent.damage * view.intent.hits);
            } else if (attackIntent && view.intent.baseDamage > 0) {
                // ATTACK_DEBUFF and friends may leave only the base value filled; the base is still
                // what the hit will take before block/powers, so expose it instead of nothing.
                intent.addProperty("damage", view.intent.baseDamage);
                intent.addProperty("damage_is_base", true);
                intent.addProperty("hits", view.intent.hits);
                intent.addProperty("total_damage", view.intent.baseDamage * view.intent.hits);
            }
            if (attackIntent && view.intent.baseDamage > 0) {
                intent.addProperty("base_damage", view.intent.baseDamage);
            }
            json.add("intent", intent);
        }
        JsonArray powers = new JsonArray();
        if (view.powers != null) {
            for (PowerSerializer.PowerView power : view.powers) {
                powers.add(PowerSerializer.toJson(power));
            }
        }
        json.add("powers", powers);
        return json;
    }

    /** Game adapter; main thread only. */
    public static MonsterView fromMonster(AbstractMonster monster, int index, boolean hideIntent) {
        MonsterView view = new MonsterView();
        view.index = index;
        view.id = monster.id;
        view.name = monster.name;
        view.currentHp = monster.currentHealth;
        view.maxHp = monster.maxHealth;
        view.block = monster.currentBlock;
        view.gone = monster.isDeadOrEscaped();
        view.halfDead = monster.halfDead;
        if (!hideIntent) {
            view.intent = intentOf(monster);
        }
        view.powers = powersOf(monster.powers);
        return view;
    }

    static IntentView intentOf(AbstractMonster monster) {
        AbstractMonster.Intent intent = monster.intent;
        if (intent == null) {
            return null;
        }
        IntentView view = new IntentView();
        view.id = intent.name();
        view.damage = monster.getIntentDmg();
        view.baseDamage = monster.getIntentBaseDmg();
        int hits = 1;
        try {
            Integer multi = ReflectionHacks.getPrivate(monster, AbstractMonster.class, "intentMultiAmt");
            if (multi != null && multi.intValue() > 0) {
                hits = multi.intValue();
            }
        } catch (Throwable ignored) {
            // Field renamed by a game update: keep single-hit semantics instead of failing.
            hits = 1;
        }
        view.hits = hits;
        return view;
    }

    static List<PowerSerializer.PowerView> powersOf(List<AbstractPower> powers) {
        List<PowerSerializer.PowerView> views = new ArrayList<>();
        if (powers != null) {
            for (AbstractPower power : powers) {
                if (power != null) {
                    views.add(PowerSerializer.fromPower(power));
                }
            }
        }
        // Same order as the player block: buffs first so a glance tells what helps vs hurts.
        java.util.Collections.sort(views, PowerSerializer.PowerView.BUFFS_FIRST);
        return views;
    }
}
