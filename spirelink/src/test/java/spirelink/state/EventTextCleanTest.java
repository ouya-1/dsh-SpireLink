package spirelink.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #76: the same event screen must answer with the same {@code event.body} every time.
 *
 * <p>QA saw two different bodies from two consecutive {@code get_state} calls of one BigFish screen:
 * one with vanilla's render markers ({@code #y}, {@code ~}, {@code @}, {@code NL}) and one with a
 * space between every character. Two sources (the event's own {@code body} field and the live dialog
 * word list) were formatted differently and neither was cleaned. The tests below pin the rule:
 * <b>deterministic post-processing, no markers, no per-character spacing</b>.
 */
public class EventTextCleanTest {

    /** What QA got the first time: the event's raw body field, markers and all. */
    private static final String RAW =
            "当你走过一条长廊时，你看见空中 ~漂浮~ 着 #y一根香蕉 ， #y一个甜甜圈 ，"
                    + "上方似乎传来一阵 @咯咯@ 的笑声。 NL 你会怎么做？";

    /** What QA got the second time: the dialog's word list joined with a space after every word. */
    private static final String SPACED =
            "当 你 走 过 一 条 长 廊 时 ， 你 看 见 空 中 漂 浮 着 一 根 香 蕉 ， 一 个 甜 甜 圈 ，"
                    + " 上 方 似 乎 传 来 一 阵 咯 咯 的 笑 声 。 你 会 怎 么 做 ？";

    private static final String EXPECTED =
            "当你走过一条长廊时，你看见空中漂浮着一根香蕉，一个甜甜圈，上方似乎传来一阵咯咯的笑声。你会怎么做？";

    // ---------------------------------------------------------------- determinism

    @Test
    public void bothSourcesNormalizeToTheSameText() {
        assertEquals("#76: 两种来源必须清洗成同一段文字",
                EventPayload.cleanEventText(RAW), EventPayload.cleanEventText(SPACED));
        assertEquals(EXPECTED, EventPayload.cleanEventText(RAW));
    }

    @Test
    public void cleaningTwiceChangesNothing() {
        String once = EventPayload.cleanEventText(RAW);
        assertEquals("同一对象 → 同一输出（幂等）", once, EventPayload.cleanEventText(once));
        assertEquals(once, EventPayload.cleanEventText(once));
    }

    @Test
    public void noRenderMarkersSurvive() {
        String clean = EventPayload.cleanEventText(RAW);
        assertFalse("不能留 #y 颜色标记", clean.contains("#y"));
        assertFalse("不能留 # 类标记", clean.contains("#"));
        assertFalse("不能留 NL 换行标记", clean.contains("NL"));
        assertFalse("不能留 @ 抖动标记", clean.contains("@"));
        assertFalse("不能留 ~ 波浪标记", clean.contains("~"));
    }

    @Test
    public void noPerCharacterSpacing() {
        String clean = EventPayload.cleanEventText(SPACED);
        assertFalse("中文字符之间不许被插入空格", clean.contains("当 你"));
        assertFalse(clean.contains(" ，"));
        assertTrue("句子仍然是完整的一段", clean.length() > 30);
    }

    @Test
    public void latinTextKeepsItsSpaces() {
        // The cleaner must not glue English words together: those spaces are real.
        assertEquals("Your HP is now 5 .", EventPayload.cleanEventText("Your #yHP is now #b5 ."));
    }

    @Test
    public void nullAndEmptyAreEmpty() {
        assertEquals("", EventPayload.cleanEventText(null));
        assertEquals("", EventPayload.cleanEventText(""));
    }

    // ---------------------------------------------------------------- dialog word joining

    @Test
    public void chineseWordsAreJoinedWithoutSpaces() {
        // GenericEventDialog stores one DialogWord per character for Chinese text.
        StringBuilder sb = new StringBuilder();
        for (char c : "当你走过".toCharArray()) {
            EventPayload.appendDialogWord(sb, String.valueOf(c));
        }
        assertEquals("当你走过", sb.toString());
    }

    @Test
    public void latinWordsKeepASingleSpace() {
        StringBuilder sb = new StringBuilder();
        EventPayload.appendDialogWord(sb, "The");
        EventPayload.appendDialogWord(sb, "big");
        EventPayload.appendDialogWord(sb, "fish");
        assertEquals("The big fish", sb.toString());
    }

    @Test
    public void mixedTextDoesNotGlueAnAsciiTokenToChinese() {
        StringBuilder sb = new StringBuilder();
        EventPayload.appendDialogWord(sb, "获得");
        EventPayload.appendDialogWord(sb, "5");
        EventPayload.appendDialogWord(sb, "点");
        assertEquals("获得5点", sb.toString());
    }

    @Test
    public void emptyWordsAreSkipped() {
        StringBuilder sb = new StringBuilder();
        EventPayload.appendDialogWord(sb, null);
        EventPayload.appendDialogWord(sb, "");
        EventPayload.appendDialogWord(sb, "好");
        assertEquals("好", sb.toString());
    }

    @Test
    public void wideClassificationMatchesTheRule() {
        assertTrue(EventPayload.isWide('你'));
        assertTrue(EventPayload.isWide('，'));
        assertTrue(EventPayload.isWide('。'));
        assertFalse(EventPayload.isWide('a'));
        assertFalse(EventPayload.isWide('5'));
        assertFalse(EventPayload.isWide(' '));
    }
}
