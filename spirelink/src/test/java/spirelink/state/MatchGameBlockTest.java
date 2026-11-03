package spirelink.state;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #119 · C10-06: GremlinMatchGame 的 INTRO / RULE_EXPLANATION 页也在给
 * {@code attempts} / {@code flip_stalled_ms}。
 *
 * <p>这两个字段描述的是<b>牌面</b>：只有 {@code screen=PLAY}（牌桌能翻）时才有意义。开幕那几页
 * 什么都还没翻，{@code flip_stalled_ms} 从小游戏挂上那一刻开始一直涨 —— 读起来就是「牌桌卡住了」，
 * 而 {@code attempts} 只是 0。所以非 PLAY 屏一律不发这两个键，{@code screen} / {@code cards} /
 * {@code matched} 照旧（agent 靠 {@code screen} 知道现在还不能翻）。</p>
 */
public class MatchGameBlockTest {

    private static JsonObject json(String raw) {
        return new JsonParser().parse(raw).getAsJsonObject();
    }

    private static JsonObject fullBoard(String screen) {
        return json("{\"screen\":\"" + screen + "\",\"game_done\":false,"
                + "\"flip_stalled_ms\":12345,\"matched\":2,\"attempts\":0,"
                + "\"first_pick\":\"\",\"flipping\":false,"
                + "\"cards\":[{\"index\":0,\"id\":\"Strike_R\",\"name\":\"打击\","
                + "\"matched\":false,\"is_first_pick\":false}]}");
    }

    @Test
    public void playScreenKeepsTheBoardFields() {
        JsonObject play = StateBuilder.matchGameForAgent(fullBoard("PLAY"));
        assertTrue(play.has("flip_stalled_ms"));
        assertTrue(play.has("attempts"));
        assertEquals(12345L, play.get("flip_stalled_ms").getAsLong());
        assertEquals(0, play.get("attempts").getAsInt());
    }

    @Test
    public void introPagesDropTheBoardFields() {
        for (String screen : new String[] {"INTRO", "RULE_EXPLANATION", "", "UNKNOWN"}) {
            JsonObject block = StateBuilder.matchGameForAgent(fullBoard(screen));
            assertFalse(screen + " 不该给 flip_stalled_ms", block.has("flip_stalled_ms"));
            assertFalse(screen + " 不该给 attempts", block.has("attempts"));
            // 牌面与屏名照旧：agent 靠 screen 知道「还不能翻」。
            assertEquals(screen, block.get("screen").getAsString());
            assertTrue(screen + " 要保留 matched", block.has("matched"));
            assertTrue(screen + " 要保留 cards", block.has("cards"));
        }
    }

    @Test
    public void theRawBlockIsNotMutated() {
        JsonObject raw = fullBoard("INTRO");
        JsonObject trimmed = StateBuilder.matchGameForAgent(raw);
        assertTrue("裁剪要产出新对象，别改调用方那份", trimmed != raw);
        assertTrue("events 层的原值保持不变", raw.has("flip_stalled_ms"));
        assertTrue(raw.has("attempts"));
    }

    @Test
    public void noMinigameIsNull() {
        assertNull(StateBuilder.matchGameForAgent(null));
    }
}
