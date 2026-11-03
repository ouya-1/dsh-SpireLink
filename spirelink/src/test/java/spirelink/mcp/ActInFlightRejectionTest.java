package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import spirelink.state.StateBuilder;
import spirelink.util.ActionInFlight;
import spirelink.util.ModLog;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #56, the observable half: when {@code act} is refused with {@code ACTION_IN_FLIGHT}, the
 * answer has to say how long the wall clock still has to run, so an agent does not have to trust the
 * docs (which claimed 3 s while the log said 22 s).
 *
 * <p>The guard is marked directly, so this test never touches the game: the rejection happens before
 * anything is queued on the main thread.
 */
public class ActInFlightRejectionTest {

    private ToolCatalog catalog;

    @Before
    public void setUp() {
        ModLog log = new ModLog(new File("target/act-inflight-test"), false);
        catalog = new ToolCatalog(new MainThreadQueue(), new StateBuilder(log, "test"), log);
        // A queued action on top of state_version 0 - exactly the situation the agent retries into.
        ActionInFlight.mark(0, "choose_map_node");
    }

    @After
    public void tearDown() {
        ActionInFlight.clear();
        // This test really goes through act(), so it touches process-wide state: the "last act was
        // rejected" flag (which makes get_state keep a hints block) and the HUD line. Leave the
        // world exactly as we found it - otherwise a later test that expects a clean ready payload
        // inherits our rejection (surefire runs every class in one JVM).
        spirelink.state.StateBuilder.noteAcceptance();
        spirelink.ui.AgentHud.clear();
    }

    /** 拒绝回包带 retry_after_ms，且文案写明墙钟预算。 */
    @Test
    public void rejectionCarriesRetryAfterMs() {
        JsonObject arguments = new JsonParser().parse(
                "{\"type\":\"end_turn\",\"state_version\":0,\"action_id\":\"qa-inflight-1\"}")
                .getAsJsonObject();
        ToolCatalog.ToolResult result = catalog.call(ToolCatalog.TOOL_ACT, arguments);

        assertTrue("在飞期间必须拒绝", result.isError);
        JsonObject error = result.payload.getAsJsonObject("error");
        assertNotNull(error);
        assertEquals("ACTION_IN_FLIGHT", error.get("code").getAsString());

        assertTrue("必须给出 retry_after_ms（issue #56）", result.payload.has("retry_after_ms"));
        long retryAfter = result.payload.get("retry_after_ms").getAsLong();
        assertTrue("剩余应在 (0, TIMEOUT_MS] 之间，实际 " + retryAfter,
                retryAfter > 0L && retryAfter <= ActionInFlight.TIMEOUT_MS);

        String message = error.get("message").getAsString();
        assertTrue("文案要给出墙钟预算: " + message,
                message.contains(String.valueOf(ActionInFlight.TIMEOUT_MS)));
        assertTrue("文案要告诉 agent 别重发: " + message, message.contains("不要重发"));
    }
}
