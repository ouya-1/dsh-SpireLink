package spirelink.state;

/**
 * Issue #121 / #122 · C12-01: the window between "we clicked continue_run" and "a run screen is up".
 *
 * <p>现场（QA 第 12/13 轮，两轮真机）：{@code act{continue_run}} 回 {@code applied} 之后 0.8-1.6 秒里
 * {@code legal_actions} 仍是 {@code ready:true} + {@code actions:[abandon_run, continue_run]}，载入期还在
 * 广告 {@code continue_run}；agent 只读一帧就会以为没点上而重发。随后跳到 sv=3 的过渡帧，虽然
 * {@code ready:false}，但文案是通用模板（「屏正在切换」/「游戏还没静止」），
 * {@code meta.awaiting} 也不是 {@code "loading"}。</p>
 *
 * <p><b>两个被证伪的判据</b>（都写下来，免得以后又走回去）：</p>
 * <ul>
 *   <li><b>v1：{@code CardCrawlGame.loadingSave} 为真 且 {@code AbstractDungeon.player == null}</b> ——
 *       恒为假。{@code MenuButton.resumeGame()} 的第一步 {@code CharacterManager.loadChosenCharacter()}
 *       就 {@code putstatic AbstractDungeon.player}，玩家对象在点下去那一帧就存在了，地牢要等过场结束
 *       才建。真机证据：日志 grep「存档正在载入」= 0 次。</li>
 *   <li><b>v2：{@code !CardCrawlGame.isInARun()}</b> —— 关得太早。地牢一建好 {@code mode} 就是
 *       GAMEPLAY，而那时屏幕还没切过去（真机 sv=3 那几帧 {@code ui="other"}），恰恰是 QA 要求必须
 *       继续报「存档正在载入」的帧。</li>
 * </ul>
 *
 * <p>v3（当前）：<b>完全不看游戏标志位的时序</b>，只用两个我们自己的事实 + 一个上界：</p>
 * <ol>
 *   <li>{@link #noteRunStart()}：<b>我们</b>把 continue_run / embark 交给执行器的那一刻（mcp 打点）；</li>
 *   <li>{@link #noteUi(String)}：状态层每帧算出的 {@code ui}（{@link StateBuilder} 打点）——只要它还是
 *       「开局前的屏」（{@code main_menu} / {@code char_select} / {@code mode_select} / 过渡桶
 *       {@code other}），载入就没结束；</li>
 *   <li>上界 {@link #MAX_LOADING_MS}：载入失败/卡住时不能永远自称「正在载入」。</li>
 * </ol>
 *
 * <p>三个入口（{@code get_state} / {@code legal_actions} / {@code act}）都读 {@link #active()}，同一句话、
 * 同一个 {@code NOT_READY}；{@code StableGate} 把它当第一优先原因，于是 {@code meta.awaiting="loading"}、
 * 动作表不再构建（continue_run 不再被广告）。</p>
 */
public final class LoadingWindow {

    /**
     * Hard ceiling for the window. A real load is 1-5 s; a load that fails leaves no evidence behind,
     * so after this the mod stops claiming "loading" and lets the normal gate decide again.
     */
    static final long MAX_LOADING_MS = 30_000L;

    /** Wall clock of the run-start click we sent; 0 = no window. Written by {@code mcp}, read anywhere. */
    private static volatile long runStartedAtMs;

    /** The last {@code ui} the state layer derived. Written on the main thread, read anywhere. */
    private static volatile String lastUi;

    private LoadingWindow() {
    }

    /**
     * Marks "we just handed a run-start action (continue_run / embark) to the executor".
     *
     * <p>Called before the action is executed, so the window already covers the fade and the first
     * frames of the load - the agent may poll {@code get_state} while the click is still in the queue.</p>
     */
    public static void noteRunStart() {
        runStartedAtMs = System.currentTimeMillis();
    }

    /** Drops the window: the action never executed (rejected / queue busy / internal error). */
    public static void clear() {
        runStartedAtMs = 0L;
    }

    /** Called by {@link StateBuilder} with the ui it just derived (one tick of lag is irrelevant). */
    public static void noteUi(String ui) {
        if (ui != null && !ui.trim().isEmpty()) {
            lastUi = ui;
        }
    }

    /**
     * Screens that exist before the run does: while one of them is up, a run-start click is still
     * loading. {@code other} is the transition bucket (fade, dungeon construction) - not a decision
     * point, so the window stays open across it.
     */
    static boolean beforeRun(String ui) {
        if (ui == null) {
            // Nothing derived yet: that is the pre-click menu frame, not a run.
            return true;
        }
        return "main_menu".equals(ui) || "char_select".equals(ui) || "mode_select".equals(ui)
                || "other".equals(ui);
    }

    /**
     * The pure rule: a run-start click, still inside the ceiling, with no run screen up yet.
     *
     * @param markerSet {@link #noteRunStart()} was called and not cleared
     * @param sinceMs   how long ago that was
     * @param ui        the ui the state layer last derived
     */
    static boolean activeFor(boolean markerSet, long sinceMs, String ui) {
        if (!markerSet || sinceMs < 0L || sinceMs >= MAX_LOADING_MS) {
            return false;
        }
        return beforeRun(ui);
    }

    /**
     * Live read, safe from any thread (two static reads and a string compare - no game object is
     * dereferenced, nothing that needs the main thread). Retires the window once a run screen is up,
     * so a later return to the main menu can never look like "loading" again.
     */
    public static boolean active() {
        long startedAt = runStartedAtMs;
        if (startedAt == 0L) {
            return false;
        }
        boolean active = activeFor(true, System.currentTimeMillis() - startedAt, lastUi);
        if (!active) {
            // Window is over (a run screen appeared, or the ceiling passed): forget it for good.
            runStartedAtMs = 0L;
        }
        return active;
    }

    /** Test seam: JVM-wide statics must not leak between test cases. */
    static void resetForTests() {
        runStartedAtMs = 0L;
        lastUi = null;
    }
}
