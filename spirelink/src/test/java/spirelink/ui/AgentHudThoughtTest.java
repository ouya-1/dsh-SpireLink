package spirelink.ui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 思考上屏（issue #28）: the {@link AgentHud} thought buffer and the {@link AgentHudRenderer} rows.
 *
 * <p>No Slay the Spire class is touched: the clock is injected through
 * {@link AgentHud#setClock(HudModel.Clock)}, so TTL/fade behaviour is asserted without sleeping.
 */
public class AgentHudThoughtTest {

    /** Drives the HUD timers by hand so a 12 s TTL costs no wall-clock time. */
    private static final class FakeClock implements HudModel.Clock {
        private long now = 1000L;

        void advance(long millis) {
            now += millis;
        }

        @Override
        public long nowMillis() {
            return now;
        }
    }

    private FakeClock clock;

    @Before
    public void setUp() {
        clock = new FakeClock();
        AgentHud.configure(true, 4);
        AgentHud.setClock(clock);
        AgentHud.clear();
    }

    @After
    public void tearDown() {
        AgentHud.setClock(null);
        AgentHud.clear();
        AgentHud.configure(true, HudModel.DEFAULT_LINES);
    }

    @Test
    public void thoughtIsShownUntilItsTtlRunsOut() {
        assertTrue(AgentHud.thought("先打打击，再看它加不加力量", 5000L));
        assertEquals("先打打击，再看它加不加力量", AgentHud.thoughtText());
        assertEquals(1.0f, AgentHud.thoughtAlpha(), 0.001f);
        assertEquals(1000L, AgentHud.thoughtStartedAt());

        clock.advance(3500L); // 1500 ms left: exactly at the start of the fade window
        assertEquals(1.0f, AgentHud.thoughtAlpha(), 0.001f);
        assertNotNull(AgentHud.thoughtText());

        clock.advance(750L); // 750 ms left -> alpha 0.5
        assertEquals(0.5f, AgentHud.thoughtAlpha(), 0.02f);

        clock.advance(750L); // expired: the block disappears and the feed gets its rows back
        assertNull(AgentHud.thoughtText());
        assertEquals(0f, AgentHud.thoughtAlpha(), 0.001f);
    }

    @Test
    public void blankThoughtKeepsThePreviousOne() {
        AgentHud.thought("第一条思考", 5000L);
        clock.advance(1000L);

        assertFalse(AgentHud.thought("   \n\t ", 5000L));
        assertFalse(AgentHud.thought(null, 5000L));

        // "这一步没有思考时保留上一条，不要清屏" (issue #28)
        assertEquals("第一条思考", AgentHud.thoughtText());
        assertEquals(1000L, AgentHud.thoughtStartedAt());
    }

    @Test
    public void zeroTtlFallsBackToTheDefault() {
        AgentHud.thought("默认 TTL", 0L);
        clock.advance(AgentHud.DEFAULT_THOUGHT_TTL_MS - 100L);
        assertNotNull(AgentHud.thoughtText());
        clock.advance(200L);
        assertNull(AgentHud.thoughtText());
    }

    @Test
    public void tinyTtlIsClampedToTheMinimum() {
        AgentHud.thought("很短", 1L);
        clock.advance(1400L);
        assertNotNull(AgentHud.thoughtText());
        clock.advance(200L);
        assertNull(AgentHud.thoughtText());
    }

    @Test
    public void thoughtIsFlattenedAndCapped() {
        assertEquals("a b c", AgentHud.normalizeThought("  a\n\n b\tc  "));
        assertNull(AgentHud.normalizeThought("   \t "));
        assertNull(AgentHud.normalizeThought(null));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append('长');
        }
        String capped = AgentHud.normalizeThought(sb.toString());
        assertEquals(AgentHud.THOUGHT_MAX_CHARS, capped.length());
        assertTrue(capped.endsWith(HudModel.ELLIPSIS));

        // Reasoning arrives as multi-line markdown; the HUD block has three flat rows.
        AgentHud.thought("第一行\n第二行\n第三行", 5000L);
        assertEquals("第一行 第二行 第三行", AgentHud.thoughtText());
    }

    @Test
    public void clearDropsTheThought() {
        AgentHud.thought("还在想", 5000L);
        AgentHud.clear();
        assertNull(AgentHud.thoughtText());
        assertEquals(0f, AgentHud.thoughtAlpha(), 0.001f);
        assertEquals(0L, AgentHud.thoughtStartedAt());
    }

    @Test
    public void disabledHudIgnoresThoughts() {
        AgentHud.configure(false, 4);
        assertFalse(AgentHud.thought("关掉时不该出现", 5000L));
        assertNull(AgentHud.thoughtText());
    }

    @Test
    public void thoughtNeverEntersTheActivityFeed() {
        AgentHud.push("Agent 已连接");
        AgentHud.thought("思考不进活动流", 5000L);
        assertEquals(1, AgentHud.lines().size());
        assertEquals("Agent 已连接", AgentHud.lines().get(0).text);
    }

    @Test
    public void thoughtWrapsIntoAtMostThreeRowsOf32() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("这是一句很长的思考");
        }
        List<String> rows = AgentHudRenderer.thoughtLines(sb.toString());
        assertEquals(AgentHudRenderer.THOUGHT_MAX_LINES, rows.size());
        for (String row : rows) {
            assertTrue(row.length() <= AgentHudRenderer.THOUGHT_CHARS_PER_LINE);
        }
        assertTrue(rows.get(rows.size() - 1).endsWith(HudModel.ELLIPSIS));

        List<String> shortThought = AgentHudRenderer.thoughtLines("先打打击");
        assertEquals(1, shortThought.size());
        assertEquals("先打打击", shortThought.get(0));
        assertTrue(AgentHudRenderer.thoughtLines(null).isEmpty());
        assertTrue(AgentHudRenderer.thoughtLines("   ").isEmpty());
    }

    /** The layout contract from issue #28: 2-3 rows x 26-40 chars, block above the feed. */
    @Test
    public void layoutFitsTheIssueBudgetAndLeavesRoomForTheFeed() {
        assertTrue(AgentHudRenderer.THOUGHT_MAX_LINES >= 2);
        assertTrue(AgentHudRenderer.THOUGHT_MAX_LINES <= 3);
        assertTrue(AgentHudRenderer.THOUGHT_CHARS_PER_LINE >= 26);
        assertTrue(AgentHudRenderer.THOUGHT_CHARS_PER_LINE <= 40);

        // Worst case: a full thought block plus a full feed must still fit above mid-screen.
        float blockHeight = AgentHudRenderer.THOUGHT_MAX_LINES * AgentHudRenderer.LINE_HEIGHT;
        float feedTop = AgentHudRenderer.THOUGHT_TOP + blockHeight + AgentHudRenderer.THOUGHT_GAP;
        float feedBottom = feedTop + HudModel.MAX_LINES * AgentHudRenderer.LINE_HEIGHT;
        assertTrue("活动流必须在思考块下方", feedTop > AgentHudRenderer.THOUGHT_TOP);
        assertTrue("两块加起来不能压到 720p 屏幕的下半部分", feedBottom < 720f / 2f);
    }

    /**
     * 乱码排查的结论（issue #28 二次验收）：思考块和活动流必须是**同一个字体对象、同一字号、
     * 同一行高、同一描边**，只有每行字数预算不同。没有游戏环境也能断言这件事，因为它被抽成了
     * {@link AgentHudRenderer.TextStyle}：真实绘制时两边都走 {@code hudFont()} 拿到的那个
     * {@code FontHelper.cardDescFont_N}，都走 {@code drawOutlined(...)} + {@code measure(...)}。
     */
    @Test
    public void thoughtBlockUsesTheSameFontAndStrokeAsTheActivityFeed() {
        AgentHudRenderer.TextStyle feed = AgentHudRenderer.FEED_STYLE;
        AgentHudRenderer.TextStyle thought = AgentHudRenderer.THOUGHT_STYLE;

        assertTrue("思考块必须和活动流同字体/同字号/同行高/同描边", thought.sameFontAndStroke(feed));
        assertEquals("FontHelper.cardDescFont_N", AgentHudRenderer.FONT_RESOURCE);
        assertEquals(AgentHudRenderer.FONT_RESOURCE, thought.fontResource);
        assertEquals(1.0f, thought.fontScale, 0.0001f);
        assertEquals(24f, thought.lineHeight, 0.0001f);
        assertEquals(AgentHudRenderer.OUTLINE_OFFSET, thought.outlineOffset, 0.0001f);
        assertEquals(AgentHudRenderer.OUTLINE_ALPHA, thought.outlineAlpha, 0.0001f);

        // 唯一允许不同的：每行字数预算（活动流 20 字，思考块 32 字）
        assertEquals(HudModel.MAX_CHARS, feed.charsPerLine);
        assertEquals(AgentHudRenderer.THOUGHT_CHARS_PER_LINE, thought.charsPerLine);
        assertEquals(32, thought.charsPerLine);
        assertFalse(thought.toString(), thought.toString().isEmpty());
    }
}
