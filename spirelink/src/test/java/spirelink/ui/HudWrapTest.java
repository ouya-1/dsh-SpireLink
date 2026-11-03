package spirelink.ui;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The top-right note block wraps instead of truncating. */
public class HudWrapTest {

    @Test
    public void shortTextStaysOneLine() {
        List<String> lines = AgentHudRenderer.wrap("记下：大颚虫会加力量", 26, 3);
        assertEquals(1, lines.size());
        assertEquals("记下：大颚虫会加力量", lines.get(0));
    }

    @Test
    public void longTextWrapsIntoAtMostThreeLines() {
        String text = "记下：地精大块头会先加力量再用重击，看到他抬手就要留格挡牌，否则两下就死了";
        List<String> lines = AgentHudRenderer.wrap(text, 26, 3);
        assertTrue(lines.size() <= 3);
        for (String line : lines) {
            assertTrue(line.length() <= 26);
        }
    }

    @Test
    public void textBeyondThreeLinesGetsEllipsis() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append("这是很长的一段笔记内容");
        }
        List<String> lines = AgentHudRenderer.wrap(sb.toString(), 26, 3);
        assertEquals(3, lines.size());
        assertTrue(lines.get(2).endsWith("…"));
    }

    @Test
    public void blankTextYieldsNothing() {
        assertTrue(AgentHudRenderer.wrap(null, 26, 3).isEmpty());
        assertTrue(AgentHudRenderer.wrap("   ", 26, 3).isEmpty());
    }
}
