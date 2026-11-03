package spirelink.actions;

import com.google.gson.JsonObject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * #110 / #111 / C8-01 / C8-02: the one strict integer reader every payload field now goes through.
 *
 * <p>Both reports are the same coercion: {@code JsonPrimitive.getAsInt()} reads the string
 * {@code "3"} as 3 and <b>truncates</b> {@code 1.5} to 1. {@code take_card{"index":1.5}} therefore
 * took card 1 (#111), and a malformed {@code choose_map_node} x/y fell back to {@code -1} — the Boss
 * sentinel — so the map branch answered a room-phase NOT_READY instead of a payload error (#110).</p>
 *
 * <p>Pure class, so the whole type matrix is pinned here without a game screen.</p>
 */
public class IntArgumentTest {

    private static JsonObject payload(String key, String json) {
        JsonObject object = new JsonObject();
        object.add(key, new com.google.gson.JsonParser().parse(json));
        return object;
    }

    private static BadRequestException badRequest(JsonObject payload, String key) {
        try {
            Integer value = IntArgument.optional(payload, key);
            fail("应该 BAD_REQUEST，却读成了 " + value + ": " + payload);
            return null;
        } catch (BadRequestException e) {
            assertEquals(ErrorCodes.BAD_REQUEST, e.code);
            assertNotNull("错误要有说明", e.getMessage());
            return e;
        }
    }

    @Test
    public void integersAreAccepted() {
        assertEquals(Integer.valueOf(0), IntArgument.optional(payload("index", "0"), "index"));
        assertEquals(Integer.valueOf(3), IntArgument.optional(payload("index", "3"), "index"));
        assertEquals("负数是合法整数，只有 index 类字段才额外禁止",
                Integer.valueOf(-1), IntArgument.optional(payload("x", "-1"), "x"));
        assertEquals(Integer.valueOf(2147483647),
                IntArgument.optional(payload("index", "2147483647"), "index"));
    }

    /** 1.0 loses nothing, so it is not a type error — the rule take_reward has had since #101. */
    @Test
    public void writtenOutIntegralNumbersAreAccepted() {
        assertEquals(Integer.valueOf(1), IntArgument.optional(payload("index", "1.0"), "index"));
        assertEquals(Integer.valueOf(2), IntArgument.optional(payload("index", "2.00"), "index"));
        assertEquals(Integer.valueOf(0), IntArgument.optional(payload("index", "0.0"), "index"));
    }

    /** The #111 bug in one line: 1.5 used to become 1 and take a card nobody named. */
    @Test
    public void fractionalNumbersAreRefusedInsteadOfTruncated() {
        for (String bad : new String[] {"1.5", "0.5", "-0.5", "2.25", "1.0000001"}) {
            BadRequestException e = badRequest(payload("index", bad), "index");
            assertTrue("要说清会被截断: " + e.getMessage(), e.getMessage().contains("截断"));
        }
    }

    /** The other half of the same coercion: the string "3" was read as 3. */
    @Test
    public void stringsAreNeverIntegers() {
        for (String bad : new String[] {"\"3\"", "\"0\"", "\"-1\"", "\"\"", "\"1.5\""}) {
            BadRequestException e = badRequest(payload("index", bad), "index");
            assertTrue("要说清不是 JSON 数字: " + e.getMessage(),
                    e.getMessage().contains("不是 JSON 数字"));
            assertTrue("报错要带上收到的值: " + e.getMessage(), e.getMessage().contains("payload.index"));
        }
    }

    @Test
    public void booleansObjectsAndArraysAreRefused() {
        assertTrue(badRequest(payload("index", "true"), "index").getMessage().contains("整数"));
        assertTrue(badRequest(payload("index", "{}"), "index").getMessage().contains("整数"));
        assertTrue(badRequest(payload("index", "[]"), "index").getMessage().contains("整数"));
    }

    @Test
    public void valuesOutsideTheIntRangeAreRefusedInsteadOfWrapping() {
        BadRequestException e = badRequest(payload("index", "3000000000"), "index");
        assertTrue("要说清超范围: " + e.getMessage(), e.getMessage().contains("超出范围"));
    }

    /** "not sent" must stay distinguishable from "sent wrong" (take_card also accepts a uuid). */
    @Test
    public void absentAndExplicitNullAreNotTypeErrors() {
        assertNull(IntArgument.optional(new JsonObject(), "index"));
        assertNull(IntArgument.optional(payload("index", "null"), "index"));
        assertNull(IntArgument.optional(null, "index"));
        assertFalse(IntArgument.has(new JsonObject(), "index"));
        assertTrue("显式 null 也算「写了这个键」", IntArgument.has(payload("index", "null"), "index"));
    }

    @Test
    public void requiredUsesTheCallersOwnSentenceForAMissingField() {
        try {
            IntArgument.required(new JsonObject(), "index", "缺少 index（自定义说明）");
            fail("缺字段要 BAD_REQUEST");
        } catch (BadRequestException e) {
            assertEquals(ErrorCodes.BAD_REQUEST, e.code);
            assertEquals("缺少 index（自定义说明）", e.getMessage());
        }
        assertEquals(7, IntArgument.required(payload("index", "7"), "index", "不该用到"));
    }

    @Test
    public void indicesRefuseNegativeValues() {
        assertNull(IntArgument.optionalNonNegative(new JsonObject(), "index"));
        assertEquals(Integer.valueOf(0),
                IntArgument.optionalNonNegative(payload("index", "0"), "index"));
        BadRequestException e = null;
        try {
            IntArgument.optionalNonNegative(payload("index", "-1"), "index");
            fail("-1 不是序号");
        } catch (BadRequestException ex) {
            e = ex;
        }
        assertEquals(ErrorCodes.BAD_REQUEST, e.code);
        assertTrue("要说清 0 起: " + e.getMessage(), e.getMessage().contains("不能为负"));
        assertTrue("报错要带上收到的值: " + e.getMessage(), e.getMessage().contains("-1"));
    }

    @Test
    public void requiredNonNegativeCombinesBothRules() {
        assertEquals(2, IntArgument.requiredNonNegative(payload("index", "2"), "index", "缺"));
        try {
            IntArgument.requiredNonNegative(new JsonObject(), "index", "缺少 index");
            fail("缺字段要 BAD_REQUEST");
        } catch (BadRequestException e) {
            assertEquals("缺少 index", e.getMessage());
        }
        try {
            IntArgument.requiredNonNegative(payload("index", "-3"), "index", "缺少 index");
            fail("负数要 BAD_REQUEST");
        } catch (BadRequestException e) {
            assertTrue(e.getMessage().contains("不能为负"));
        }
    }

    /** The message has to name the field and the value, or the caller cannot fix anything. */
    @Test
    public void messagesNameTheKeyAndTheReceivedValue() {
        BadRequestException e = badRequest(payload("x", "\"left\""), "x");
        assertTrue(e.getMessage(), e.getMessage().contains("payload.x"));
        assertTrue("要带上收到的值: " + e.getMessage(), e.getMessage().contains("left"));
    }
}