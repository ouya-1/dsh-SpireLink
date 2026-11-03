package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import spirelink.actions.ActionTypes;
import spirelink.state.StateBuilder;
import spirelink.util.ModLog;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * C6-01 at the wiring level: an {@code applied} answer must actually carry {@code log}.
 *
 * <p>Design 5 shipped the pieces — {@link spirelink.actions.ActionLog#describe} (sentence from two
 * digests, already unit-tested in {@code spirelink.actions.ActionLogTest}) and
 * {@link spirelink.state.CombatDigest#capture} — but nothing in {@code ToolCatalog} ever called
 * them, so the field three documents tell the agent to read did not exist. These tests pin the call
 * site: the field is always there on an applied answer, it carries the measured sentence when the
 * digest pair is available, and it degrades (HUD sentence / fallback text) instead of disappearing
 * or failing the action.
 */
public class ActAppliedLogTest {

    private ToolCatalog catalog;

    @Before
    public void setUp() {
        ModLog log = new ModLog(new File("target/act-applied-log-test"), false);
        catalog = new ToolCatalog(new MainThreadQueue(), new StateBuilder(log, "test"), log);
    }

    @After
    public void tearDown() {
        spirelink.state.StateBuilder.noteAcceptance();
        spirelink.ui.AgentHud.clear();
    }

    /** 和 act() 成功分支同形的回包（settled 之后 attachPlan 之前）。 */
    private static JsonObject appliedPayload(int stateVersion) {
        return new JsonParser().parse("{\"ok\":true,\"outcome\":\"applied\","
                + "\"state_version\":" + stateVersion + ",\"error\":null,"
                + "\"action\":{\"type\":\"play_card\",\"payload\":{\"target_index\":0}}}")
                .getAsJsonObject();
    }

    /** {@link spirelink.state.CombatDigest#capture()} 的形状。 */
    private static JsonObject digest(int energy, int hand, int hp, int block, int round, int[][] monsters) {
        JsonObject json = new JsonObject();
        json.addProperty("energy", energy);
        json.addProperty("hand", hand);
        json.addProperty("hp", hp);
        json.addProperty("block", block);
        json.addProperty("round", round);
        JsonArray array = new JsonArray();
        for (int[] monster : monsters) {
            JsonObject entry = new JsonObject();
            entry.addProperty("index", monster[0]);
            entry.addProperty("id", "Monster" + monster[0]);
            entry.addProperty("name", "史莱姆" + monster[0]);
            entry.addProperty("hp", monster[1]);
            array.add(entry);
        }
        json.add("monsters", array);
        return json;
    }

    /** applied 回包里一定有 log，且句子描述的是实测差量。 */
    @Test
    public void appliedPayloadCarriesTheMeasuredSentence() {
        JsonObject payload = appliedPayload(13);
        JsonObject args = new JsonParser().parse("{\"uuid\":\"u1\",\"target_index\":0}")
                .getAsJsonObject();
        JsonObject before = digest(3, 5, 80, 0, 1, new int[][]{{0, 28}, {1, 30}});
        JsonObject after = digest(2, 4, 80, 0, 1, new int[][]{{0, 22}, {1, 30}});

        catalog.attachAppliedLog(payload, ActionTypes.PLAY_CARD, args, before, after);

        assertTrue("applied 必须带 log: " + payload, payload.has("log"));
        String log = payload.get("log").getAsString();
        assertFalse("log 不许是空串", log.trim().isEmpty());
        assertEquals("出牌 → 第1只(史莱姆0)：能量 3→2，手牌 5→4，第1只(史莱姆0) 28→22", log);
        // 其它契约字段不许被动过。
        assertEquals("applied", payload.get("outcome").getAsString());
        assertTrue(payload.get("error").isJsonNull());
    }

    /** 没有 digest（读不到游戏 / 非战斗）时也要有 log：退回 HUD 那句话。 */
    @Test
    public void logFallsBackToTheHudSentenceWithoutDigests() {
        JsonObject payload = appliedPayload(8);
        JsonObject args = new JsonParser().parse("{\"uuid\":\"u1\"}").getAsJsonObject();

        catalog.attachAppliedLog(payload, ActionTypes.PLAY_CARD, args, null, null);

        assertEquals("出牌", payload.get("log").getAsString());
    }

    /**
     * {@code ActionLog} 只认识九种动作；其它类型（地图/商店/休息…）必须退回一句人话，
     * 而不是这个字段直接消失 —— 文档说的是"applied 带 log"，没有例外。
     */
    @Test
    public void unknownActionTypeStillGetsASentence() {
        JsonObject payload = appliedPayload(9);

        catalog.attachAppliedLog(payload, "choose_map_node", new JsonObject(), null, null);

        String log = payload.get("log").getAsString();
        assertFalse("词表外的动作也得有 log: " + payload, log.trim().isEmpty());
        assertTrue("要能看出是哪一步: " + log, log.contains("走") || log.contains("choose_map_node"));
    }

    /**
     * 两个词表都不认识的动作类型：宁可写动作名，也不要退回 HUD 那句"调整了下动作" ——
     * agent 读了等于没读。
     */
    @Test
    public void actionTypeUnknownToBothVocabulariesIsNamed() {
        JsonObject payload = appliedPayload(6);

        catalog.attachAppliedLog(payload, "choose_character", new JsonObject(), null, null);

        assertEquals("choose_character 已执行", payload.get("log").getAsString());
    }

    /** 空动作类型也不能产生空串。 */
    @Test
    public void blankTypeStillProducesANonEmptySentence() {
        JsonObject payload = appliedPayload(4);

        catalog.attachAppliedLog(payload, null, new JsonObject(), null, null);

        assertFalse(payload.get("log").getAsString().trim().isEmpty());
    }

    /** null 回包不许抛（attachAppliedLog 是"锦上添花"，不能成为失败点）。 */
    @Test
    public void nullPayloadIsIgnored() {
        catalog.attachAppliedLog(null, ActionTypes.END_TURN, new JsonObject(), null, null);
    }

    /** 动作短语取的是 HUD 同一套文案（end_turn 不依赖游戏对象就能取到）。 */
    @Test
    public void actionPhraseComesFromTheHudVocabulary() {
        assertEquals("结束这一回合", ToolCatalog.actionPhrase(ActionTypes.END_TURN, new JsonObject()));
        assertEquals("结束这一回合", ToolCatalog.actionPhrase(ActionTypes.END_TURN, null));
        assertNotNull(ToolCatalog.actionPhrase("not_a_real_type", new JsonObject()));
        assertFalse(ToolCatalog.actionPhrase("not_a_real_type", new JsonObject()).trim().isEmpty());
        assertNotNull(ToolCatalog.actionPhrase(null, null));
    }

    /** act 的 schema 描述必须提到 log，否则文档说的字段在工具面上又找不到。 */
    @Test
    public void actSchemaAdvertisesTheLogField() {
        JsonArray tools = catalog.listTools();
        String description = null;
        for (int i = 0; i < tools.size(); i++) {
            JsonObject tool = tools.get(i).getAsJsonObject();
            if (ToolCatalog.TOOL_ACT.equals(tool.get("name").getAsString())) {
                description = tool.get("description").getAsString();
            }
        }
        assertNotNull("act 必须在 tools/list 里", description);
        assertTrue("act 描述要说明 applied 带 log: " + description, description.contains("log"));
    }

    /** 设计 5 的那套构造器真的还在（否则这条修复会悄悄退化成一个手写字符串）。 */
    @Test
    public void designFiveBuilderIsStillTheSource() {
        assertNull("没有词条的动作类型返回 null，由调用方兜底",
                spirelink.actions.ActionLog.describe("open_mode_select", new JsonObject(), null, null));
        assertEquals("结束回合",
                spirelink.actions.ActionLog.describe(ActionTypes.END_TURN, new JsonObject(), null, null));
    }
}
