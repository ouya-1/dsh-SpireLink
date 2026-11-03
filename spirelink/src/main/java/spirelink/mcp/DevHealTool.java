package spirelink.mcp;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.powers.AbstractPower;

/**
 * Test-only: restore the player to full health and strip debuffs.
 *
 * <p>Without this, one combat event (MaskedBandits) kills the 999-HP test run and every later event
 * in a scan runs on the death screen.
 */
public final class DevHealTool {

    /** Powers that always count as debuffs for a test run. */
    private static final String[] DEBUFFS = {"Vulnerable", "Weak", "Frail", "Poison",
        "Negative Strength", "Negative Dexterity", "Shackled", "Confusion", "Entangled",
        "Fasting", "Hex", "Slow", "Constricted", "Draw Reduction", "Normality",
        "No Draw", "Vulnerable"};

    private DevHealTool() {
    }

    public static JsonObject heal() {
        JsonObject out = new JsonObject();
        if (!spirelink.util.TestMode.enabled()) {
            out.addProperty("ok", false);
            out.addProperty("error", "DEV_TOOL_DISABLED");
            return out;
        }
        try {
            AbstractPlayer player = AbstractDungeon.player;
            if (player == null) {
                out.addProperty("ok", false);
                out.addProperty("error", "NOT_READY");
                out.addProperty("message", "还没有玩家对象（未进入一局游戏）");
                return out;
            }
            int before = player.currentHealth;
            player.currentHealth = player.maxHealth;
            int removed = 0;
            if (player.powers != null) {
                for (AbstractPower power : new java.util.ArrayList<AbstractPower>(player.powers)) {
                    if (power == null || !isDebuff(power)) {
                        continue;
                    }
                    player.powers.remove(power);
                    removed++;
                }
            }
            player.isDead = false;
            // 顺手把「血上限很高但当前血=1」的怪补满：The Heart / Donu&Deca 的 pre-battle 动作会把
            // currentHealth 打回 1（2026-11-02 实测：CorruptHeart 1/750 一刀就 battle_over）。
            int restored = 0;
            try {
                com.megacrit.cardcrawl.monsters.MonsterGroup group = AbstractDungeon.getMonsters();
                if (group != null && group.monsters != null) {
                    for (com.megacrit.cardcrawl.monsters.AbstractMonster monster : group.monsters) {
                        if (monster != null && monster.maxHealth > 1 && monster.currentHealth <= 1) {
                            monster.currentHealth = monster.maxHealth;
                            restored++;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 不在战斗里就没有 MonsterGroup，正常情况。
            }
            out.addProperty("monsters_restored", restored);
            if (restored > 0) {
                spirelink.util.Diag.info("dev_heal: 补满 " + restored + " 只怪的血量（开战前被打回 1 的那种）");
            }
            out.addProperty("ok", true);
            out.addProperty("hp_before", before);
            out.addProperty("hp_after", player.currentHealth);
            out.addProperty("debuffs_removed", removed);
            spirelink.util.Diag.info("dev_heal: " + before + " -> " + player.currentHealth
                    + "，清除 debuff " + removed + " 个");
            spirelink.ui.AgentHud.push("测试模式：血量回满");
            return out;
        } catch (Throwable t) {
            out.addProperty("ok", false);
            out.addProperty("error", "NOT_READY");
            out.addProperty("message", "回血失败: " + t);
            return out;
        }
    }

    private static boolean isDebuff(AbstractPower power) {
        try {
            if (power.type == AbstractPower.PowerType.DEBUFF) {
                return true;
            }
        } catch (Throwable ignored) {
            // fall through to the name list
        }
        if (power.name == null) {
            return false;
        }
        for (String name : DEBUFFS) {
            if (name.equals(power.name)) {
                return true;
            }
        }
        return false;
    }
}
