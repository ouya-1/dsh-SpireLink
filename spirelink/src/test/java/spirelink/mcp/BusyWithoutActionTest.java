package spirelink.mcp;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #125 · C16-02：queue BUSY 不等于「你的动作还在落地」。
 *
 * <p>现场：过渡刚结束那一拍 {@code legal_actions} 已 {@code ready:true}，紧接着的 {@code act} 却被
 * {@code ACTION_IN_FLIGHT}（{@code queue_status:"BUSY"}, {@code waited_ms:0}）打回 —— 那时单飞队列
 * 只是被**过渡那一帧**占着，我们没有任何动作在飞。现在只有 {@code ActionInFlight} 真的还挂着时才报
 * {@code ACTION_IN_FLIGHT}，否则回 {@code NOT_READY} 并说清「主线程在忙别的东西」。</p>
 */
public class BusyWithoutActionTest {

    @Test
    public void busyWithNothingInFlightIsNotReady() {
        assertEquals("NOT_READY", ToolCatalog.queueMissCode(true, false));
        assertEquals("ACTION_IN_FLIGHT", ToolCatalog.queueMissCode(true, true));
        assertEquals("NOT_READY", ToolCatalog.queueMissCode(false, false));
    }

    @Test
    public void theLegacyOverloadStillSaysActionInFlight() {
        // 其它调用点/测试仍用单参版本：BUSY 一律按「动作在飞」解释（老行为）。
        assertEquals("ACTION_IN_FLIGHT",
                ToolCatalog.queueMissCode(MainThreadQueue.Result.Status.BUSY));
        assertEquals("NOT_READY",
                ToolCatalog.queueMissCode(MainThreadQueue.Result.Status.TIMEOUT));
    }

    @Test
    public void theSentenceSaysTheMainThreadIsBusyNotThatAnActionLanded() {
        String original = "主线程队列里已经有一条动作在排队（单飞队列，不再接收第二条），这条没有执行：0ms";
        String rewritten = ToolCatalog.busyButNothingInFlight(original);
        assertTrue(rewritten, rewritten.contains("主线程正忙着执行别的东西"));
        assertTrue(rewritten, rewritten.contains("不是我们上一条动作在落地"));
        assertFalse("不许再说「有一条动作在排队」", rewritten.contains("已有一条动作在排队"));
        assertNull("没有原文就不要编", ToolCatalog.busyButNothingInFlight(null));
    }

    @Test
    public void thePayloadOnlyClaimsInFlightWhenSomethingIs() throws IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        int miss = code.indexOf("JsonObject queueMissPayload(");
        assertTrue(miss > 0);
        String body = code.substring(miss, Math.min(code.length(), miss + 2000));
        assertTrue("要看 ActionInFlight 真的挂没挂",
                body.contains("spirelink.util.ActionInFlight.blocked(versionAfter) >= 0"));
        assertTrue("按它决定文案", body.contains("busyButNothingInFlight("));
        assertTrue("错误码也要按它决定",
                body.contains("queueMissCode(status == MainThreadQueue.Result.Status.BUSY, actionPending)"));
    }
}
