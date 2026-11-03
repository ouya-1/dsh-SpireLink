package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import spirelink.util.RuntimeProbe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Issues #84 / #85: the blocks that are merged in <em>after</em> the state filter.
 *
 * <p>#84a: {@code get_state{exclude:["hints"]}} still came back with hints, because the cached body
 * merged on the not-ready/error paths (and the liveness decorator) could put the block back after the
 * filter had removed it.
 *
 * <p>#84b: {@code legal_actions}'s {@code hints.frames} came from the cached stable snapshot - the
 * test measured 15171 there while the same-tick {@code get_state} said 47594. Those numbers are how
 * an agent tells "the game is moving" from "the game is dead" (issue #69 reads them), so they have to
 * be read at serve time.
 *
 * <p>#85: {@code get_state{include:["briefing"]}} answered nothing, because the briefing lives under
 * {@code memory} and only a present memory block was refreshed.
 */
public class GetStateHintsAndIncludeTest {

    private static JsonObject json(String raw) {
        return new JsonParser().parse(raw).getAsJsonObject();
    }

    private static JsonArray array(String raw) {
        return new JsonParser().parse(raw).getAsJsonArray();
    }

    // ------------------------------------------------------------------ #84a exclude 不许复活

    /** exclude 之后，被缓存合并回来的 hints 必须再删掉。 */
    @Test
    public void excludeRemovesHintsThatCameBackFromTheCache() {
        JsonObject payload = json("{\"ok\":false,\"ready\":false,\"meta\":{\"ui\":\"map\"},"
                + "\"hints\":{\"frames\":12}}");
        ToolCatalog.dropExcluded(payload, array("[\"hints\"]"));

        assertFalse("exclude:[\"hints\"] 之后不许还有 hints", payload.has("hints"));
        assertTrue("meta 永远不许被 exclude 删掉", payload.has("meta"));
    }

    /** exclude + 装饰器的组合：先删，再装饰，仍然不许复活（旧行为的核心 bug）。 */
    @Test
    public void excludeThenDecorateNeverResurrectsHints() {
        JsonObject payload = json("{\"ok\":true,\"ready\":true,\"hints\":{\"frames\":3}}");
        ToolCatalog.dropExcluded(payload, array("[\"hints\"]"));
        ToolCatalog.mergeLiveness(payload);
        assertFalse("装饰器不许把 exclude 掉的 hints 造回来", payload.has("hints"));
    }

    /** exclude 别的区块照常生效；空/null exclude 什么都不做。 */
    @Test
    public void excludeHandlesOtherBlocksAndNulls() {
        JsonObject payload = json("{\"ok\":true,\"meta\":{},\"map\":{\"x\":1},"
                + "\"memory\":{\"run_history\":[]}}");
        ToolCatalog.dropExcluded(payload, array("[\"map\",\"memory\",\"meta\"]"));
        assertFalse(payload.has("map"));
        assertFalse(payload.has("memory"));
        assertTrue("meta 必须留下", payload.has("meta"));

        JsonObject untouched = json("{\"ok\":true,\"map\":{}}");
        ToolCatalog.dropExcluded(untouched, null);
        ToolCatalog.dropExcluded(untouched, new JsonArray());
        assertTrue(untouched.has("map"));
    }

    // ------------------------------------------------------------------ #84b legal_actions 现读现算

    /** legal_actions 形状的回包：hints 里的 frames/beats 必须是「此刻」的值，不是缓存里的旧数字。 */
    @Test
    public void legalActionsHintsAreRefreshedToTheLiveNumbers() {
        // 旧值取 QA 实测的 15171：同拍 get_state 是 47594，两个数字不一致本身就是 bug。
        JsonObject payload = json("{\"ready\":false,\"state_version\":42,"
                + "\"hints\":{\"frames\":15171,\"beats\":15171,"
                + "\"ms_since_frame\":999999,\"ms_since_beat\":999999},"
                + "\"actions\":[]}");
        long liveFrames = RuntimeProbe.frames();
        long liveBeats = RuntimeProbe.beats();

        ToolCatalog.mergeLiveness(payload);

        JsonObject hints = payload.getAsJsonObject("hints");
        assertEquals("frames 必须现读现算", liveFrames, hints.get("frames").getAsLong());
        assertEquals("beats 必须现读现算", liveBeats, hints.get("beats").getAsLong());
        assertNotEquals("旧缓存值不许留着", 15171L, hints.get("frames").getAsLong());
        // 现读现算：两次取值之间可以差 1ms（实测就这么偶发失败过一次），所以按「接近」判，
        // 而不是按「相等」判 —— 这条测的是「有没有刷新」，不是毫秒级精度。
        assertTrue("ms_since_frame 要现读现算: " + hints.get("ms_since_frame").getAsLong(),
                Math.abs(RuntimeProbe.msSinceFrame() - hints.get("ms_since_frame").getAsLong()) <= 200L);
        assertTrue("ms_since_beat 要现读现算: " + hints.get("ms_since_beat").getAsLong(),
                Math.abs(RuntimeProbe.msSinceBeat() - hints.get("ms_since_beat").getAsLong()) <= 200L);
    }

    /** 没有 hints 的回包不被刷新出 hints（与 #66/#68 同一张网）。 */
    @Test
    public void legalActionsWithoutHintsStaysWithoutHints() {
        JsonObject payload = json("{\"ready\":true,\"state_version\":7,\"actions\":[]}");
        ToolCatalog.mergeLiveness(payload);
        assertFalse(payload.has("hints"));
    }

    // ------------------------------------------------------------------ #85 briefing 单独可要

    /** include:["briefing"] 单独用：必须决定去补一个顶层 briefing。 */
    @Test
    public void standaloneBriefingIsAttached() {
        JsonObject payload = json("{\"ok\":true,\"meta\":{}}");
        assertTrue(ToolCatalog.wantsStandaloneBriefing(payload, json("{\"include\":[\"briefing\"]}")));
        // 逗号分隔的旧写法同样认
        assertTrue(ToolCatalog.wantsStandaloneBriefing(payload, json("{\"include\":\"briefing\"}")));
    }

    /** 和 memory 一起要时，briefing 已经在 memory.briefing 里，不许再发一份顶层副本。 */
    @Test
    public void briefingIsNotDuplicatedWhenMemoryIsThere() {
        JsonObject payload = json("{\"ok\":true,\"memory\":{\"briefing\":{\"notes_total\":3}}}");
        assertFalse(ToolCatalog.wantsStandaloneBriefing(payload,
                json("{\"include\":[\"memory\",\"briefing\"]}")));
    }

    /** 已经带了顶层 briefing（或根本没要）时也不补。 */
    @Test
    public void briefingIsNotAttachedTwiceOrUnasked() {
        assertFalse(ToolCatalog.wantsStandaloneBriefing(
                json("{\"briefing\":{\"notes_total\":1}}"),
                json("{\"include\":[\"briefing\"]}")));
        assertFalse(ToolCatalog.wantsStandaloneBriefing(
                json("{\"meta\":{}}"), json("{\"include\":[\"map\"]}")));
        assertFalse(ToolCatalog.wantsStandaloneBriefing(json("{\"meta\":{}}"), null));
        assertFalse(ToolCatalog.wantsStandaloneBriefing(json("{\"meta\":{}}"),
                json("{\"exclude\":[\"briefing\"]}")));
        assertFalse(ToolCatalog.wantsStandaloneBriefing(null,
                json("{\"include\":[\"briefing\"]}")));
    }

    /** include 里的 briefing 说明必须写进 schema（agent 只能从 tools/list 学到这个用法）。 */
    @Test
    public void schemaExplainsStandaloneBriefing() {
        JsonObject getState = null;
        JsonArray tools = new ToolCatalog(null, null, null).listTools();
        for (int i = 0; i < tools.size(); i++) {
            JsonObject tool = tools.get(i).getAsJsonObject();
            if (ToolCatalog.TOOL_GET_STATE.equals(tool.get("name").getAsString())) {
                getState = tool;
            }
        }
        assertTrue(getState != null);
        String description = getState.getAsJsonObject("inputSchema")
                .getAsJsonObject("properties").getAsJsonObject("include")
                .get("description").getAsString();
        assertTrue("include.description 要说清 briefing 能单独要: " + description,
                description.contains("briefing 可以单独要"));
    }
}
