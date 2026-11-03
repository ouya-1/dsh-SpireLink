package spirelink.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;
import spirelink.util.Glossary;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #61: {@code glossary{list:true}} answered 171 entries - most of them named
 * {@code [MISSING_NAME]} with {@code [MISSING_0]} definitions - under a {@code terms} key the docs
 * do not use, and without the promised {@code by_source}.
 *
 * <p>Three rules are pinned here: placeholder markers are never emitted as names (the machine id is
 * used instead, flagged {@code name_resolved:false}), an entry with no usable name at all is dropped,
 * and a real name - even one that looks a bit like a marker, e.g. {@code MissingNo} - is never eaten.
 */
public class GlossaryPlaceholderTest {

    // ------------------------------------------------------------------ placeholder detection

    /** 各种本地化占位符形状都要认出来。 */
    @Test
    public void placeholderPatternsAreRecognised() {
        String[] bad = new String[]{"[MISSING_NAME]", "[MISSING_0]", "MISSING_DESCRIPTION",
                "  [MISSING_NAME]  ", "[MISSING_NAME", "missing_name", "", "   ", null};
        for (String text : bad) {
            assertTrue("应判为占位符: " + text, GlossaryTool.isPlaceholderName(text));
        }
    }

    /** 正常名字一个都不许误杀（issue 里特意点名的风险）。 */
    @Test
    public void realNamesAreNeverMistakenForPlaceholders() {
        String[] good = new String[]{"格挡", "Block", "Strength", "MissingNo", "Missing",
                "缺失的名称", "MISSING", "[BLOCK]"};
        for (String text : good) {
            assertFalse("不许误杀正常名字: " + text, GlossaryTool.isPlaceholderName(text));
        }
    }

    // ------------------------------------------------------------------ resolve()

    /** 占位符名字回退成机器 id，并标记 name_resolved:false；占位定义与占位别名都丢掉。 */
    @Test
    public void placeholderNameFallsBackToTheMachineId() {
        GlossaryTool.Resolved resolved = GlossaryTool.resolve("[MISSING_NAME]",
                Arrays.asList("[MISSING_NAME]", "AccuracyPower"), "[MISSING_0]");
        assertNotNull(resolved);
        assertEquals("AccuracyPower", resolved.term);
        assertFalse("回退时必须标记 name_resolved:false", resolved.nameResolved);
        assertEquals("占位定义必须丢弃", "", resolved.definition);
        assertFalse("占位别名不许留在 aliases 里",
                resolved.aliases.contains("[MISSING_NAME]"));
    }

    /** 连机器 id 都没有的词条：整条丢弃，不回脏数据。 */
    @Test
    public void entryWithoutAnyUsableNameIsDropped() {
        assertNull(GlossaryTool.resolve("[MISSING_NAME]",
                Collections.singletonList("[MISSING_NAME]"), "[MISSING_0]"));
        assertNull(GlossaryTool.resolve(null, null, null));
    }

    /** 正常词条原样保留：名字、定义、别名都不动。 */
    @Test
    public void realNameIsKeptVerbatim() {
        GlossaryTool.Resolved resolved = GlossaryTool.resolve("格挡",
                Arrays.asList("格挡", "blocks", "BLOCK"), "持续到下回合开始前，阻挡伤害。");
        assertNotNull(resolved);
        assertEquals("格挡", resolved.term);
        assertTrue(resolved.nameResolved);
        assertEquals("持续到下回合开始前，阻挡伤害。", resolved.definition);
        assertTrue(resolved.aliases.contains("格挡"));
        assertTrue(resolved.aliases.contains("BLOCK"));
    }

    /** 真实词条里夹着一个占位别名：只丢掉占位，别的不动。 */
    @Test
    public void placeholderAliasIsDroppedButTheEntryStays() {
        GlossaryTool.Resolved resolved = GlossaryTool.resolve("易伤",
                Arrays.asList("易伤", "[MISSING_NAME]", "Vulnerable"), "");
        assertNotNull(resolved);
        assertEquals("易伤", resolved.term);
        assertTrue(resolved.nameResolved);
        assertFalse(resolved.aliases.contains("[MISSING_NAME]"));
        assertTrue(resolved.aliases.contains("Vulnerable"));
    }

    // ------------------------------------------------------------------ list 回包字段

    /** 字段名与文档一致：results + count + by_source，不再有 terms。 */
    @Test
    public void listPayloadUsesResultsAndBySource() {
        JsonObject payload = GlossaryTool.listPayload(Collections.<Glossary.Entry>emptyList());
        assertTrue(payload.get("ok").getAsBoolean());
        assertEquals(0, payload.get("count").getAsInt());
        assertTrue("文档口径是 results", payload.has("results"));
        assertFalse("terms 是旧字段名，只许有一个口径", payload.has("terms"));
        assertTrue("文档承诺的 by_source 必须出现", payload.has("by_source"));
        assertTrue(payload.getAsJsonObject("by_source").entrySet().isEmpty());
        assertTrue(payload.get("results").isJsonArray());
    }

    /** 空/ null 入参不炸（工具在任何位置都能安全列一次）。 */
    @Test
    public void listPayloadToleratesNull() {
        JsonObject payload = GlossaryTool.listPayload(null);
        assertEquals(0, payload.get("count").getAsInt());
        assertTrue(payload.has("by_source"));
    }
}
