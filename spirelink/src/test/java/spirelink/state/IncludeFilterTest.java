package spirelink.state;

import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #58: {@code get_state}'s include/exclude must not invent keys and must not eat {@code meta}.
 *
 * <p>Two bugs, one method. (a) {@code include:["nosuchblock"]} answered with a {@code
 * "nosuchblock": {}} placeholder, i.e. the response carried whatever string the caller sent - 40
 * garbage names produced 40 garbage keys. (b) {@code include:["meta"] + exclude:["meta"]} removed
 * the one block that is always there, and with it {@code ui} / {@code state_version}.
 *
 * <p>Decision recorded here: unknown include names are <b>ignored</b>, not an error. include is a
 * "give me exactly these" list; a name the mod cannot answer has no honest representation, and a
 * hard failure would have to be raised in the transport layer (ToolCatalog) for a purely local
 * mistake. The legal names stay documented instead.
 */
public class IncludeFilterTest {

    private static final String UI = "map";

    private StateBuilder builder() {
        return new StateBuilder(null, "test");
    }

    @Before
    public void quietState() {
        // hints only travel when they explain something; a leftover rejection from another test in
        // this JVM would change the allow-list under us.
        StateBuilder.noteAcceptance();
    }

    private static JsonObject fullBody() {
        JsonObject body = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("ready", true);
        meta.addProperty("state_version", 12);
        meta.addProperty("ui", UI);
        body.add("meta", meta);
        JsonObject player = new JsonObject();
        player.addProperty("hp", 70);
        body.add("player", player);
        JsonObject combat = new JsonObject();
        combat.addProperty("turn", 2);
        body.add("combat", combat);
        JsonObject hints = new JsonObject();
        hints.addProperty("frames", 1L);
        body.add("hints", hints);
        return body;
    }

    private static Set<String> names(String... values) {
        Set<String> out = new LinkedHashSet<String>();
        for (String value : values) {
            out.add(value);
        }
        return out;
    }

    // ---------------------------------------------------------------- (a) unknown include keys

    @Test
    public void unknownIncludeKeyDoesNotOccupyAKey() {
        JsonObject body = fullBody();
        builder().filterBlocks(body, UI, true, names("nosuchblock"), null);
        assertFalse("#58a: 垃圾 key 不许出现在回包里（" + body + "）", body.has("nosuchblock"));
        assertTrue("meta 永远在", body.has("meta"));
    }

    @Test
    public void fortyGarbageKeysProduceZeroKeys() {
        Set<String> garbage = new LinkedHashSet<String>();
        for (int i = 0; i < 40; i++) {
            garbage.add("b" + i);
        }
        JsonObject body = fullBody();
        builder().filterBlocks(body, UI, true, garbage, null);
        assertEquals("40 个垃圾 key → 只剩 meta（不能凭空多出客户端的字符串）", 1, body.entrySet().size());
        assertTrue(body.has("meta"));
    }

    @Test
    public void unknownIncludeKeyDoesNotBreakKnownOnes() {
        JsonObject body = fullBody();
        builder().filterBlocks(body, UI, true, names("nosuchblock", "player"), null);
        assertTrue("已知块照给", body.has("player"));
        assertEquals(70, body.getAsJsonObject("player").get("hp").getAsInt());
        assertFalse(body.has("nosuchblock"));
        assertFalse("没请求的块照删", body.has("combat"));
    }

    @Test
    public void knownBlockRequestedButAbsentStillAnswersEmpty() {
        // The original contract for *real* block names is kept: an explicit request must answer,
        // otherwise "nothing here" and "the mod ignored me" look the same.
        JsonObject body = fullBody();
        builder().filterBlocks(body, UI, true, names("shop"), null);
        assertTrue(body.has("shop"));
        assertTrue("已知但当前为空的块回答 {}", body.getAsJsonObject("shop").entrySet().isEmpty());
        assertTrue(body.has("meta"));
    }

    @Test
    public void virtualIncludeNamesAreNotInvented() {
        // deck / *&#95;pile / briefing are answered by ToolCatalog after filtering (and by memory for
        // briefing); the body itself must not grow a placeholder for them.
        JsonObject body = fullBody();
        builder().filterBlocks(body, UI, true,
                names("deck", "discard_pile", "exhaust_pile", "draw_pile", "briefing"), null);
        assertFalse(body.has("deck"));
        assertFalse(body.has("discard_pile"));
        assertFalse(body.has("exhaust_pile"));
        assertFalse(body.has("draw_pile"));
        assertFalse(body.has("briefing"));
        assertTrue(body.has("meta"));
    }

    // ---------------------------------------------------------------- (b) meta is never optional

    @Test
    public void metaSurvivesIncludePlusExclude() {
        JsonObject body = fullBody();
        builder().filterBlocks(body, UI, true, names("meta"), names("meta"));
        assertTrue("#58b: meta 不可被 exclude", body.has("meta"));
        JsonObject meta = body.getAsJsonObject("meta");
        assertEquals("meta.ready 是 state_version 是否有效的唯一依据", true,
                meta.get("ready").getAsBoolean());
        assertEquals(12, meta.get("state_version").getAsInt());
        assertEquals(UI, meta.get("ui").getAsString());
    }

    @Test
    public void metaSurvivesExcludeInDefaultMode() {
        JsonObject body = fullBody();
        builder().filterBlocks(body, UI, true, null, names("meta", "player"));
        assertTrue(body.has("meta"));
        assertFalse("其它块照常可排除", body.has("player"));
    }

    @Test
    public void unknownExcludeKeyIsHarmless() {
        JsonObject body = fullBody();
        builder().filterBlocks(body, UI, true, null, names("nosuchblock"));
        assertTrue(body.has("meta"));
        assertTrue(body.has("player"));
    }

    @Test
    public void excludeStillRemovesOtherBlocks() {
        // Regression lock: meta became un-excludable, everything else must stay excludable.
        JsonObject body = fullBody();
        builder().filterBlocks(body, "combat", false, names("player", "combat"), names("combat"));
        assertFalse(body.has("combat"));
        assertTrue(body.has("player"));
        assertTrue(body.has("meta"));
    }

    @Test
    public void includeWhitelistKeepsOnlyWhatWasAsked() {
        JsonObject body = fullBody();
        builder().filterBlocks(body, UI, true, names("player"), null);
        assertTrue(body.has("player"));
        assertTrue(body.has("meta"));
        assertFalse(body.has("combat"));
        assertFalse(body.has("hints"));
    }
}
