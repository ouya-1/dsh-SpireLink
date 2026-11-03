package spirelink.ui;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * HUD buffer behaviour (design P0.5).
 *
 * <p>Only touches {@link HudModel}/{@link AgentHud}: no Slay the Spire or libgdx class is loaded,
 * so this runs as a plain JUnit test without a game runtime.
 */
public class AgentHudTest {

    @Test
    public void keepsOnlyConfiguredNumberOfLines() {
        HudModel model = new HudModel();
        model.configure(4);
        model.push("line-1");
        model.push("line-2");
        model.push("line-3");
        model.push("line-4");
        model.push("line-5");
        assertEquals(4, model.size());
        assertEquals("line-2", model.texts().get(0));
        assertEquals("line-5", model.texts().get(3));
    }

    @Test
    public void newestLineIsLastWithAgeZero() {
        HudModel model = new HudModel();
        model.configure(3);
        model.push("a");
        model.push("b");
        List<HudModel.Line> lines = model.lines();
        assertEquals(2, lines.size());
        assertEquals("a", lines.get(0).text);
        assertEquals(1, lines.get(0).age);
        assertEquals("b", lines.get(1).text);
        assertEquals(0, lines.get(1).age);
    }

    @Test
    public void longTextIsTruncatedWithEllipsis() {
        HudModel model = new HudModel();
        String longText = "看局面 v12 并且这一行非常非常长需要被截断掉";
        assertTrue(longText.length() > HudModel.MAX_CHARS);
        model.push(longText);
        String stored = model.texts().get(0);
        assertEquals(HudModel.MAX_CHARS, stored.length());
        assertTrue(stored.endsWith(HudModel.ELLIPSIS));
        assertEquals(longText.substring(0, HudModel.MAX_CHARS - 1),
                stored.substring(0, HudModel.MAX_CHARS - 1));
    }

    @Test
    public void blankTextIsIgnored() {
        HudModel model = new HudModel();
        assertFalse(model.push(null));
        assertFalse(model.push(""));
        assertFalse(model.push("   \t "));
        assertEquals(0, model.size());
        assertTrue(model.lines().isEmpty());
    }

    @Test
    public void lineCountIsClampedToRange() {
        HudModel model = new HudModel();
        model.configure(99);
        assertEquals(HudModel.MAX_LINES, model.capacity());
        model.configure(0);
        assertEquals(HudModel.MIN_LINES, model.capacity());
        model.configure(-3);
        assertEquals(HudModel.MIN_LINES, model.capacity());
        model.configure(4);
        assertEquals(4, model.capacity());
    }

    @Test
    public void shrinkingCapacityDropsOldestLines() {
        HudModel model = new HudModel();
        model.configure(6);
        for (int i = 1; i <= 6; i++) {
            model.push("m" + i);
        }
        assertEquals(6, model.size());
        model.configure(2);
        assertEquals(2, model.size());
        assertEquals("m5", model.texts().get(0));
        assertEquals("m6", model.texts().get(1));
    }

    @Test
    public void normalizeCollapsesLineBreaks() {
        assertEquals("a b", HudModel.normalize("a\nb"));
        assertEquals("a b", HudModel.normalize("  a\r b  "));
        assertNull(HudModel.normalize("   "));
    }

    @Test
    public void consecutiveDuplicatesMergeIntoOneRow() {
        HudModel model = new HudModel();
        model.configure(6);
        model.push("Agent 已连接");
        model.push("Agent 已连接");
        model.push("Agent 已连接");
        assertEquals(1, model.size());
        HudModel.Line line = model.lines().get(0);
        assertEquals("Agent 已连接", line.text);
        assertEquals(3, line.count);
        assertEquals("Agent 已连接 ×3", line.display());
    }

    @Test
    public void nonAdjacentDuplicatesAreNotMerged() {
        HudModel model = new HudModel();
        model.configure(6);
        model.push("Agent 已连接");
        model.push("看局面 v3");
        model.push("Agent 已连接");
        assertEquals(3, model.size());
        assertEquals("Agent 已连接", model.lines().get(0).text);
        assertEquals("Agent 已连接", model.lines().get(2).text);
        assertEquals(1, model.lines().get(2).count);
        assertEquals("Agent 已连接", model.lines().get(2).display());
    }

