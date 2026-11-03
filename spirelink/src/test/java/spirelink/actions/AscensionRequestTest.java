package spirelink.actions;

import com.google.gson.JsonObject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #77 (the half that belongs to the action layer): {@code choose_ascension} used to read
 * {@code level} through {@code JsonPrimitive.getAsInt()}, so the <b>string</b> {@code "1"} was
 * coerced into 1, accepted, and then answered {@code queued} — which also blocked the next action
 * behind {@code ACTION_IN_FLIGHT} for about a second.
 *
 * <p>The parser is pure, so the whole type/range matrix is unit tested without a character-select
 * screen.</p>
 */
public class AscensionRequestTest {

    private static JsonObject level(String json) {
        JsonObject payload = new JsonObject();
        payload.add("level", new com.google.gson.JsonParser().parse(json));
        return payload;
    }

    private static void assertBadRequest(JsonObject payload) {
        try {
            AscensionRequest.parse(payload);
            fail("应该 BAD_REQUEST: " + payload);
        } catch (BadRequestException e) {
            assertEquals(ErrorCodes.BAD_REQUEST, e.code);
            assertNotNull("错误要有说明", e.getMessage());
        }
    }

    @Test
    public void integerLevelIsAccepted() {
        // The whole vanilla ladder is legal (QA round 6 pinned it down: 0/1/5/10/15/20).
        for (int step : AscensionRequest.LADDER) {
            assertEquals(step, AscensionRequest.parse(level(String.valueOf(step))).level);
        }
        assertEquals(0, AscensionRequest.parse(level("0")).level);
        assertEquals(1, AscensionRequest.parse(level("1")).level);
        assertEquals(20, AscensionRequest.parse(level("20")).level);
    }

    /**
     * QA round 6 (#100, ascension half): {@code level:3} was applied even though neither the game nor
     * {@code legal_actions} has that step - an accepted action that is never advertised.
     */
    @Test
    public void levelsOffTheLadderAreIllegalAction() {
        for (String bad : new String[] {"2", "3", "4", "6", "19"}) {
            try {
                AscensionRequest.parse(level(bad));
                fail("梯子外的等级要拒: " + bad);
            } catch (BadRequestException e) {
                assertEquals(ErrorCodes.ILLEGAL_ACTION, e.code);
                assertTrue("要报出梯子: " + e.getMessage(),
                        e.getMessage().contains("0/1/5/10/15/20"));
            }
        }
    }

    @Test
    public void ladderIsTheDocumentedSixSteps() {
        assertEquals(6, AscensionRequest.LADDER.length);
        assertEquals("0/1/5/10/15/20", AscensionRequest.ladderText());
        assertTrue(AscensionRequest.isLadderLevel(0));
        assertTrue(AscensionRequest.isLadderLevel(15));
        assertFalse(AscensionRequest.isLadderLevel(3));
        assertFalse(AscensionRequest.isLadderLevel(21));
    }

    /** The bug from the issue: a string went straight through. */
    @Test
    public void stringLevelIsRejected() {
        assertBadRequest(level("\"1\""));
        assertBadRequest(level("\"20\""));
        assertBadRequest(level("\"0\""));
    }

    /** A JSON number is fine as long as it has no fractional part (1.0 == 1). */
    @Test
    public void integralFloatingPointIsAccepted() {
        assertEquals(1, AscensionRequest.parse(level("1.0")).level);
        assertEquals(0, AscensionRequest.parse(level("0.0")).level);
    }

    /** 1.5 is a number but not a level: Gson's getAsInt() would have truncated it to 1. */
    @Test
    public void fractionalNumberIsRejected() {
        assertBadRequest(level("1.5"));
        assertBadRequest(level("0.5"));
        assertBadRequest(level("-0.5"));
    }

    @Test
    public void booleanAndNullAreRejected() {
        assertBadRequest(level("true"));
        assertBadRequest(level("null"));
        assertBadRequest(new JsonObject());
    }

    @Test
    public void negativeIsBadRequest() {
        assertBadRequest(level("-1"));
    }

    /** 99 is a real number but past the ladder: ILLEGAL_ACTION, same as before #77. */
    @Test
    public void aboveTheLadderIsIllegalAction() {
        try {
            AscensionRequest.parse(level("99"));
            fail("should reject");
        } catch (BadRequestException e) {
            assertEquals(ErrorCodes.ILLEGAL_ACTION, e.code);
            assertTrue("要说清上限: " + e.getMessage(), e.getMessage().contains("20"));
        }
    }

    /** The legacy {@code ascension} key keeps working (the executor accepts both). */
    @Test
    public void ascensionAliasIsAccepted() {
        JsonObject payload = new JsonObject();
        payload.addProperty("ascension", 5);
        AscensionRequest request = AscensionRequest.parse(payload);
        assertEquals(5, request.level);
        assertEquals("ascension", request.key);
    }

    @Test
    public void levelWinsOverTheAlias() {
        JsonObject payload = new JsonObject();
        payload.addProperty("level", 5);
        payload.addProperty("ascension", 1);
        assertEquals(5, AscensionRequest.parse(payload).level);
    }

    @Test
    public void nullPayloadIsBadRequest() {
        assertBadRequest((JsonObject) null);
    }
}
