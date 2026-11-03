package spirelink.state;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.AbstractCard;

/**
 * Card serialization (design 7.4). Compact form only in P0.
 *
 * <p>Fields are read from the live card instance: {@code uuid} is the play identity,
 * {@code cost} is {@code costForTurn}, and {@code damage / block / magicNumber} must be read
 * <em>after</em> the combat card ran {@code applyPowers()} — the caller does that and this class
 * mirrors the resulting numbers.
 *
 * <p>The mapping itself lives on {@link CardView} so it can be unit tested with fake values; only
 * {@link #fromCard(AbstractCard)} touches the game.
 */
public final class CardSerializer {

    /** Plain-value mirror of a compact card. */
    public static final class CardView {
        public String uuid;
        public String id;
        public String name;
        public String type;
        public String rarity;
        /** {@code costForTurn}: -2 unplayable, -1 uninitialized / X-cost, otherwise the cost. */
        public int cost;
        public int upgrades;
        public boolean ethereal;
        public boolean exhausts;
        public boolean retain;
        /** Raw {@code AbstractCard.CardTarget} name: SELF / NONE / ENEMY / SELF_AND_ENEMY / ... */
        public String target;
        /** Derived: strictly {@code target == CardTarget.ENEMY}. */
        public boolean hasTarget;
        /** Human readable description (already marked up with 【术语】 by {@link spirelink.util.Glossary}). */
        public String description;
        /** {@code null} outside combat, where playability is meaningless. */
        public Boolean playable;
        public String unplayableReason;
        public int damage;
        public int block;
        public int magicNumber;
        public boolean needsInspect;
        /** Hand index (left to right) or pile index; {@code null} to omit. */
        public Integer index;
    }

    private CardSerializer() {
    }

    public static JsonObject toJson(CardView view) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", view.uuid);
        json.addProperty("id", view.id);
        json.addProperty("name", view.name);
        if (view.description != null && !view.description.isEmpty()) {
            // Marked text + machine readable keywords, e.g. "获得【格挡】5 点" / ["格挡"].
            spirelink.util.Glossary.addMarked(json, "text", view.description);
        }
        if (view.type != null) {
            json.addProperty("type", view.type);
        }
        if (view.rarity != null) {
            json.addProperty("rarity", view.rarity);
        }
        json.addProperty("cost", view.cost);
        json.addProperty("upgrades", view.upgrades);
        json.addProperty("ethereal", view.ethereal);
        json.addProperty("exhausts", view.exhausts);
        json.addProperty("retain", view.retain);
        if (view.target != null) {
            json.addProperty("target", view.target);
        }
        json.addProperty("has_target", view.hasTarget);
        if (view.playable != null) {
            json.addProperty("is_playable", view.playable.booleanValue());
        }
        if (view.unplayableReason != null) {
            json.addProperty("unplayable_reason", view.unplayableReason);
        }
        json.addProperty("damage", view.damage);
        json.addProperty("block", view.block);
        json.addProperty("magic_number", view.magicNumber);
        json.addProperty("needs_inspect", view.needsInspect);
        if (view.index != null) {
            json.addProperty("index", view.index);
        }
        return json;
    }

    /**
     * Reads direct fields off a live card. Playability / unplayable_reason / index / needs_inspect
     * are filled by {@link StateBuilder} because they need combat context.
     */
    public static CardView fromCard(AbstractCard card) {
        CardView view = new CardView();
        view.uuid = card.uuid == null ? null : card.uuid.toString();
        view.id = card.cardID;
        view.name = card.name;
        view.description = CardText.describe(card);
        view.type = card.type == null ? null : card.type.name();
        view.rarity = card.rarity == null ? null : card.rarity.name();
        view.cost = card.costForTurn;
        view.upgrades = card.timesUpgraded;
        view.ethereal = card.isEthereal;
        view.exhausts = card.exhaust;
        view.retain = card.retain || card.selfRetain;
        view.target = card.target == null ? null : card.target.name();
        view.hasTarget = card.target == AbstractCard.CardTarget.ENEMY;
        view.damage = card.damage;
        view.block = card.block;
        view.magicNumber = card.magicNumber;
        return view;
    }
}
