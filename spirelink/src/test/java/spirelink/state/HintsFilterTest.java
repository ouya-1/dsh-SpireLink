package spirelink.state;

import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #49 follow-up: {@code hints} must survive {@link StateBuilder#filterBlocks} when it has
 * something to say.
 *
 * <p>The bug was that "hints" was never in the allow-list, so the remove loop dropped it and a ready
 * response only carried the liveness object ToolCatalog appends afterwards — {@code
 * hints.char_selected} / {@code hints.next} never reached the agent.
 */
public class HintsFilterTest {

    private static JsonObject bodyWithHints() {
        JsonObject body = new JsonObject();
        body.add("meta", new JsonObject());
        body.add("player", new JsonObject());
        JsonObject hints = new JsonObject();
        hints.addProperty("char_selected", "IRONCLAD");
        hints.addProperty("next", "embark");
        body.add("hints", hints);
        return body;
    }

    private static StateBuilder builder() {
        return new StateBuilder(null, "test");
    }

    /**
     * {@code wantHints} also answers true while a rejected-action flag is set, and that flag is
     * process-wide (ToolCatalog calls {@code noteRejection()}). The MCP tests in this same JVM leave
     * a rejection behind depending on class order, which made the "ready screen drops hints"
     * assertion below order-dependent. Pin the precondition instead of depending on the runner.
     */
    @Before
    public void noStaleRejection() {
        StateBuilder.noteAcceptance();
    }

    @Test
    public void readyCharSelectKeepsHints() {
        JsonObject body = bodyWithHints();
        builder().filterBlocks(body, "char_select", true, null, null);
        assertTrue("选人界面的 hints.char_selected/next 必须能到 agent 手里", body.has("hints"));
        assertTrue(body.getAsJsonObject("hints").has("char_selected"));
    }

    @Test
    public void readyOtherScreensStillDropHints() {
        JsonObject body = bodyWithHints();
        builder().filterBlocks(body, "map", true, null, null);
        assertFalse("已就绪的其它界面不该带 hints（省字符）", body.has("hints"));
    }

    @Test
    public void notReadyKeepsHints() {
        JsonObject body = bodyWithHints();
        builder().filterBlocks(body, "combat", false, null, null);
        assertTrue("未就绪时 hints 一定要出现（老行为）", body.has("hints"));
    }

    @Test
    public void excludeStillRemovesHintsOnCharSelect() {
        JsonObject body = bodyWithHints();
        Set<String> exclude = new LinkedHashSet<String>();
        exclude.add("hints");
        builder().filterBlocks(body, "char_select", true, null, exclude);
        assertFalse("exclude:[\"hints\"] 必须还能删掉", body.has("hints"));
    }
}
