package spirelink.state;

/**
 * Issue #119 · C10-05: what {@code hints.last_error_fresh} really means.
 *
 * <p>现场（QA 第 10 轮）：一次 {@code act} 回了 {@code queued} 之后，{@code hints.last_error_fresh}
 * 连续 13-65 秒都是 {@code true}。原因不是序号算错，而是「新鲜」只有一个条件
 * （{@code last_error_seq == act_seq}）：
 * <ul>
 *   <li>排队期间每次 {@code get_state} 都会因为「动画没结束」记一条读状态错误
 *       （{@code get_state → NOT_READY}），而读工具不推进 {@code act_seq} —— 于是这条读错误永远
 *       看起来像「刚才那次 act 的错」；</li>
 *   <li>排队的那条动作真的落地之后，世界已经往前走了，可 {@code act_seq} 仍然没变，旧错误继续
 *       被当成新鲜的。</li>
 * </ul>
 *
 * <p>所以「新鲜」要三个条件同时成立：</p>
 * <ol>
 *   <li>这条错误就是最后一次 {@code act} 记下的（{@code last_error_seq == act_seq}，原规则）；</li>
 *   <li>它是<b>动作</b>的错误，不是一次读状态失败（读失败解释的是「这次读为什么没内容」，
 *       不是「你刚才那条动作错了」）；</li>
 *   <li>从那以后世界没有再动过（决策点没换过）：动作落地/界面推进之后，旧错误就是历史。</li>
 * </ol>
 *
 * <p>纯函数 + 两个由调用方注入的时间戳，所以整条规则可以在没有游戏、没有真实时钟的单测里钉住
 * （{@code ErrorFreshnessTest}）。时间由 {@code StateBuilder#lastDecisionChangeAt()} 提供：
 * 它就是「最近一次 {@code state_version} 真的往前跳」的时刻，正是「世界动了」的定义。</p>
 */
public final class ErrorFreshness {

    /**
     * The two read tools that record their own refusals through the same channel as {@code act}.
     *
     * <p>Kept as plain strings (the protocol names, which do not change) so this class stays in
     * {@code state/} without importing the MCP layer: {@code mcp/ToolCatalog} calls
     * {@code CrashGuard.noteActionError(code, TOOL)} with exactly these two names when a read answers
     * {@code NOT_READY} / {@code INTERNAL}.</p>
     */
    static final String READ_GET_STATE = "get_state";
    static final String READ_LEGAL_ACTIONS = "legal_actions";

    private ErrorFreshness() {
    }

    /**
     * @param lastError the text {@code CrashGuard} recorded, or {@code null} when nothing ever failed
     * @return whether that text was produced by a read tool instead of by {@code act}
     */
    static boolean isReadRefusal(String lastError) {
        if (lastError == null) {
            return false;
        }
        // CrashGuard's format is "<type> → <CODE>" (or "where: throwable" for internal reports).
        int arrow = lastError.indexOf(" → ");
        if (arrow <= 0) {
            return false;
        }
        String source = lastError.substring(0, arrow);
        return READ_GET_STATE.equals(source) || READ_LEGAL_ACTIONS.equals(source);
    }

    /**
     * The whole rule, with every input passed in.
     *
     * @param errorSeq          {@code hints.last_error_seq}: the act call that recorded the error
     * @param actSeq            {@code hints.act_seq}: act calls so far
     * @param lastError         {@code hints.last_error}, {@code null}/empty when nothing ever failed
     * @param lastErrorAtMs     wall clock of the error
     * @param worldMovedAtMs    wall clock of the last real decision change
     *                          ({@code StateBuilder#lastDecisionChangeAt()})
     */
    public static boolean isFreshFor(long errorSeq, long actSeq, String lastError,
            long lastErrorAtMs, long worldMovedAtMs) {
        if (lastError == null || lastError.isEmpty()) {
            return false;
        }
        // (1) the error belongs to the latest act call.
        if (errorSeq != actSeq) {
            return false;
        }
        // (2) a read that answered NOT_READY is not an act error (C10-05).
        if (isReadRefusal(lastError)) {
            return false;
        }
        // (3) nothing has moved since the error was recorded: a queued action that finally landed,
        //     or any other decision-point change, turns the old error into history. <= so that an
        //     error recorded in the same millisecond as a bump still counts as newer.
        return lastErrorAtMs >= worldMovedAtMs;
    }
}
