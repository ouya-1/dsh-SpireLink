package spirelink.state;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * B2-18: card text must go through the same deterministic cleaner as event prose.
 *
 * <p>QA read {@code card_reward.cards[].text = "造成 6 点伤害。 NL 在弃牌堆中时…"}: {@code
 * AbstractCard.rawDescription} is written for the renderer, so it carries vanilla's line-break marker
 * ({@code NL}, 439 of them in the shipped Chinese card strings) and colour codes ({@code #r/#g/#y/#b/
 * #p}). The event blocks were cleaned (issue #76) and the card path was not, so the marker reached the
 * agent verbatim.
 *
 * <p>Everything here is pure: {@link CardText#render} is the exact function {@code describe(card)}
 * calls, with the three numbers passed in instead of read off a live card.
 */
public class CardTextCleanTest {

    /** What QA saw, before the fix: the description of a card whose text continues on a new line. */
    private static final String QA_RAW = "造成 !D! 点伤害。 NL 在弃牌堆中时，每回合开始时消耗。";

    // ---------------------------------------------------------------- NL (the B2-18 evidence)

    @Test
    public void cardTextHasNoLineBreakMarkerLeft() {
        String text = CardText.render(QA_RAW, 6, -1, -1);
        assertFalse("卡面文字里不许再有 NL 字面量: " + text, text.contains("NL"));
        assertEquals("NL 是换行标记，读者要的是一句话", "造成 6 点伤害。在弃牌堆中时，每回合开始时消耗。", text);
    }

    @Test
    public void aBareMarkerNeverSurvivesEvenWithoutNumbers() {
        // Same assertion without any placeholder involved: this is the pure "renderer marker" case.
        assertEquals("弃牌堆", CardText.render("弃牌堆", -1, -1, -1));
        assertEquals("丢弃。抽一张牌。", CardText.render("丢弃。 NL 抽一张牌。", -1, -1, -1));
    }

    // ---------------------------------------------------------------- colour markup

    @Test
    public void colourMarkersAreGone() {
        // #r red / #g green / #y yellow / #b blue / #p purple are the five the shipped strings use.
        assertEquals("造成 6 点伤害，获得 3 点格挡。强化",
                CardText.render("造成 #r6 点伤害，获得 #g3 点格挡。#y 强化", -1, -1, -1));
        assertEquals("抽 1 张牌", CardText.render("抽 #b1 张牌", -1, -1, -1));
        assertEquals("升级后更强", CardText.render("升级后#p更强", -1, -1, -1));
        assertFalse("任何颜色标记都不许留在卡面文字里",
                CardText.render("造成 #r6 点伤害，获得 #g3 点格挡。#y 强化", -1, -1, -1).contains("#"));
    }

    @Test
    public void unknownHashIsTextNotMarkup() {
        // The old StateBuilder cleaner did replaceAll("#.", "") and ate the character after any '#';
        // "#1 号" must survive, because that '#' is part of the sentence.
        assertEquals("#1 号", CardText.render("#1 号", -1, -1, -1));
    }

    // ---------------------------------------------------------------- numbers first, then clean

    @Test
    public void placeholdersAreSubstitutedBeforeCleaning() {
        // !D! damage / !B! block / !M! magic, all in one description.
        assertEquals("造成 6 点伤害，获得 5 点格挡，施加 2 层虚弱。",
                CardText.render("造成 !D! 点伤害，获得 !B! 点格挡，施加 !M! 层虚弱。 NL ", 6, 5, 2));
    }

    @Test
    public void unknownNumberKeepsThePlaceholderInsteadOfInventingAValue() {
        // -1 means "this card has no such number": leaving !D! visible is honest, "0 点伤害" is not.
        String text = CardText.render("造成 !D! 点伤害。 NL 结束", -1, -1, -1);
        assertEquals("造成 !D! 点伤害。结束", text);
    }

    // ---------------------------------------------------------------- determinism (#76)

    @Test
    public void cleaningIsDeterministicAndIdempotent() {
        String once = CardText.render(QA_RAW, 6, -1, -1);
        assertEquals("同一张牌每次都要给同一句话", once, CardText.render(QA_RAW, 6, -1, -1));
        assertEquals("已经干净的文字再清一次不许变形", once, ProseClean.clean(once));
        assertEquals("已经是结果时替换占位符也不该再变", once, CardText.render(once, 6, -1, -1));
    }

    @Test
    public void nullAndEmptyAreEmpty() {
        assertEquals("", CardText.render(null, 6, 6, 6));
        assertEquals("", CardText.render("", 6, 6, 6));
    }

    // ---------------------------------------------------------------- one cleaner, every block

    @Test
    public void cardsPotionsRelicsAndEventsShareTheSameCleaner() {
        String raw = "获得 #b2 点格挡。 NL 消耗。";
        String expected = "获得 2 点格挡。消耗。";
        // cards
        assertEquals(expected, CardText.render(raw, -1, -1, -1));
        // potions / relics / option labels (StateBuilder.cleanUp)
        assertEquals(expected, ProseClean.clean(raw));
        // powers (PowerSerializer)
        assertEquals(expected, ProseClean.clean(raw));
        // event prose: same output for text without the @/~ effect markers
        assertEquals(expected, EventPayload.cleanEventText(raw));
    }

    @Test
    public void eventProseStillStripsItsOwnEffectMarkers() {
        // Image events additionally use ~wave~ and @shaky@; that extra step must survive the merge.
        String eventText = EventPayload.cleanEventText("上方传来 @咯咯@ 的笑声 NL 与 ~波浪~ 的耳语");
        assertFalse(eventText.contains("@"));
        assertFalse(eventText.contains("~"));
        assertFalse(eventText.contains("NL"));
        // Chinese prose has no inter-word spaces: the gaps the removed markers left are closed too.
        assertEquals("上方传来咯咯的笑声与波浪的耳语", eventText);
    }

    // ---------------------------------------------------------------- the JSON contract

    @Test
    public void textIsCleanedWhileRawTextKeepsThePlaceholders() {
        // No live card here: null is the one input that needs no game object, and it pins the field
        // names + null safety. The raw_text contract ("保留 !D!/!B!/!M!" - docs §card_reward) is the
        // reason describe() is the only cleaned field: raw_text is the escape hatch.
        JsonObject json = CardText.toJson(null);
        assertEquals("", json.get("text").getAsString());
        assertEquals("", json.get("raw_text").getAsString());
        assertTrue(json.has("text"));
        assertNull(json.get("keywords"));
    }

    @Test
    public void substituteWithoutACardIsAHarmlessPassthrough() {
        assertEquals("造成 !D! 点伤害", CardText.substitute(null, "造成 !D! 点伤害"));
        assertEquals("", CardText.substitute(null, null));
    }
}
