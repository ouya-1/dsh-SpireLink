package spirelink.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** {@code act.log} sentence (design 5: applied carries a short human sentence). */
public class ActionLogTest {

    private static JsonObject digest(int energy, int hand, int hp, int block, int round,
                                     int[][] monsters) {
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
            entry.addProperty("hp", monster[1]);
            entry.addProperty("id", "Monster" + monster[0]);
            entry.addProperty("name", "史莱姆" + monster[0]);
            array.add(entry);
        }
        json.add("monsters", array);
        return json;
    }

    private static JsonObject payload(Integer targetIndex) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", "u1");
        if (targetIndex != null) {
            json.addProperty("target_index", targetIndex);
        }
        return json;
    }

    @Test
    public void attackLogShowsTargetAndDeltas() {
        JsonObject pre = digest(3, 5, 80, 0, 1, new int[][]{{0, 28}, {1, 30}});
        JsonObject post = digest(2, 4, 80, 0, 1, new int[][]{{0, 22}, {1, 30}});
        String sentence = ActionLog.describe(ActionTypes.PLAY_CARD, payload(0), pre, post);
        assertTrue(sentence, sentence.startsWith("出牌 → 第1只(史莱姆0)"));
        assertTrue(sentence, sentence.contains("能量 3→2"));
        assertTrue(sentence, sentence.contains("手牌 5→4"));
        assertTrue(sentence, sentence.contains("第1只(史莱姆0) 28→22"));
    }

    @Test
    public void killIsReported() {
        JsonObject pre = digest(3, 5, 80, 0, 1, new int[][]{{0, 5}});
        JsonObject post = digest(2, 4, 80, 0, 1, new int[][]{{0, 0}});
        String sentence = ActionLog.describe(ActionTypes.PLAY_CARD, payload(0), pre, post);
        assertTrue(sentence, sentence.contains("击杀"));
    }

    @Test
    public void potionAndBlockAreReported() {
        JsonObject pre = digest(3, 5, 70, 0, 1, new int[0][]);
        JsonObject post = digest(3, 5, 82, 0, 1, new int[0][]);
        String sentence = ActionLog.describe(ActionTypes.USE_POTION, payload(null), pre, post);
        assertTrue(sentence, sentence.startsWith("使用药水"));
        assertTrue(sentence, sentence.contains("HP 70→82"));
    }

    @Test
    public void endTurnAndSelectionHaveVerbs() {
        JsonObject pre = digest(3, 5, 80, 0, 1, new int[0][]);
        assertEquals("结束回合", ActionLog.describe(ActionTypes.END_TURN, payload(null), pre, pre));
        assertEquals("选牌", ActionLog.describe(ActionTypes.CHOOSE_HAND_CARDS, payload(null), pre, pre));
        assertEquals("确认选牌",
                ActionLog.describe(ActionTypes.CONFIRM_HAND_SELECT, payload(null), pre, pre));
    }

    @Test
    public void unknownActionOrMissingDigestFallsBack() {
        assertNull(ActionLog.describe("open_mode_select", payload(null), null, null));
        JsonObject pre = digest(3, 5, 80, 0, 1, new int[0][]);
        assertEquals("出牌", ActionLog.describe(ActionTypes.PLAY_CARD, payload(null), pre, null));
    }
}
