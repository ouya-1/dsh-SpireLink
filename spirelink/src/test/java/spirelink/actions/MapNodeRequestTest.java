package spirelink.actions;

import com.google.gson.JsonObject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * #110: the {@code choose_map_node} payload is validated <b>before</b> any room-phase decision.
 *
 * <p>The report: {@code {"x": "<字符串>", "y": 3}} answered
 * {@code NOT_READY "房间还没结束（phase=…），先 proceed 离开"}. The reader was
 * {@code ActionExecutor.num()}, whose fallback is {@code -1} — the Boss sentinel — so a typo was
 * read as "walk to the Boss". The parser is pure, so the whole type/shape matrix is pinned here.
 */
public class MapNodeRequestTest {

    private static JsonObject payload(String x, String y) {
        JsonObject object = new JsonObject();
        if (x != null) {
            object.add("x", new com.google.gson.JsonParser().parse(x));
        }
        if (y != null) {
            object.add("y", new com.google.gson.JsonParser().parse(y));
        }
        return object;
    }

    private static BadRequestException badRequest(JsonObject payload) {
        try {
            MapNodeRequest request = MapNodeRequest.parse(payload);
            fail("应该 BAD_REQUEST，却读成了 (" + request.x + "," + request.y + ")");
            return null;
        } catch (BadRequestException e) {
            assertEquals(ErrorCodes.BAD_REQUEST, e.code);
            assertNotNull("错误要有说明", e.getMessage());
            return e;
        }
    }

    @Test
    public void integersAreReadAsTheNodeTheyName() {
        MapNodeRequest node = MapNodeRequest.parse(payload("3", "2"));
        assertEquals(3, node.x);
        assertEquals(2, node.y);
        assertFalse("普通节点不是 Boss 入口", node.isBossEntry());
        assertFalse(node.hasInvalidColumn());
        assertFalse(node.hasInvalidRow());
    }

    /** The reported payload: a string x must be a payload error, never the Boss sentinel. */
    @Test
    public void aStringColumnIsABadRequestAndNeverTheBossEntry() {
        BadRequestException e = badRequest(payload("\"left\"", "3"));
        assertTrue("要说清必须是整数: " + e.getMessage(), e.getMessage().contains("必须是整数"));
        assertTrue("要指出是 x: " + e.getMessage(), e.getMessage().contains("payload.x"));
        badRequest(payload("\"3\"", "2"));
    }

    @Test
    public void aFractionalCoordinateIsRefused() {
        assertTrue(badRequest(payload("1.5", "2")).getMessage().contains("截断"));
        assertTrue(badRequest(payload("1", "2.5")).getMessage().contains("截断"));
    }

    @Test
    public void missingFieldsNameThePayloadThisActionWants() {
        BadRequestException noY = badRequest(payload("3", null));
        assertTrue(noY.getMessage(), noY.getMessage().contains("x/y"));
        assertTrue("要能照着改: " + noY.getMessage(), noY.getMessage().contains("choose_map_node"));
        badRequest(payload(null, "2"));
        badRequest(new JsonObject());
        badRequest(null);
        badRequest(payload("3", "null"));
    }

    @Test
    public void theBossEntryIsExactlyMinusOnePlusTheRowCount() {
        MapNodeRequest boss = MapNodeRequest.parse(payload("-1", "15"));
        assertTrue(boss.isBossEntry());
        assertFalse(boss.hasInvalidColumn());
        assertTrue("Boss 行就是地图行数: " + boss.y, boss.isBossRow(15));
        assertFalse("别的行不是 Boss 行", boss.isBossRow(16));
        assertEquals("choose_map_node{x:-1,y:15}", MapNodeRequest.bossEntryText(15));
    }

    /** Any negative column other than -1 used to be read as the Boss icon too. */
    @Test
    public void otherNegativeColumnsAreNotTheBoss() {
        MapNodeRequest node = MapNodeRequest.parse(payload("-5", "2"));
        assertTrue(node.hasInvalidColumn());
        assertFalse(node.isBossEntry());
        String message = MapNodeRequest.columnMessage(-5, 15);
        assertTrue(message, message.contains("-5"));
        assertTrue("要给出正确的 Boss 入口: " + message, message.contains("-1"));
        assertTrue("要带行数: " + message, message.contains("15"));
    }

    @Test
    public void negativeRowsArePayloadErrors() {
        assertTrue(MapNodeRequest.parse(payload("3", "-1")).hasInvalidRow());
        String message = MapNodeRequest.rowMessage(-1, 15);
        assertTrue(message, message.contains("-1"));
        assertTrue("要说明行从 0 起: " + message, message.contains("0 起"));
    }

    @Test
    public void aWrongBossRowSaysWhichRowTheBossHas() {
        String message = MapNodeRequest.bossRowMessage(3, 15);
        assertTrue(message, message.contains("3"));
        assertTrue("要给出正确的 y: " + message, message.contains("15"));
    }
}