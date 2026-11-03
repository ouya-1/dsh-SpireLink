package spirelink.actions;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #101: {@code take_reward} with a missing or out-of-range {@code index} (the report used 42)
 * was silently "repaired" by claiming the <b>first</b> reward — in that run a relic the caller never
 * asked for. The payload is now strict and the rejection carries the legal range plus the items, so a
 * wrong index is a retryable BAD_REQUEST instead of a surprise pickup.
 */
public class TakeRewardRequestTest {

    private static JsonObject payload(String key, String json) {
        JsonObject object = new JsonObject();
        object.add(key, new com.google.gson.JsonParser().parse(json));
        return object;
    }

    private static void assertBadRequest(JsonObject payload) {
        try {
            TakeRewardRequest.parse(payload);
            fail("应该 BAD_REQUEST: " + payload);
        } catch (BadRequestException e) {
            assertEquals(ErrorCodes.BAD_REQUEST, e.code);
            assertNotNull("错误要有说明", e.getMessage());
        }
    }

    @Test
    public void indexIsMandatory() {
        assertBadRequest(null);
        assertBadRequest(new JsonObject());
        // explicit null is not an index either
        assertBadRequest(payload("index", "null"));
    }

    @Test
    public void indexMustBeAnInteger() {
        assertBadRequest(payload("index", "\"0\""));
        assertBadRequest(payload("index", "true"));
        assertBadRequest(payload("index", "1.5"));
        assertBadRequest(payload("index", "-1"));
    }

    @Test
    public void integerIndexIsAccepted() {
        TakeRewardRequest request = TakeRewardRequest.parse(payload("index", "0"));
        assertEquals(0, request.index);
        assertEquals("index", request.key);
        assertEquals(2, TakeRewardRequest.parse(payload("index", "2")).index);
        // 1.0 is integral, so it is a level-like number, not a type error
        assertEquals(1, TakeRewardRequest.parse(payload("index", "1.0")).index);
    }

    /** The legacy {@code slot} name keeps working; index wins when both are present. */
    @Test
    public void slotAliasStillWorks() {
        TakeRewardRequest request = TakeRewardRequest.parse(payload("slot", "1"));
        assertEquals(1, request.index);
        assertEquals("slot", request.key);

        JsonObject both = new JsonObject();
        both.addProperty("index", 3);
        both.addProperty("slot", 1);
        assertEquals(3, TakeRewardRequest.parse(both).index);
    }

    @Test
    public void rangeChecksAreExact() {
        assertTrue(TakeRewardRequest.inRange(0, 3));
        assertTrue(TakeRewardRequest.inRange(2, 3));
        assertFalse("42 就是 QA 报的那个越界值", TakeRewardRequest.inRange(42, 3));
        assertFalse(TakeRewardRequest.inRange(3, 3));
        assertFalse(TakeRewardRequest.inRange(-1, 3));
        assertFalse(TakeRewardRequest.inRange(0, 0));
    }

    @Test
    public void rangeTextNamesTheLegalRange() {
        assertEquals("合法范围 0..2（共 3 条）", TakeRewardRequest.rangeText(3));
        assertEquals("合法范围 0..0（共 1 条）", TakeRewardRequest.rangeText(1));
        assertTrue(TakeRewardRequest.rangeText(0).contains("没有奖励"));
    }

    @Test
    public void listTextNamesTheItems() {
        String text = TakeRewardRequest.listText(Arrays.asList("0=金币(25 金)", "1=遗物(小盒子)"));
        assertEquals("0=金币(25 金), 1=遗物(小盒子)", text);
        assertTrue(TakeRewardRequest.listText(java.util.Collections.<String>emptyList())
                .contains("没有可领取"));
        assertEquals(3, TakeRewardRequest.sizeOf(Arrays.asList("a", "b", "c")));
        assertEquals(0, TakeRewardRequest.sizeOf(null));
    }
}
