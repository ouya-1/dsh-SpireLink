package spirelink.state;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.potions.PotionSlot;

/**
 * Potion serialization (design 7.5 v1.1).
 *
 * <p>Belt slots are fixed: an empty slot is reported as {@code {"slot": n, "empty": true}} and
 * nothing else. The game fills unused slots with a placeholder object ({@link PotionSlot}, whose
 * {@code ID} is {@code "Potion Slot"}) — that placeholder is <em>not</em> a potion and must never
 * be serialized with an id/name/text, or the harness would try to use it.
 *
 * <p>The mapping lives on {@link PotionView} so it can be unit tested with fake values; only
 * {@link #fromPotion(AbstractPotion, int)} and {@link #isPlaceholder(AbstractPotion)} touch the
 * game.
 */
public final class PotionSerializer {

    /** The placeholder id the game puts in unused belt slots. */
    static final String PLACEHOLDER_ID = "Potion Slot";

    /** Plain-value mirror of one belt slot. */
    public static final class PotionView {
        public int slot;
        public boolean empty;
        public String id;
        public String name;
        public Boolean canUse;
        public Boolean canDiscard;
        public Boolean isThrown;
        /** Raw {@code AbstractPotion.targetRequired}. */
        public Boolean targetRequired;
        public String text;
        public String color;
    }

    private PotionSerializer() {
    }

    public static PotionView emptySlot(int slot) {
        PotionView view = new PotionView();
        view.slot = slot;
        view.empty = true;
        return view;
    }

    public static JsonObject toJson(PotionView view) {
        JsonObject json = new JsonObject();
        json.addProperty("slot", view.slot);
        json.addProperty("empty", view.empty);
        if (view.empty) {
            // Design 7.5: an empty slot carries slot + empty only.
            return json;
        }
        json.addProperty("id", view.id);
        json.addProperty("name", view.name);
        if (view.canUse != null) {
            json.addProperty("can_use", view.canUse.booleanValue());
        }
        if (view.canDiscard != null) {
            json.addProperty("can_discard", view.canDiscard.booleanValue());
        }
        if (view.isThrown != null) {
            json.addProperty("is_thrown", view.isThrown.booleanValue());
        }
        if (view.targetRequired != null) {
            json.addProperty("target_required", view.targetRequired.booleanValue());
        }
        if (view.text != null) {
            json.addProperty("text", view.text);
        }
        if (view.color != null) {
            json.addProperty("color", view.color);
        }
        return json;
    }

    /**
     * Reads the direct fields off a real potion. {@code canUse()} / {@code canDiscard()} are left
     * to the caller because they run game logic and need exception guards.
     */
    public static PotionView fromPotion(AbstractPotion potion, int slot) {
        PotionView view = new PotionView();
        view.slot = slot;
        view.empty = false;
        view.id = potion.ID;
        view.name = potion.name;
        view.isThrown = Boolean.valueOf(potion.isThrown);
        view.targetRequired = Boolean.valueOf(potion.targetRequired);
        // B2-18: potion text carries colour codes (#y/#b - 89 of them in the shipped Chinese strings).
        // Cleaned in the game adapter, not in toJson: that mapper stays a pure mirror of the view.
        view.text = ProseClean.clean(potion.description);
        view.color = potion.color == null ? null : potion.color.name();
        return view;
    }

    /**
     * True when this belt entry is not a potion: a null slot, the game's {@link PotionSlot}
     * placeholder, or anything carrying the placeholder id.
     */
    public static boolean isPlaceholder(AbstractPotion potion) {
        if (potion == null) {
            return true;
        }
        if (potion instanceof PotionSlot) {
            return true;
        }
        return isPlaceholderId(potion.ID);
    }

    /** Pure id check (kept free of {@link PotionSlot} statics so it is unit testable). */
    static boolean isPlaceholderId(String id) {
        return PLACEHOLDER_ID.equals(id);
    }
}
