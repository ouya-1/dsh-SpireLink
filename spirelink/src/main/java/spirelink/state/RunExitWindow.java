package spirelink.state;

/**
 * Issue #123 · S2: the 0.8-1.6 s between "we abandoned the run" and "the main menu is really up".
 *
 * <p>现场（QA 第 14 轮，帧级证据）：战斗里 {@code abandon_mid_run} applied 后回 {@code respUi:"idle"}，
 * 随后 3 帧 {@code ui=idle}（界面清单里没有这个名字）+ 唯一动作 {@code proceed「打开地图」}；照做 applied
 * 又回 {@code respUi:"map"}，下一帧 {@code ui=map} 广告 {@code choose_map_node}，真发出去却被打回
 * {@code ILLEGAL_ACTION: ui=main_menu 下不能执行 choose_map_node} —— 画面其实已经在主菜单。</p>
 *
 * <p>根因不是「数据算错」，而是<b>过渡期还在描述一个正在消失的地牢</b>：放弃本局之后
 * {@code mode} 仍是 GAMEPLAY、{@code AbstractDungeon} 里旧战斗/旧地图还是原样，于是
 * {@code deriveUi} 老老实实报了 {@code idle}（无房间可判的兜底值）再报 {@code map}，planner 也就照着
 * 广告了 {@code proceed}/{@code choose_map_node}。过渡帧的屏既不是「已经过去」也不是「还没到」，
 * 拿它做决策必然白走。</p>
 *
 * <p>判据和 {@link LoadingWindow} 同构（都是<b>我们自己的点击</b>打点，不依赖游戏标志位时序）：</p>
 * <ol>
 *   <li>{@link #noteRunExit()}：mcp 在把 abandon_* 交给执行器之前打点；</li>
 *   <li>窗口结束：状态层算出的 ui 变成 {@code main_menu}（主菜单真的起来了）；</li>
 *   <li>上界 {@link #MAX_MS}：放弃失败/卡住时不能一直自称「正在离开」。</li>
 * </ol>
 *
 * <p>窗口内 {@code StateBuilder.deriveUi} 一律回 {@code other}（有文档的过渡桶），
 * {@code StableGate} 回 {@code ready:false / leaving_run}，因此过渡帧既不报旧屏、也不广告旧屏的动作。</p>
 */
public final class RunExitWindow {

    /** Upper bound: abandoning is a fade plus a menu rebuild, not a load. */
    static final long MAX_MS = 10_000L;

    /** Wall clock of the abandon click we sent; 0 = no window. */
    private static volatile long exitAtMs;

    /** The last raw ui the state layer derived (before the transition bucket is substituted). */
    private static volatile String lastUi;

    private RunExitWindow() {
    }

    /** Marks "we just handed an abandon_* action to the executor" (called before it runs). */
    public static void noteRunExit() {
        exitAtMs = System.currentTimeMillis();
    }

    /** Drops the window: the action never executed (queue busy / internal error / executor rejected). */
    public static void clear() {
        exitAtMs = 0L;
    }

    /** Called by {@link StateBuilder} with the ui it derived from the live game, before substitution. */
    public static void noteUi(String ui) {
        if (ui != null && !ui.trim().isEmpty()) {
            lastUi = ui;
        }
    }

    /**
     * The pure rule: an abandon click, still inside the ceiling, with the main menu not up yet.
     *
     * @param markerSet {@link #noteRunExit()} was called and not cleared
     * @param sinceMs   how long ago that was
     * @param ui        the ui the state layer last derived from the live game
     */
    static boolean activeFor(boolean markerSet, long sinceMs, String ui) {
        if (!markerSet || sinceMs < 0L || sinceMs >= MAX_MS) {
            return false;
        }
        // "main_menu" is the one ui that proves the run is really gone.
        return !"main_menu".equals(ui);
    }

    /** Live read, safe from any thread (two static reads and a string compare). */
    public static boolean active() {
        long at = exitAtMs;
        if (at == 0L) {
            return false;
        }
        boolean active = activeFor(true, System.currentTimeMillis() - at, lastUi);
        if (!active) {
            exitAtMs = 0L;
        }
        return active;
    }

    /** Test seam: JVM-wide statics must not leak between test cases. */
    static void resetForTests() {
        exitAtMs = 0L;
        lastUi = null;
    }
}
