package spirelink.actions;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * #113: candidate labels in rejection messages are one line, bounded, and shaped like the labels the
 * planner puts in {@code legal_actions}.
 *
 * <p>Event option texts and card names arrive from the game with newlines, padding and sometimes
 * hundreds of characters; before this the {@code choose_option} rejection was a bare
 * {@code "选项序号越界: 9"} and had no label at all, and pasting {@code option.msg} in raw would have
 * turned a one-line rejection into an unreadable wall.</p>
 */
public class LabelTextTest {

    @Test
    public void shortTextIsKeptAsIs() {
        assertEquals("香蕉 回复 333 生命", LabelText.oneLine("香蕉 回复 333 生命"));
        assertEquals("Strike", LabelText.oneLine("Strike"));
    }

    @Test
    public void whitespaceIsCollapsedAndTrimmed() {
        assertEquals("香蕉 回复 333 生命", LabelText.oneLine("  香蕉\n\n  回复\t333   生命 "));
        assertEquals("a b", LabelText.oneLine("a\r\nb"));
    }

    /** The planner drops the square brackets for its own labels; the candidate must read the same. */
    @Test
    public void squareBracketsAreDroppedLikeThePlannerDoes() {
        assertEquals("锁定 需要：金神像", LabelText.oneLine("[锁定] 需要：金神像"));
    }

    @Test
    public void longTextIsCutToTheBoundWithAnEllipsis() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append("字");
        }
        String label = LabelText.oneLine(longText.toString());
        assertEquals(LabelText.MAX_LENGTH, label.length());
        assertTrue("要有省略号: " + label, label.endsWith("…"));
    }

    @Test
    public void nullIsAnEmptyLabelNeverACrash() {
        assertEquals("", LabelText.oneLine(null));
        assertEquals("", LabelText.oneLine("   "));
        assertEquals("", LabelText.oneLine("[]"));
    }
}