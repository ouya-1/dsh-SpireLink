package spirelink.actions;

/**
 * Pure decision for {@code ui=map} + {@code proceed} (issue #100, S1 — the soft lock).
 *
 * <p>QA #100: with no reachable node (and no boss to enter) the planner still handed out a lone
 * {@code proceed} "关闭地图" — and the executor never closed the map with it. While {@code
 * AbstractDungeon.screen == MAP} the room branches below it matched first: a cleared rest/shop/event
 * room took the "click the overlay proceed button" branch, whose button does nothing while the map
 * owns the screen. The action answered ok, the state signature never moved, the harness called it
 * {@code queued} after 10.6 s and then refused every following action for 13 s
 * ({@code ACTION_IN_FLIGHT}). The only advertised action was a move that could never land.</p>
 *
 * <p>So while the map is open, the planner and the executor ask this one class what the map can
 * still do. {@code proceed} is advertised — and accepted — <b>only</b> for {@link #CLOSE_MAP}, and
 * that case really does close the map (the room is not finished, so returning to it is progress).
 * The other three answers are refusals that name a real alternative, never a no-op.</p>
 */
public final class MapExitPolicy {

    /** There are nodes to walk to: {@code choose_map_node}, never {@code proceed}. */
    public static final int PICK_NODE = 0;
    /** Last row cleared: the boss icon is the way forward (a hitbox, not a node). */
    public static final int PICK_BOSS = 1;
    /** No node, no boss, but the room is unfinished: closing the map returns to it — real progress. */
    public static final int CLOSE_MAP = 2;
    /**
     * No node, no boss, room finished: leaving the map changes nothing at all. This is the state
     * that must never be advertised as {@code proceed} (it is the #100 soft lock).
     */
    public static final int DEAD_END = 3;

    private MapExitPolicy() {
    }

    /**
     * @param reachableCount  how many nodes {@code ActionPlanner#reachableNodes()} returned
     * @param bossAvailable   {@code ActionPlanner#bossAvailable()}
     * @param canReturnToRoom there is a current room and it is <b>not</b> finished, so closing the
     *                        map hands control back to a room the player can still act in. Note the
     *                        {@code room == null} case (an act that has no current node yet) is
     *                        {@code false} on purpose: with nothing to go back to, closing the map is
     *                        as meaningless as it is on a finished room.
     * @return one of {@link #PICK_NODE} / {@link #PICK_BOSS} / {@link #CLOSE_MAP} / {@link #DEAD_END}
     */
    public static int decide(int reachableCount, boolean bossAvailable, boolean canReturnToRoom) {
        if (reachableCount > 0) {
            return PICK_NODE;
        }
        if (bossAvailable) {
            return PICK_BOSS;
        }
        return canReturnToRoom ? CLOSE_MAP : DEAD_END;
    }

    /** True only when {@code proceed} is a real, state-changing move. */
    public static boolean proceedClosesMap(int decision) {
        return decision == CLOSE_MAP;
    }

    /**
     * #110: the one-sentence hint for a refusal <b>that happens while the map is open</b>. It may
     * only name an action this very state can run.
     *
     * <p>The report: a rejected {@code choose_map_node} answered {@code "房间还没结束（phase=…），
     * 先 proceed 离开"} — while the same tick offered nodes and therefore never advertised
     * {@code proceed} ({@link #proceedClosesMap} is false for {@link #PICK_NODE}). The tip pointed at
     * a move that cannot execute, which is how a wording bug turns into a dead end for the agent.</p>
     *
     * @param decision one of {@link #decide}'s answers
     */
    public static String executableHint(int decision) {
        switch (decision) {
            case PICK_NODE:
                return "先 " + ActionTypes.CHOOSE_MAP_NODE + "{x,y} 走一个能走的节点（这些节点现在就广告着）";
            case PICK_BOSS:
                return "先 " + ActionTypes.CHOOSE_MAP_NODE + "{x:-1,y:<地图行数>} 点 Boss 图标";
            case CLOSE_MAP:
                return "先 proceed 关闭地图（这一拍广告的就是它）";
            case DEAD_END:
            default:
                return "这一拍没有能走的节点，也没有能关的地图：可用动作见 legal_actions"
                        + "（要退出请用 " + ActionTypes.ABANDON_MID_RUN + "）";
        }
    }

    /** One human sentence per decision: the label when it may run, the refusal otherwise. */
    public static String message(int decision, int reachableCount, int bossY) {
        switch (decision) {
            case PICK_NODE:
                return "地图上还有 " + reachableCount + " 个能走的节点：用 choose_map_node{x,y} 选点，"
                        + "proceed 不会让你前进";
            case PICK_BOSS:
                return "这一层已经清完，前面是 Boss 图标：用 choose_map_node{x:-1,y:" + bossY + "}";
            case CLOSE_MAP:
                return "关闭地图（这一层没有能走的节点，当前房间还没结束）";
            case DEAD_END:
            default:
                return "地图上没有可达节点、也没有 Boss 入口，当前房间也已经结束 —— 这一局无法再前进："
                        + "proceed 不会改变任何状态，要退出请用 abandon_mid_run";
        }
    }
}