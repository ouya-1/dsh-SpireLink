package spirelink.state;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.AbstractCard;

/**
 * Card descriptions as a human reads them.
 *
 * <p>{@code rawDescription} is written for the renderer, not for a reader: it keeps the engine's
 * placeholders ({@code !D!} / {@code !B!} / {@code !M!}, only replaced while the card renders) and
 * vanilla's markup ({@code NL} line breaks, {@code #y}/{@code #r}/{@code #g}/{@code #b}/{@code #p}
 * colour codes). Both used to leak: "造成 !D! 点伤害" was guesswork, and B2-18 showed the other half -
 * {@code card_reward.cards[].text = "造成 6 点伤害。 NL 在弃牌堆中时…"} with the line-break marker
 * still in it, because nothing on the card path called the cleaner the event blocks use.
 *
 * <p>{@link #describe(AbstractCard)} is now "substitute the numbers, then the one deterministic
 * cleaner" ({@link ProseClean}). Every card text the agent can see goes through it: card rewards,
 * upgraded previews, hand/draw/discard/exhaust views. The rule itself lives in
 * {@link #render(String, int, int, int)} so it is unit tested without a game object.
 */
public final class CardText {

    private CardText() {
    }

    /** Description with live numbers substituted and renderer markup removed. */
    public static String describe(AbstractCard card) {
        if (card == null) {
            return "";
        }
        return render(card.rawDescription, damageOf(card), blockOf(card), magicOf(card));
    }

    /**
     * {@code {text, raw_text}} - {@code raw_text} stays raw on purpose (documented contract: it keeps
     * {@code !D!/!B!/!M!}, and is the escape hatch when a number is genuinely unknown).
     */
    public static JsonObject toJson(AbstractCard card) {
        JsonObject out = new JsonObject();
        out.addProperty("text", describe(card));
        out.addProperty("raw_text", card == null || card.rawDescription == null
                ? "" : card.rawDescription);
        return out;
    }

    /**
     * The whole card-text rule, pure: placeholders first, then {@link ProseClean} (order matters -
     * the substituted numbers must not be mistaken for markup).
     *
     * <p>A negative value means "this card has no such number": the placeholder is left alone rather
     * than replaced by a made-up {@code 0} or {@code -1}.
     */
    static String render(String rawText, int damage, int block, int magic) {
        return ProseClean.clean(substitute(rawText, damage, block, magic));
    }

    /** Pure substitution: {@code !D!/!B!/!M!} → the live numbers. */
    static String substitute(String text, int damage, int block, int magic) {
        if (text == null) {
            return "";
        }
        String out = text;
        if (damage >= 0) {
            out = out.replace("!D!", Integer.toString(damage));
        }
        if (block >= 0) {
            out = out.replace("!B!", Integer.toString(block));
        }
        if (magic >= 0) {
            out = out.replace("!M!", Integer.toString(magic));
        }
        return out;
    }

    /** Kept for callers that already hold a rendered string (card reward, deck, hand). */
    public static String substitute(AbstractCard card, String text) {
        if (card == null) {
            return text == null ? "" : text;
        }
        return substitute(text, damageOf(card), blockOf(card), magicOf(card));
    }

    private static int damageOf(AbstractCard card) {
        return card.damage >= 0 ? card.damage : card.baseDamage;
    }

    private static int blockOf(AbstractCard card) {
        return card.block >= 0 ? card.block : card.baseBlock;
    }

    private static int magicOf(AbstractCard card) {
        return card.magicNumber >= 0 ? card.magicNumber : card.baseMagicNumber;
    }
}
