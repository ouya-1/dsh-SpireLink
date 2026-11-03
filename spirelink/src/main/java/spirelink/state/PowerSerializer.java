package spirelink.state;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.powers.AbstractPower;

/**
 * Shared serializer for {@code AbstractPower} entries on the player and on monsters (design 7.3 /
 * 7.7). Values are read straight from the live power; nothing is recomputed.
 */
public final class PowerSerializer {

    /** Plain-value mirror so the mapping can be unit tested without a game. */
    public static final class PowerView {
        public String id;
        public String name;
        /** Real stack count; negative values are meaningful (Strength can go below zero). */
        public Integer amount;
        public String text;
        /** {@code "buff"} or {@code "debuff"} straight from {@code AbstractPower.type}. */
        public String kind;
        /** Search order: buffs first, then debuffs, then by id. */
        public static final java.util.Comparator<PowerView> BUFFS_FIRST =
                new java.util.Comparator<PowerView>() {
                    @Override
                    public int compare(PowerView a, PowerView b) {
                        int byKind = rank(a).compareTo(rank(b));
                        if (byKind != 0) {
                            return byKind;
                        }
                        String left = a == null || a.id == null ? "" : a.id;
                        String right = b == null || b.id == null ? "" : b.id;
                        return left.compareTo(right);
                    }

                    private String rank(PowerView view) {
                        if (view == null || view.kind == null) {
                            return "1";
                        }
                        return "debuff".equals(view.kind) ? "2" : "1";
                    }
                };
    }

    private PowerSerializer() {
    }

    public static JsonObject toJson(PowerView view) {
        JsonObject json = new JsonObject();
        json.addProperty("id", view.id);
        if (view.name != null) {
            json.addProperty("name", view.name);
        }
        if (view.amount != null) {
            json.addProperty("amount", view.amount);
            // Same number under a clearer name: "stack" is what a player would say.
            json.addProperty("stack", view.amount);
        }
        if (view.kind != null) {
            json.addProperty("kind", view.kind);
        }
        if (view.name != null) {
            // Machine readable term next to the human text: the agent can look it up in glossary.
            json.addProperty("term", view.name);
        }
        if (view.text != null) {
            spirelink.util.Glossary.addMarked(json, "text", view.text);
        }
        return json;
    }

    /** Game adapter; call from the main thread only. */
    public static PowerView fromPower(AbstractPower power) {
        PowerView view = new PowerView();
        view.id = power.ID;
        view.name = power.name;
        // Negative stacks are real (Strength can go below zero); treating them as "no count" hid
        // them behind amount:null.
        view.amount = Integer.valueOf(power.amount);
        view.kind = power.type
                == com.megacrit.cardcrawl.powers.AbstractPower.PowerType.DEBUFF
                        ? "debuff" : "buff";
        // Colour markup (#y/#b/...) is renderer input, not information; one cleaner for all prose.
        view.text = ProseClean.clean(power.description);
        return view;
    }
}