    @Test
    public void mergedRowStillCountsAsOneLineForCapacity() {
        HudModel model = new HudModel();
        model.configure(2);
        model.push("a");
        model.push("a");
        model.push("b");
        model.push("c");
        assertEquals(2, model.size());
        assertEquals("b", model.texts().get(0));
        assertEquals("c", model.texts().get(1));
    }

    @Test
    public void idleAlphaFactorHasThreeTiers() {
        assertEquals(1.0f, HudModel.idleAlphaFactor(0L), 0.0001f);
        assertEquals(1.0f, HudModel.idleAlphaFactor(HudModel.IDLE_DIM_MILLIS), 0.0001f);
        assertEquals(0.55f, HudModel.idleAlphaFactor(HudModel.IDLE_DIM_MILLIS + 1), 0.0001f);
        assertEquals(0.55f, HudModel.idleAlphaFactor(HudModel.IDLE_FAINT_MILLIS), 0.0001f);
        assertEquals(0.30f, HudModel.idleAlphaFactor(HudModel.IDLE_FAINT_MILLIS + 1), 0.0001f);
    }

    @Test
    public void lineAlphaRampMatchesDesign() {
        assertEquals(1.00f, HudModel.lineAlpha(0), 0.0001f);
        assertEquals(0.80f, HudModel.lineAlpha(1), 0.0001f);
        assertEquals(0.60f, HudModel.lineAlpha(2), 0.0001f);
        assertEquals(0.45f, HudModel.lineAlpha(3), 0.0001f);
        assertEquals(0.32f, HudModel.lineAlpha(4), 0.0001f);
        assertEquals(0.32f, HudModel.lineAlpha(9), 0.0001f);
    }

    @Test
    public void idleMillisUsesInjectedClock() {
        FakeClock clock = new FakeClock(1000L);
        HudModel model = new HudModel(clock);
        assertEquals(Long.MAX_VALUE, model.idleMillis());

        model.push("Agent 已连接");
        assertEquals(1000L, model.lastPushMillis());
        assertEquals(0L, model.idleMillis());

        clock.advance(8000L);
        assertEquals(8000L, model.idleMillis());
        assertEquals(1.0f, HudModel.idleAlphaFactor(model.idleMillis()), 0.0001f);

        clock.advance(12_001L);
        assertEquals(HudModel.IDLE_FAINT_FACTOR, HudModel.idleAlphaFactor(model.idleMillis()), 0.0001f);

        model.push("看局面 v7");
        assertEquals(0L, model.idleMillis());
        assertEquals(1.0f, HudModel.idleAlphaFactor(model.idleMillis()), 0.0001f);
    }

    @Test
    public void blankPushDoesNotRefreshIdleTimer() {
        FakeClock clock = new FakeClock(500L);
        HudModel model = new HudModel(clock);
        model.push("看局面 v1");
        clock.advance(9000L);
        assertFalse(model.push("   "));
        assertEquals(9000L, model.idleMillis());
    }

    private static final class FakeClock implements HudModel.Clock {
        private long now;

        FakeClock(long start) {
            this.now = start;
        }

        void advance(long millis) {
            this.now += millis;
        }

        @Override
        public long nowMillis() {
            return now;
        }
    }

    @Test
    public void staticFacadeFeedsSameBuffer() {
        AgentHud.configure(true, 4);
        AgentHud.clear();
        assertTrue(AgentHud.isEnabled());
        AgentHud.push("Agent 已连接");
        AgentHud.push("看局面 v3");
        AgentHud.action("打出 打击");
        assertEquals(3, AgentHud.lines().size());
        assertEquals("打出 打击", AgentHud.lines().get(2).text);
        assertEquals(0, AgentHud.lines().get(2).age);

        AgentHud.configure(false, 4);
        assertFalse(AgentHud.isEnabled());
        AgentHud.push("关掉后不该再进缓冲");
        assertEquals(3, AgentHud.lines().size());

        // restore defaults for other tests in this JVM
        AgentHud.configure(true, HudModel.DEFAULT_LINES);
        AgentHud.clear();
    }
}
