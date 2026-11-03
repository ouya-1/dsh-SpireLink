package spirelink.mcp;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #60: {@code dev_reward{"kind":"bogus"}} silently produced a <b>card</b> reward (unknown
 * kinds fell through to the default branch) while {@code kind:"key"} was refused even though
 * {@code docs/MCP-返回格式.md} §12.3 lists it.
 *
 * <p>The whitelist is pure, so the whole matrix is testable without a dungeon.</p>
 */
public class DevRewardToolTest {

    @Test
    public void everyDocumentedKindIsAccepted() {
        for (String kind : new String[] { "card", "gold", "relic", "potion", "key" }) {
            assertEquals(kind, DevRewardTool.normalizeKind(kind));
        }
    }

    @Test
    public void kindsAreCaseAndWhitespaceInsensitive() {
        assertEquals("gold", DevRewardTool.normalizeKind(" GOLD "));
        assertEquals("key", DevRewardTool.normalizeKind("Key"));
    }

    /** The default documented in §12.3: a missing kind means "card". */
    @Test
    public void blankDefaultsToCard() {
        assertEquals(DevRewardTool.DEFAULT_KIND, DevRewardTool.normalizeKind(null));
        assertEquals(DevRewardTool.DEFAULT_KIND, DevRewardTool.normalizeKind(""));
        assertEquals(DevRewardTool.DEFAULT_KIND, DevRewardTool.normalizeKind("   "));
    }

    /** The whole point of the issue: an unknown kind must not silently become a card reward. */
    @Test
    public void unknownKindIsRejectedInsteadOfFallingBackToCard() {
        assertNull(DevRewardTool.normalizeKind("bogus"));
        assertNull(DevRewardTool.normalizeKind("cards"));
        assertNull(DevRewardTool.normalizeKind("goldx"));
        assertNull(DevRewardTool.normalizeKind("reward"));
    }

    @Test
    public void unknownKindMessageListsEveryLegalValue() {
        String message = DevRewardTool.unknownKindMessage("bogus");
        for (String kind : DevRewardTool.KINDS) {
            assertTrue("提示里要列出 " + kind + ": " + message, message.contains(kind));
        }
        assertTrue("提示里要回显收到的值: " + message, message.contains("bogus"));
    }

    /** key 的取舍：文档说支持，所以实现它（docs/ 不在本任务的改动范围里）。 */
    @Test
    public void keyIsPartOfTheWhitelistNotAnAfterthought() {
        assertTrue(DevRewardTool.KINDS.contains("key"));
    }

    @Test
    public void kindsAreInDocumentationOrder() {
        assertEquals(Arrays.asList("card", "gold", "relic", "potion", "key"), DevRewardTool.KINDS);
    }

    @Test
    public void errorPayloadShapeIsUnchanged() {
        com.google.gson.JsonObject out = DevRewardTool.err("BAD_REQUEST", "x");
        assertEquals(false, out.get("ok").getAsBoolean());
        assertEquals("BAD_REQUEST", out.get("error").getAsString());
        assertEquals("x", out.get("message").getAsString());
        assertNotNull(out.get("message"));
    }
}
