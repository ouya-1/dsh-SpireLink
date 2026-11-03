package spirelink.state;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * C3-01: relic text must go through the same cleaner as cards, potions and event prose.
 *
 * <p>QA read {@code relics[].text = "回复 #b6 点生命"} (Burning Blood) and {@code "获得 #y【力量】"}
 * (Girya): the relic block handed {@code AbstractRelic.description} to the glossary without cleaning
 * it, and the boss-relic block kept its own copy of the rule - so one relic could answer two different
 * texts depending on the screen it was read from.
 *
 * <p>The samples below are the shipped Chinese strings (localization/zhs/relics.json), not invented
 * ones: Burning Blood builds its description from {@code DESCRIPTIONS[0] + counter + DESCRIPTIONS[1]},
 * i.e. exactly "在战斗结束时，回复 #b6 点生命。" at runtime; Girya really carries " #y力量  " with two
 * spaces around the marker; Nullstone Periapt really carries an {@code NL}.
 *
 * <p>Pure: {@link RelicText} takes the description string, no game object needed.
 */
public class RelicTextCleanTest {

    /** Burning Blood, as it exists at runtime (counter substituted by the relic itself). */
    private static final String BURNING_BLOOD = "在战斗结束时，回复 #b6 点生命。";

    /** Girya, verbatim from the shipped localization (note the doubled space around the marker). */
    private static final String GIRYA = "你现在能在休息处获得 #y力量  。（最多3次）";

    /** Nullstone Periapt: a relic description that really contains the line-break marker. */
    private static final String NULLSTONE = "Negate the next #yDebuff you receive from an enemy. NL "
            + "Recharges in #b10 turns。";

    // ---------------------------------------------------------------- the C3-01 evidence

    @Test
    public void relicTextHasNoColourMarkers() {
        assertEquals("Burning Blood 不许再带 #b",
                "在战斗结束时，回复 6 点生命。", RelicText.describe(BURNING_BLOOD));
        assertEquals("Girya 不许再带 #y（标记两侧的空格也要一起收掉）",
                "你现在能在休息处获得力量。（最多3次）", RelicText.describe(GIRYA));
        assertFalse("遗物文本里不许留任何 # 颜色标记",
                RelicText.describe(BURNING_BLOOD + GIRYA + NULLSTONE).contains("#"));
    }

    @Test
    public void nlInRelicTextIsRemoved() {
        // 6 of the shipped Chinese relic strings carry NL; one line is what a reader wants.
        String text = RelicText.describe(NULLSTONE);
        assertFalse("遗物文本里不许再有 NL 字面量: " + text, text.contains("NL"));
        assertEquals("Negate the next Debuff you receive from an enemy. Recharges in 10 turns。",
                text);
    }

    @Test
    public void unknownHashIsStillText() {
        // Same guard as the card path: the '#' in "#1 号" is part of the sentence, not markup.
        assertEquals("#1 号", RelicText.describe("#1 号"));
    }

    // ---------------------------------------------------------------- determinism (#76)

    @Test
    public void relicTextIsDeterministicAndIdempotent() {
        String once = RelicText.describe(BURNING_BLOOD);
        assertEquals("同一个遗物每次都要给同一句话", once, RelicText.describe(BURNING_BLOOD));
        assertEquals("已经干净的文字再清一次不许变形", once, RelicText.describe(once));
        assertEquals("结果再走一遍完整管线也不许变", once, ProseClean.clean(once));
    }

    @Test
    public void nullAndEmptyAreEmpty() {
        assertEquals("", RelicText.describe(null));
        assertEquals("", RelicText.describe(""));
    }

    // ---------------------------------------------------------------- one cleaner for all prose

    @Test
    public void relicsUseTheSameCleanerAsCards() {
        // Pins "同一套": a marker string must come out identical on the card and the relic path.
        assertEquals(CardText.render(BURNING_BLOOD, -1, -1, -1), RelicText.describe(BURNING_BLOOD));
        assertEquals(ProseClean.clean(GIRYA), RelicText.describe(GIRYA));
    }

    // ---------------------------------------------------------------- what the block emits

    @Test
    public void putTextWritesOnlyTheCleanedDisplayText() {
        JsonObject relic = new JsonObject();
        relic.addProperty("id", "Burning Blood");
        RelicText.putText(relic, BURNING_BLOOD);

        assertNotNull("text 必须有", relic.get("text"));
        assertEquals("在战斗结束时，回复 6 点生命。", relic.get("text").getAsString());
        assertFalse("整条遗物 JSON 里不许出现颜色标记", relic.toString().contains("#"));
        assertFalse("整条遗物 JSON 里不许出现 NL", relic.toString().contains("NL"));
        // Contract: only the display text is cleaned; no raw copy is invented here (raw-style fields
        // are the ones that keep the original, and the relic block emits none).
        assertFalse("不许顺手加一个 raw 字段", relic.has("raw_text"));
        if (relic.has("keywords")) {
            assertFalse("keywords 里也不许有标记", relic.get("keywords").toString().contains("#"));
        }
    }

    @Test
    public void putTextAlwaysWritesTheKeySoBothRelicScreensAgree() {
        // Player relics and the boss-relic screen must answer the same shape for the same relic.
        JsonObject missing = new JsonObject();
        RelicText.putText(missing, null);
        assertTrue("description 缺失时也要有 text 这个键（否则两个界面形状不一致）",
                missing.has("text"));
        assertEquals("", missing.get("text").getAsString());
        // null target must not throw: these builders run on the main thread.
        RelicText.putText(null, BURNING_BLOOD);
    }
}
