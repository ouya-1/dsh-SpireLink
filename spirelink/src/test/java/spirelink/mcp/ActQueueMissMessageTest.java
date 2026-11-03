package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import spirelink.state.StateBuilder;
import spirelink.util.ModLog;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #97 / C3-06: what an {@code act} says when it never reached the main thread.
 *
 * <p>QA sent two acts at once; the rejected one answered "主线程 10 秒内没有执行这条动作，可以安全重试"
 * after they had waited only 2.8 s, and the other act of the same type had been <em>applied</em>. Two
 * lies in one sentence:
 * <ul>
 *   <li>the 10 s is only true for a queue {@code TIMEOUT}; a single-flight {@code BUSY} rejection
 *       comes back in milliseconds, so the number has to be the measured wait;</li>
 *   <li>"safe to retry" is wrong when the competing action already moved the world - the retry would
 *       stack a second click onto a screen that is gone.</li>
 * </ul>
 *
 * <p>The message is built by {@link ToolCatalog#queueMissPayload}, so this test drives that directly:
 * no queue, no game, no 10 s wait.</p>
 *
 * <p>Issue #108 · C7-02 (same payload, the other half): a single-flight {@code BUSY} rejection means
 * an action is in flight, so it must answer {@code ACTION_IN_FLIGHT} — the code the error table and
 * the skill docs name for exactly this situation — instead of {@code NOT_READY}. {@code TIMEOUT}
 * keeps {@code NOT_READY}: nothing of ours is in flight any more, the main thread just never ran the
 * command. Both are asserted here because the distinction is what stops the two guards from
 * describing the same refusal with two different codes.
 */
public class ActQueueMissMessageTest {

    private ToolCatalog catalog;
    private StateBuilder stateBuilder;

    @Before
    public void setUp() {
        ModLog log = new ModLog(new File("target/act-queue-miss-test"), false);
        stateBuilder = new StateBuilder(log, "test");
        catalog = new ToolCatalog(new MainThreadQueue(), stateBuilder, log);
    }

    @After
    public void tearDown() {
        spirelink.state.StateBuilder.noteAcceptance();
        spirelink.ui.AgentHud.clear();
    }

    private JsonObject payload(MainThreadQueue.Result.Status status, long waitedMs, int versionBefore) {
        JsonObject args = new JsonParser().parse("{\"index\":2}").getAsJsonObject();
        JsonObject payload = catalog.queueMissPayload("choose_map_node", args, status, waitedMs,
                versionBefore);
        assertNotNull(payload);
        return payload;
    }

    private static String message(JsonObject payload) {
        JsonObject error = payload.getAsJsonObject("error");
        assertNotNull("error 必须是对象: " + payload, error);
        return error.get("message").getAsString();
    }

    /** 单飞队列打回：文案必须说实测等待时长，不许再写「10 秒」。 */
    @Test
    public void busyQueueReportsTheMeasuredWaitInsteadOfTenSeconds() {
        String message = message(payload(MainThreadQueue.Result.Status.BUSY, 2800, 0));

        assertTrue("要说清实际等了多久: " + message, message.contains("2800ms"));
        assertTrue("要说清是单飞队列打回: " + message, message.contains("单飞"));
        assertTrue("要给下一步: " + message, message.contains("get_state"));
        assertFalse("不许再声称等了 10 秒: " + message, message.contains("10 秒"));
        assertFalse("不许再说「可以安全重试」: " + message, message.contains("可以安全重试"));
    }

    /** 真超时：既说实测时长，也说队列上限，两者都要能对上。 */
    @Test
    public void timeoutReportsBothTheMeasuredWaitAndTheQueueLimit() {
        String message = message(payload(MainThreadQueue.Result.Status.TIMEOUT, 10012, 0));

        assertTrue("实测时长: " + message, message.contains("10012ms"));
        assertTrue("队列上限: " + message, message.contains(ToolCatalog.COMMAND_TIMEOUT_MS + "ms"));
        assertFalse("不许用「10 秒」这种写死的说法: " + message, message.contains("10 秒"));
        assertFalse(message.contains("可以安全重试"));
    }

    /** 版本在等待期间推进了 = 另一条动作已生效：必须明说别重发。 */
    @Test
    public void advancedVersionSaysTheOtherActionLanded() {
        int current = stateBuilder.version();
        JsonObject payload = payload(MainThreadQueue.Result.Status.BUSY, 42, current - 1);
        String message = message(payload);

        assertTrue("要说清另一条已经生效: " + message, message.contains("另一个动作已经生效"));
        assertTrue("要把版本变化写出来: " + message,
                message.contains(String.valueOf(current - 1)) && message.contains(String.valueOf(current)));
        assertTrue("要明确「不要重发」: " + message, message.contains("不要重发"));
        assertTrue("payload 里也要有机器可读的标记",
                payload.get("version_advanced").getAsBoolean());
        assertEquals(current, payload.get("state_version").getAsInt());
    }

    /** 版本没动：仍然要求先 get_state 确认，而不是让 agent 蒙。 */
    @Test
    public void unchangedVersionAsksForAFreshStateBeforeRetrying() {
        int current = stateBuilder.version();
        JsonObject payload = payload(MainThreadQueue.Result.Status.TIMEOUT, 10000, current);
        String message = message(payload);

        assertTrue("要先 get_state: " + message, message.contains("get_state"));
        assertFalse("没推进就不许说「已经生效」: " + message, message.contains("另一个动作已经生效"));
        assertFalse(payload.get("version_advanced").getAsBoolean());
    }

    /** 读工具的超时文案不再是写死的「10 秒」，而是跟着 COMMAND_TIMEOUT_MS 走。 */
    @Test
    public void readTimeoutWordingFollowsTheConstant() {
        String getState = ToolCatalog.readTimeoutMessage("get_state");
        String legal = ToolCatalog.readTimeoutMessage("legal_actions");

        assertEquals(ToolCatalog.COMMAND_TIMEOUT_MS / 1000 + " 秒", getState.substring(
                getState.indexOf('（') + 1, getState.indexOf(" 秒") + 2));
        assertTrue(getState.contains("get_state"));
        assertTrue(legal.contains("legal_actions"));
        assertTrue("两句必须同源，只有工具名不同",
                getState.replace("get_state", "X").equals(legal.replace("legal_actions", "X")));
    }

    /** 机器可读字段齐全，且单飞队列打回用的是「动作在飞」那个错误码（C7-02）。 */
    @Test
    public void payloadCarriesMachineReadableFields() {
        // #125·C16-02 起：BUSY 只有在真的还有动作在飞时才算 ACTION_IN_FLIGHT。
        spirelink.util.ActionInFlight.mark(0, "choose_map_node");
        JsonObject payload = payload(MainThreadQueue.Result.Status.BUSY, 7, 0);

        assertEquals("ACTION_IN_FLIGHT",
                payload.getAsJsonObject("error").get("code").getAsString());
        spirelink.util.ActionInFlight.clear();
        assertEquals(7L, payload.get("waited_ms").getAsLong());
        assertEquals("BUSY", payload.get("queue_status").getAsString());
        assertTrue(payload.has("state_version"));
        assertEquals("rejected", payload.get("outcome").getAsString());
        assertEquals("choose_map_node",
                payload.getAsJsonObject("action").get("type").getAsString());
        // BUSY 不是 3 秒墙钟护栏在起作用，所以不能给一个假的 retry_after_ms（文案里已写明原因）。
        assertFalse(payload.has("retry_after_ms"));
    }

    /** C7-02：同一个 payload 生成器，两种队列失败对应两个不同的错误码。 */
    @Test
    public void busyIsActionInFlightAndTimeoutIsNotReady() {
        assertEquals("ACTION_IN_FLIGHT",
                ToolCatalog.queueMissCode(MainThreadQueue.Result.Status.BUSY));
        assertEquals("NOT_READY",
                ToolCatalog.queueMissCode(MainThreadQueue.Result.Status.TIMEOUT));
        assertEquals("BUSY 之外的未知状态也不能冒充在飞",
                "NOT_READY", ToolCatalog.queueMissCode(null));

        spirelink.util.ActionInFlight.mark(0, "choose_map_node");
        assertEquals("ACTION_IN_FLIGHT",
                payload(MainThreadQueue.Result.Status.BUSY, 5, 0)
                        .getAsJsonObject("error").get("code").getAsString());
        spirelink.util.ActionInFlight.clear();
        assertEquals("NOT_READY",
                payload(MainThreadQueue.Result.Status.TIMEOUT, 10000, 0)
                        .getAsJsonObject("error").get("code").getAsString());
    }

    /**
     * Issue #125 · C16-02：队列被占但没有动作在飞时，不许报 ACTION_IN_FLIGHT。
     *
     * <p>QA 看到的是「过渡那一帧占着单飞队列」：{@code waited_ms:0} + {@code queue_status:BUSY}，而
     * 我们没有任何动作在落地。这种时候让 agent 去「等自己的动作」是误导，正确的是 NOT_READY。</p>
     */
    @Test
    public void busyWithNothingInFlightAnswersNotReady() {
        spirelink.util.ActionInFlight.clear();
        JsonObject payload = payload(MainThreadQueue.Result.Status.BUSY, 0, 0);
        assertEquals("NOT_READY", payload.getAsJsonObject("error").get("code").getAsString());
        String message = payload.getAsJsonObject("error").get("message").getAsString();
        assertTrue("要说清是主线程在忙别的东西: " + message, message.contains("主线程正忙着执行别的东西"));
        assertFalse("不许再说「已经有一条动作在排队」: " + message,
                message.contains("已经有一条动作在排队"));
    }

    /** C7-02 的文案：说明这是队列占用而不是墙钟护栏，别让 agent 去找 retry_after_ms。 */
    @Test
    public void busyMessageExplainsThatThisIsNotTheWallClockGuard() {
        String message = message(payload(MainThreadQueue.Result.Status.BUSY, 12, 0));

        assertTrue("要说清是队列被占: " + message, message.contains("单飞"));
        assertTrue("要说明为什么没有 retry_after_ms: " + message,
                message.contains("retry_after_ms"));
        assertTrue("要和墙钟护栏区分开: " + message, message.contains("墙钟"));
    }
}
