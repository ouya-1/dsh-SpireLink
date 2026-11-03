package spirelink.mcp;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.potions.AbstractPotion;

/** Test-only: put a named potion into the first empty slot. */
public final class DevPotionTool {

    private static final String BASE = "com.megacrit.cardcrawl.potions.";

    private DevPotionTool() {
    }

    public static JsonObject add(String id) {
        return add(id, -1);
    }

    /**
     * Puts a potion into a slot. {@code wantedSlot >= 0} reuses that slot (the old potion is destroyed
     * first), which lets a scan loop over three slots forever instead of running out of empties.
     */
    public static JsonObject add(String id, int wantedSlot) {
        if (!spirelink.util.TestMode.enabled()) {
            return err("DEV_TOOL_DISABLED", null);
        }
        if (AbstractDungeon.player == null) {
            return err("NOT_READY", "还没有玩家（未进地牢）");
        }
        AbstractPotion resolved = resolve(id);
        if (resolved == null) {
            return err("POTION_NOT_FOUND", "找不到药水: " + id
                    + "（可用 {list:true} 列出全部 id；也接受 FirePotion / 中文名 / 显示名）");
        }
        Class<?> clazz = resolved.getClass();
        try {
            AbstractPotion potion = (AbstractPotion) clazz.getDeclaredConstructor().newInstance();
            int slot = -1;
            if (wantedSlot >= 0) {
                if (wantedSlot >= AbstractDungeon.player.potions.size()) {
                    return err("BAD_REQUEST", "slot 越界: " + wantedSlot + "（可选 0.."
                            + (AbstractDungeon.player.potions.size() - 1) + "）");
                }
                slot = wantedSlot;
                // Reset the slot first so a rejected use/discard from the previous potion cannot
                // leave a half-dead object behind.
                try {
                    AbstractDungeon.topPanel.destroyPotion(slot);
                } catch (Throwable t) {
                    spirelink.util.Diag.warn("dev_potion: destroyPotion(" + slot + ") 失败: " + t);
                }
                AbstractDungeon.player.potions.set(slot,
                        new com.megacrit.cardcrawl.potions.PotionSlot(slot));
            } else {
                for (int i = 0; i < AbstractDungeon.player.potions.size(); i++) {
                    // Empty slots hold a PotionSlot placeholder in this version, never null.
                    if (spirelink.state.PotionSerializer
                            .isPlaceholder(AbstractDungeon.player.potions.get(i))) {
                        slot = i;
                        break;
                    }
                }
            }
            if (slot < 0) {
                return err("POTION_SLOT_FULL", "没有空药水槽（传 slot 可指定槽位覆盖，或先用 discard_potion 清一格）");
            }
            AbstractDungeon.player.potions.set(slot, potion);
            // The game fills these when a potion is really obtained; a bare newInstance() keeps
            // slot = -1, and using/discarding it then calls TopPanel.destroyPotion(-1) -> AIOOBE.
            potion.slot = slot;
            potion.isObtained = true;
            potion.discarded = false;
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("id", id);
            out.addProperty("slot", slot);
            out.addProperty("name", potion.name);
            return out;
        } catch (Throwable t) {
            return err("NOT_READY", "给药水失败: " + t);
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

    /**
     * Every potion the game knows, by rarity. The list doubles as the name index: the same object
     * carries {@code ID} ("Fire Potion"), the class name ({@code FirePotion}) and the localized
     * {@code name} (Chinese in the zh pack).
     */
    public static java.util.List<AbstractPotion> allPotions() {
        java.util.List<AbstractPotion> out = new java.util.ArrayList<AbstractPotion>();
        for (com.megacrit.cardcrawl.potions.AbstractPotion.PotionRarity rarity
                : com.megacrit.cardcrawl.potions.AbstractPotion.PotionRarity.values()) {
            try {
                java.util.ArrayList<AbstractPotion> byRarity =
                        com.megacrit.cardcrawl.helpers.PotionHelper.getPotionsByRarity(rarity);
                if (byRarity == null) {
                    continue;
                }
                for (AbstractPotion potion : byRarity) {
                    if (potion != null && !spirelink.state.PotionSerializer.isPlaceholder(potion)) {
                        out.add(potion);
                    }
                }
            } catch (Throwable ignored) {
                // a rarity table that is not initialised yet is not fatal
            }
        }
        return out;
    }

    /** {@code dev_potion{list:true}}: id, class name and localized name for every potion. */
    public static JsonObject list() {
        if (!spirelink.util.TestMode.enabled()) {
            return err("DEV_TOOL_DISABLED", null);
        }
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        for (AbstractPotion potion : allPotions()) {
            com.google.gson.JsonObject item = new com.google.gson.JsonObject();
            item.addProperty("id", potion.ID);
            item.addProperty("class", potion.getClass().getSimpleName());
            item.addProperty("name", potion.name);
            item.addProperty("rarity", potion.rarity == null ? "" : potion.rarity.name());
            items.add(item);
        }
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        out.addProperty("ok", true);
        out.addProperty("count", items.size());
        out.add("potions", items);
        return out;
    }

    /**
     * Resolves a potion by id ({@code "Fire Potion"}), class name ({@code FirePotion}) or display
     * name (Chinese under the zh language pack). Whitespace and case are ignored.
     */
    private static AbstractPotion resolve(String wanted) {
        if (wanted == null || wanted.trim().isEmpty()) {
            return null;
        }
        String key = normalize(wanted);
        for (AbstractPotion potion : allPotions()) {
            if (potion == null) {
                continue;
            }
            if (key.equals(normalize(potion.ID))
                    || key.equals(normalize(potion.getClass().getSimpleName()))
                    || key.equals(normalize(potion.name))) {
                return potion;
            }
        }
        // Fall back to a plain class lookup for ids that are not in the rarity tables (special
        // potions such as the Ghost Council ones).
        Class<?> clazz = find(BASE, wanted.replace(" ", ""), AbstractPotion.class, "", "potions.");
        if (clazz == null) {
            return null;
        }
        try {
            return (AbstractPotion) clazz.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Lowercase, strip spaces/underscores/hyphens so "Fire Potion" == "FirePotion" == "firepotion". */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '_' || c == '-' || c == '·') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
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
