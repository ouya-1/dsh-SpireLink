package spirelink.mcp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #124 · C15-01 / C15-03：两处「同一件事两种说法」。
 *
 * <ul>
 *   <li>C15-01：过渡期 {@code act} 拒绝回包把 {@code ui} 报成旧值 "combat"，而同拍
 *       {@code get_state.meta.ui} / {@code legal_actions.ui} 都是 "other"，且该回包
 *       {@code blocked_ui / awaiting} 为 null —— 现在拒绝回包读的是状态层「本帧真正上报的 ui」，
 *       过渡期还会补齐这两个字段。</li>
 *   <li>C15-03：{@code dev_use_card} 在 {@code energy=0} 时 reason 仍写「按规则可打出」—— 现在按能量解释。</li>
 * </ul>
 */
public class RejectContextUiTest {

    // ------------------------------------------------------------ C15-03 dev_use_card 的 reason

    @Test
    public void theForcedPlaySaysEnergyWasNotEnough() {
        String reason = CardUseClassifier.reason(CardUseClassifier.PLAYED, 3, 0, null);
        assertTrue(reason, reason.contains("energy") || reason.contains("能量"));
        assertTrue("要点明能量不够: " + reason, reason.contains("能量只有 0"));
        assertTrue("要说明是强制调用绕过: " + reason, reason.contains("freeToPlayOnce"));
        assertFalse("不许再说「按规则可打出」: " + reason, reason.contains("按规则可打出"));
    }

    @Test
    public void enoughEnergyKeepsThePlainSentence() {
        String reason = CardUseClassifier.reason(CardUseClassifier.PLAYED, 1, 3, null);
        assertTrue(reason, reason.contains("按规则可打出"));
        assertTrue("把能量写出来，方便对账: " + reason, reason.contains("本回合能量 3"));
    }

    @Test
    public void unknownEnergyDoesNotGuess() {
        String reason = CardUseClassifier.reason(CardUseClassifier.PLAYED, 3, -1, null);
        assertTrue(reason, reason.contains("按规则可打出"));
        assertFalse("读不到就不说能量: " + reason, reason.contains("本回合能量"));
        // 旧的三参重载必须继续可用（其它调用点/测试仍在使用）
        assertTrue(CardUseClassifier.reason(CardUseClassifier.PLAYED, 3, null)
                .contains("按规则可打出"));
    }

    @Test
    public void theBlockedByRuleSentenceIsUnchanged() {
        String reason = CardUseClassifier.reason(CardUseClassifier.BLOCKED, -2, 0, null);
        assertTrue(reason, reason.contains("不可打出"));
        assertTrue(reason, reason.contains("cost=-2"));
    }

    // ------------------------------------------------------------ C15-01 拒绝回包的口径

    @Test
    public void rejectionsUseTheLiveReportedUi() throws java.io.IOException {
        String code = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/mcp/ToolCatalog.java"));
        int attach = code.indexOf("void attachRejectContext(JsonObject payload)");
        assertTrue(attach > 0);
        String body = code.substring(attach, Math.min(code.length(), attach + 1500));
        assertTrue("要优先用状态层本帧上报的 ui（而不是缓存 body 的旧 ui）",
                body.contains("spirelink.state.StateBuilder.reportedUi()"));
        assertTrue("没有上报值时才退回缓存", body.contains("cachedUi(stateBuilder.cachedView())"));
        assertTrue("过渡期要补 blocked_ui", body.contains("payload.addProperty(\"blocked_ui\", ui)"));
        assertTrue("过渡期要补 awaiting", body.contains("payload.addProperty(\"awaiting\", loading ? \"loading\" : \"transition\")"));
        assertTrue("判据来自两个窗口",
                body.contains("spirelink.state.LoadingWindow.active()")
                        && body.contains("spirelink.state.RunExitWindow.active()"));
    }

    /** 状态层必须真的把「上报值」记下来，否则上面的优先取法拿不到东西。 */
    @Test
    public void theStateLayerExposesWhatItReports() throws java.io.IOException {
        String builder = DevEventActLockTest.stripComments(
                DevEventActLockTest.read("src/main/java/spirelink/state/StateBuilder.java"));
        assertTrue(builder.contains("public static String reportedUi()"));
        assertTrue("deriveUi 里要写入上报价", builder.contains("lastReportedUi = reported;"));
        assertTrue("字段要是 volatile（跨线程读）", builder.contains("private static volatile String lastReportedUi;"));
    }
}
