package spirelink.mcp;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.relics.AbstractRelic;

/**
 * Test-only: grant a relic so get_state serialisation and passives can be exercised.
 *
 * <p>Issue #119 · C10-04: the three Spire keys are <b>not</b> relics (there is no
 * {@code relics.RubyKey} class — a key is a {@code RewardItem.RewardType} for Emerald/Sapphire and a
 * {@code Settings.hasRubyKey} flag for Ruby), so {@code dev_relic{id:"SapphireKey"}} used to answer
 * {@code RELIC_NOT_FOUND} and QA could not fabricate the chest that #116b needs. Key ids are now
 * recognised here and handed to {@link KeyRewards}, which builds the real chest shape (relic row +
 * key row on one screen) — same place, one thing to remember.</p>
 */
public final class DevRelicTool {

    private static final String BASE = "com.megacrit.cardcrawl.relics.";

    private DevRelicTool() {
    }

    public static JsonObject add(String id) {
        if (!spirelink.util.TestMode.enabled()) {
            return err("DEV_TOOL_DISABLED", null);
        }
        // Issue #119 · C10-04: a key id is a request for the key chest, not for a relic class.
        KeyRewards.Key key = KeyRewards.keyOf(id);
        if (key != null) {
            return KeyRewards.fabricate(key);
        }
        if (AbstractDungeon.player == null) {
            return err("NOT_READY", "还没有玩家（未进地牢）");
        }
        Class<?> clazz = find(BASE, id, AbstractRelic.class, "");
        if (clazz == null) {
            return err("RELIC_NOT_FOUND", "找不到遗物: " + id
                    + "（要造钥匙宝箱请用钥匙 id: " + KeyRewards.acceptedIds() + "）");
        }
        try {
            AbstractRelic relic = (AbstractRelic) clazz.getDeclaredConstructor().newInstance();
            relic.instantObtain(AbstractDungeon.player, AbstractDungeon.player.relics.size(), false);
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("id", id);
            out.addProperty("name", relic.name);
            out.addProperty("relics", AbstractDungeon.player.relics.size());
            return out;
        } catch (Throwable t) {
            return err("NOT_READY", "加遗物失败: " + t);
        }
    }

    static Class<?> find(String base, String id, Class<?> parent, String... pkgs) {
        if (id.indexOf('.') > 0) {
            try {
                Class<?> c = Class.forName(id);
                return parent.isAssignableFrom(c) ? c : null;
            } catch (Throwable t) {
                return null;
            }
        }
        for (String pkg : pkgs) {
            try {
                Class<?> c = Class.forName(base + pkg + id);
                if (parent.isAssignableFrom(c)) {
                    return c;
                }
            } catch (Throwable ignored) {
                // next package
            }
        }
        return null;
    }

    static JsonObject err(String code, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", code);
        if (message != null) {
            out.addProperty("message", message);
        }
        return out;
    }

}
