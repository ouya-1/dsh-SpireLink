package spirelink.state;

/**
 * The one deterministic prose cleaner for everything this package hands to the agent.
 *
 * <p>Before B2-18 there were three different answers to "how do we clean a game string?":
 * event prose used the strict {@link spirelink.util.TextClean} pipeline, powers used
 * {@code TextClean} directly, and potions/relics used a looser {@code replaceAll("#.", "")} in
 * {@code StateBuilder}. Cards used <b>none of them</b> - card descriptions are
 * {@code AbstractCard.rawDescription}, which is written for the renderer - so QA read
 * {@code card_reward.cards[].text = "造成 6 点伤害。 NL 在弃牌堆中时…"} with vanilla's line-break
 * marker in the middle of the sentence.
 *
 * <p>The rules (all verified against the shipped Chinese strings: {@code cards.json} has 439
 * {@code NL} and a handful of {@code #r}; {@code relics.json} has 10 {@code NL} and 319 colour codes;
 * {@code potions.json} has 89 colour codes and no {@code NL}):
 *
 * <ul>
 *   <li>{@code NL} is a line break, not a word → one space (or nothing between CJK characters);</li>
 *   <li>{@code #y}/{@code #g}/{@code #r}/{@code #b}/{@code #p} (and {@code ##}/{@code #@}/{@code #.})
 *       are colour instructions → removed, together with the gap they leave behind;</li>
 *   <li>an unknown {@code #} is text, not markup ("#1 号" stays "#1 号");</li>
 *   <li>same input → same output, and cleaning twice changes nothing (issue #76: one object must not
 *       answer two ways).</li>
 * </ul>
 */
final class ProseClean {

    private ProseClean() {
    }

    /** Prose the renderer decorates with colour codes and line breaks (cards, potions, relics, powers). */
    static String clean(String raw) {
        return spirelink.util.TextClean.stripGameMarkup(raw);
    }

    /**
     * Event prose: image events additionally mark {@code ~wave~} and {@code @shaky@} runs, which are
     * renderer effects rather than words (issue #76).
     */
    static String cleanEvent(String raw) {
        if (raw == null) {
            return "";
        }
        return clean(raw.replace("@", "").replace("~", ""));
    }
}
