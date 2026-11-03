package spirelink.state;

import com.google.gson.JsonObject;

/**
 * Relic descriptions as a human reads them.
 *
 * <p>C3-01: {@code relics[].text} still carried the renderer's colour instructions - QA read
 * "回复 #b6 点生命" (Burning Blood) and "获得 #y【力量】" (Girya). Cards, potions, powers and event
 * prose were already going through {@link ProseClean}; the relic path was the last one that handed
 * {@code AbstractRelic.description} straight to {@link spirelink.util.Glossary}, and the boss-relic
 * block kept its own local copy of the cleaning. The same relic therefore answered two different
 * texts depending on which screen it was read from.
 *
 * <p>Both relic blocks now go through {@link #describe(String)} / {@link #putText(JsonObject, String)},
 * so there is exactly one relic-text rule: renderer markup out ({@code NL}, {@code #b}/{@code #y}/
 * {@code #r}/{@code #g}/{@code #p}), {@link spirelink.util.Glossary} terms marked afterwards.
 *
 * <p>Raw-style fields keep the original text on purpose (documented contract) - only the display
 * {@code text} is cleaned, and no raw field is invented here.
 */
public final class RelicText {

    private RelicText() {
    }

    /** Display text for a relic: the same deterministic cleaner cards, potions and events use. */
    public static String describe(String rawDescription) {
        return ProseClean.clean(rawDescription);
    }

    /**
     * Writes {@code text} (plus {@code keywords} when the glossary marks a term) for one relic entry.
     *
     * <p>Always writes {@code text}, so a relic entry has the same shape on every screen; a null
     * description answers {@code ""} instead of dropping the key.
     */
    public static void putText(JsonObject out, String rawDescription) {
        if (out == null) {
            return;
        }
        spirelink.util.Glossary.addMarked(out, "text", describe(rawDescription));
    }
}
