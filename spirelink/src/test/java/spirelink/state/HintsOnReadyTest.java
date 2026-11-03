package spirelink.state;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #100（item 4）+ C6-04：「异常但 ready」的那一拍必须自带 hints。
 *
 * <p>两条现场：
 * <ul>
 *   <li>{@code ui=map}、{@code map.reachable=[]}、{@code ready=true} —— 这一拍没有任何能推进的动作
 *       （唯一出口 {@code proceed} 还是坏的），但 {@code hints} 整块不出现，{@code
 *       hints.map_no_reachable} 只有显式 {@code include:["hints"]} 才看得到；</li>
 *   <li>{@code ui=victory} 同样 {@code ready=true}，于是 {@code hints.suggest_note} 被过滤掉 ——
 *       而文档写的是「同一拍 {@code hints.suggest_note}」。</li>
 * </ul>
 *
 * <p>规则：{@code ready} 不等于「没事要说」。{@link StateBuilder#wantHints} 以前只看
 * ready/rejected/char_select，现在再加一条内容判据 —— hints 里带着 {@code map_no_reachable} /
 * {@code suggest_note} / {@code unknown_ui} / {@code event_missing} 这类「说明这一拍不正常」的标记时，
 * 整块留下。反过来说：没有这些标记的 ready 帧仍然不挂 hints（省字符、「看到 hints 就说明有事」这条
 * 语义不变）。
 *
 * <p>顺带钉住同一拍的自洽性：{@code map.reachable} 与 {@code hints.map_no_reachable} 现在来自同一次
 * 地图读取（{@link StateBuilder.MapProbe}），末行「Boss 可进」时不再一边说没路、一边把 Boss 列进
 * reachable。
 */
public class HintsOnReadyTest {

    private static StateBuilder builder() {
        return new StateBuilder(null, "test");
    }

    @Before
    public void quietState() {
        // hints 还会在「上一次 act 被拒」时出现；本 JVM 里别的测试可能留下这个标记。
        StateBuilder.noteAcceptance();
    }

    private static JsonObject bodyWithHints(JsonObject hints) {
        JsonObject body = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("ready", true);
        meta.addProperty("state_version", 315);
        body.add("meta", meta);
        body.add("player", new JsonObject());
        body.add("hints", hints);
        return body;
    }

    private static JsonObject hints(String... keyValues) {
        JsonObject hints = new JsonObject();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            hints.addProperty(keyValues[i], keyValues[i + 1]);
        }
        return hints;
    }

    private static Set<String> names(String... values) {
        Set<String> out = new LinkedHashSet<String>();
        for (String value : values) {
            out.add(value);
        }
        return out;
    }

    // ------------------------------------------------------------------ #100 item 4：卡死的地图

    /** ready 的地图 + map_no_reachable：hints 必须自己出现（不必 include）。 */
    @Test
    public void readyMapWithNoReachableKeepsTheHintsBlock() {
        JsonObject hints = hints("map_no_reachable", "true", "frames", "47594");
        JsonObject body = bodyWithHints(hints);

        builder().filterBlocks(body, "map", true, null, null);

        assertTrue("#100: 卡死的地图这一拍自带提示", body.has("hints"));
        assertTrue(body.getAsJsonObject("hints").get("map_no_reachable").getAsBoolean());
        assertTrue("不是空对象占位", body.getAsJsonObject("hints").has("frames"));
    }

    /** 同一块 hints 在死亡屏（也是 ready）同样不该被删：死亡和胜利是一套逻辑。 */
    @Test
    public void readyDeathKeepsSuggestNote() {
        JsonObject body = bodyWithHints(hints("suggest_note", "记一条：第 3 层被书虫磨死"));
        builder().filterBlocks(body, "death", true, null, null);
        assertTrue(body.has("hints"));
        assertEquals("记一条：第 3 层被书虫磨死",
                body.getAsJsonObject("hints").get("suggest_note").getAsString());
    }

    // ------------------------------------------------------------------ C6-04：victory 同拍

    /** victory 是 ready 帧：没有这条判据，suggest_note 永远到不了 agent 手里。 */
    @Test
    public void readyVictoryKeepsSuggestNote() {
        JsonObject body = bodyWithHints(hints("suggest_note", "记一条：心脏战前留一张 Impervious"));
        builder().filterBlocks(body, "victory", true, null, null);

        assertTrue("C6-04: 通关那一拍就要有 hints.suggest_note", body.has("hints"));
        assertEquals("记一条：心脏战前留一张 Impervious",
                body.getAsJsonObject("hints").get("suggest_note").getAsString());
    }

    /** 没有 suggest_note 的 victory（同一次通关的后续读取）：hints 照旧不出现，不白花字符。 */
    @Test
    public void readyVictoryWithoutSuggestNoteStillDropsHints() {
        JsonObject body = bodyWithHints(hints("frames", "47594", "act_seq", "48"));
        builder().filterBlocks(body, "victory", true, null, null);
        assertFalse("没事要说的 ready 帧不许挂 hints", body.has("hints"));
    }

    /** 普通 ready 地图（有路可走）：hints 照旧整块删掉，旧语义不许被这条新判据放大。 */
    @Test
    public void readyMapWithRoutesStillDropsHints() {
        JsonObject body = bodyWithHints(hints("frames", "100", "grid_selected", "1"));
        builder().filterBlocks(body, "map", true, null, null);
        assertFalse(body.has("hints"));
    }

    // ------------------------------------------------------------------ 其它「异常但 ready」

    @Test
    public void unknownUiStillAsksForAttention() {
        JsonObject body = bodyWithHints(hints("unknown_ui", "true"));
        builder().filterBlocks(body, "unknown:SOME_SCREEN", true, null, null);
        assertTrue("I2: 模组不认识的界面必须让 agent 看见", body.has("hints"));
    }

    @Test
    public void emptyEventRoomStillAsksForAttention() {
        JsonObject body = bodyWithHints(hints("event_missing", "true"));
        builder().filterBlocks(body, "event", true, null, null);
        assertTrue("事件房但事件为空：这不是读取失败，要说出来", body.has("hints"));
    }

    // ------------------------------------------------------------------ 既有契约不许回退

    /** exclude 仍然赢：异常标记不能让 exclude:["hints"] 失效。 */
    @Test
    public void excludeStillRemovesTheAnomalyHints() {
        JsonObject body = bodyWithHints(hints("map_no_reachable", "true"));
        builder().filterBlocks(body, "map", true, null, names("hints"));
        assertFalse("exclude 里的 hints 必须还能删掉它", body.has("hints"));
        assertTrue("meta 不许被动", body.has("meta"));
    }

    /** include:["hints"] 显式点名：本来就要回（这条不是新行为，别在新判据里弄丢）。 */
    @Test
    public void includeStillAnswersHintsExplicitly() {
        JsonObject body = bodyWithHints(hints("map_no_reachable", "true"));
        builder().filterBlocks(body, "map", true, names("hints"), null);
        assertTrue(body.has("hints"));
    }

    /** 纯判据：只有文档里那四个标记算「有事要说」，别的键（含 null 值）都不算。 */
    @Test
    public void explainsSomethingOnlyForTheDocumentedMarkers() {
        assertFalse("没有 hints 块", StateBuilder.explainsSomething(null));
        assertFalse("空 hints", StateBuilder.explainsSomething(new JsonObject()));
        assertFalse("普通探针", StateBuilder.explainsSomething(hints("frames", "1", "beats", "1")));

        JsonObject nullMarked = new JsonObject();
        nullMarked.add("map_no_reachable", JsonNull.INSTANCE);
        assertFalse("显式 null 不算「有事要说」", StateBuilder.explainsSomething(nullMarked));

        assertTrue(StateBuilder.explainsSomething(hints("map_no_reachable", "true")));
        assertTrue(StateBuilder.explainsSomething(hints("suggest_note", "记一条")));
        assertTrue(StateBuilder.explainsSomething(hints("unknown_ui", "true")));
        assertTrue(StateBuilder.explainsSomething(hints("event_missing", "true")));
    }

    // ------------------------------------------------------------------ 同一拍自洽（#100 顺带）

    /**
     * 末行「没有节点但 Boss 可进」不是卡死：那一拍的 {@code map.reachable} 里就有 Boss 坐标，
     * 所以 {@code map_no_reachable} 不许同时为真。
     */
    @Test
    public void bossCountsAsAWayForward() {
        StateBuilder.MapProbe stuck = new StateBuilder.MapProbe(
                Collections.<com.megacrit.cardcrawl.map.MapRoomNode>emptyList(), false, true);
        assertTrue("没节点且 Boss 不可进 = 卡死", stuck.noWayForward());

        StateBuilder.MapProbe bossOnly = new StateBuilder.MapProbe(
                Collections.<com.megacrit.cardcrawl.map.MapRoomNode>emptyList(), true, true);
        assertFalse("Boss 可进就是有出路", bossOnly.noWayForward());

        StateBuilder.MapProbe nodes = new StateBuilder.MapProbe(
                Collections.<com.megacrit.cardcrawl.map.MapRoomNode>singletonList(null), false, false);
        assertFalse("有节点就是有路", nodes.noWayForward());

        StateBuilder.MapProbe unknown = new StateBuilder.MapProbe(
                null, false, false);
        assertTrue("读取失败按空列表处理，仍报卡死", unknown.noWayForward());
    }
}
