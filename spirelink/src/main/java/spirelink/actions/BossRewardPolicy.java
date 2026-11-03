package spirelink.actions;

/**
 * Which vanilla {@code ProceedButton} transition a cleared act-boss room runs — one decision, for
 * every place that has to move the run past a boss (issue #114, S1).
 *
 * <p>The bug: after the act-1 boss the reward screen's {@code proceed} cleared the remaining rewards,
 * closed the screen and opened the <b>act map</b>. The boss relic is not on the map — it lives behind
 * the boss chest in {@code TreasureRoomBoss} ({@code BossChest.open()} is what calls
 * {@code BossRelicSelectScreen.open}) — so the pick was skipped for good, and because the act's map
 * was already exhausted the next state had {@code reachable=[]}: only {@code abandon_mid_run} left.
 * Vanilla's {@code ProceedButton.update()} never does that: for a {@code MonsterRoomBoss} it calls
 * {@code goToTreasureRoom()} (act 1/2) or the door/true-victory transition (act 3/4).</p>
 *
 * <p>Disassembled from {@code ProceedButton} (desktop-1.0.jar):
 * <pre>
 *   room = getCurrRoom();
 *   if (room instanceof MonsterRoomBoss) {
 *       if ("TheBeyond".equals(id))  { asc20 &amp;&amp; 2 bosses ? goToDoubleBoss() : goToVictoryRoomOrTheDoor(); }
 *       else if ("TheEnding".equals(id)) { goToTrueVictoryRoom(); }
 *   }
 *   if (screen == COMBAT_REWARD &amp;&amp; !(room instanceof TreasureRoomBoss)) {
 *       if (room instanceof MonsterRoomBoss) goToTreasureRoom();   // the boss chest room
 *       ...
 *   } else if (room instanceof TreasureRoomBoss) { goToNextDungeon(room); }
 * </pre>
 * The act-1/2 branch is the one this class pins: {@code goToTreasureRoom} and nothing else.</p>
 *
 * <p>Pure on purpose: the runtime facts (room simple class name, dungeon id, the ascension double-boss
 * flag) are handed in as strings/booleans, so the whole table is testable with {@code mvn test}
 * ({@code BossRewardPolicyTest}) — the call sites in {@link ActionExecutor} read
 * {@code AbstractDungeon}, which needs the game.</p>
 */
public final class BossRewardPolicy {

    /** Act 1/2 boss: the run continues in the boss chest room, where the relic pick happens. */
    public static final String GO_TO_TREASURE_ROOM = "goToTreasureRoom";
    /** Act 4 heart: the real ending. */
    public static final String GO_TO_TRUE_VICTORY_ROOM = "goToTrueVictoryRoom";
    /** Out of the boss chest room into the next act. */
    public static final String GO_TO_NEXT_DUNGEON = "goToNextDungeon";
    /** Ascension 20 double boss. */
    public static final String GO_TO_DOUBLE_BOSS = "goToDoubleBoss";
    /** Act 3: the door (act 4) or the victory screen. */
    public static final String GO_TO_VICTORY_ROOM_OR_THE_DOOR = "goToVictoryRoomOrTheDoor";

    /**
     * How long one boss hand-over may take before it counts as stuck rather than slow (#118).
     *
     * <p>One vanilla room fade is 0.8 s: {@code AbstractDungeon.updateFading} counts {@code fadeTimer}
     * down from 0.8 and only then flips {@code isFadingOut} off. #118 is why this is a <b>budget</b>
     * and not a flag: a "a transition is pending" test that can stay true forever turns {@code proceed}
     * into an action that answers ok and changes nothing, which the harness reports as {@code queued}
     * until the wall clock gives up.</p>
     */
    public static final long HAND_OVER_BUDGET_MS = 2000L;

    /** No hand-over is in flight: run it. */
    public static final int RUN = 0;
    /** The act transition is already committed ({@code AbstractDungeon.isDungeonBeaten}): wait. */
    public static final int ACT_TRANSITION = 1;
    /** We started this very room's hand-over moments ago: wait, do not restart its fade. */
    public static final int HAND_OVER_IN_FLIGHT = 2;

    /** Sentinel: the boss relic has not been resolved yet. */
    public static final String RELIC_PENDING = "!boss_relic_pending";
    /** Sentinel: we started this room's hand-over and its fade is (still) running. */
    public static final String IN_FLIGHT = "!transition_pending";
    /** Sentinel: the act transition is already committed by the game. */
    public static final String ACT_TRANSITION_PENDING = "!act_transition";

    /** {@code AbstractDungeon.id} of act 3 — its boss does not hand over to a boss chest. */
    private static final String THE_BEYOND = "TheBeyond";
    /** {@code AbstractDungeon.id} of the act-4 heart. */
    private static final String THE_ENDING = "TheEnding";

    private BossRewardPolicy() {
    }

    /**
     * True for the room whose boss just died in a normal act: its reward screen is the only place the
     * boss relic can still be saved, so its exit must not be the map (#114).
     *
     * @param roomClass {@code AbstractRoom#getClass().getSimpleName()}, may be {@code null}
     */
    public static boolean bossFightRoom(String roomClass) {
        return "MonsterRoomBoss".equals(roomClass);
    }

    /**
     * True when the boss still owes the act-boss relic pick, i.e. the transition is
     * {@code goToTreasureRoom} and the chest has to be opened before the act can end.
     *
     * <p>Act 3 ({@code TheBeyond}) and the heart ({@code TheEnding}) keep the door/true-victory
     * transition the mod already used — that split is unchanged here on purpose (no act-3 run has
     * been re-tested on a real machine).</p>
     */
    public static boolean chestRoomNext(String roomClass, String dungeonId) {
        return bossFightRoom(roomClass)
                && !THE_BEYOND.equals(dungeonId)
                && !THE_ENDING.equals(dungeonId);
    }

    /**
     * The {@code ProceedButton} method that carries a cleared boss room forward.
     *
     * @param roomClass  current room simple class name, may be {@code null}
     * @param dungeonId  {@code AbstractDungeon.id}, may be {@code null}
     * @param doubleBoss ascension 20 with two bosses in the act
     * @return the vanilla method name, or {@code null} when this room has no boss transition at all
     */
    public static String handOver(String roomClass, String dungeonId, boolean doubleBoss) {
        if (roomClass == null) {
            return null;
        }
        switch (roomClass) {
            case "MonsterRoomBoss":
                if (chestRoomNext(roomClass, dungeonId)) {
                    return GO_TO_TREASURE_ROOM;
                }
                // Act 3 / the heart: the door (or the A20 double boss), never the boss chest.
                return doubleBoss ? GO_TO_DOUBLE_BOSS : GO_TO_VICTORY_ROOM_OR_THE_DOOR;
            case "VictoryRoom":
                return GO_TO_TRUE_VICTORY_ROOM;
            case "TreasureRoomBoss":
                return GO_TO_NEXT_DUNGEON;
            default:
                return doubleBoss ? GO_TO_DOUBLE_BOSS : GO_TO_VICTORY_ROOM_OR_THE_DOOR;
        }
    }

    /**
     * What {@code proceed} may do to a boss room right now (#114 / #118).
     *
     * <p>The one rule that matters for #118: <b>pending is a budget, never a flag.</b> A "some room
     * change is already running" test that can stay true makes {@code proceed} answer ok without
     * changing anything; the harness then holds the action as {@code queued} until its wall clock
     * gives up, and the run is wedged (act stuck at {@code ui=treasure}, 60 s+ of frozen
     * {@code state_version}). Two situations may legitimately say "wait":</p>
     * <ul>
     *   <li>{@link #ACT_TRANSITION} — {@code AbstractDungeon.isDungeonBeaten} is set, i.e.
     *       {@code goToNextDungeon()} already committed the act switch and the game is loading the next
     *       act; running a second hand-over would only restart that fade;</li>
     *   <li>{@link #HAND_OVER_IN_FLIGHT} — <b>we</b> started this very room's hand-over less than
     *       {@link #HAND_OVER_BUDGET_MS} ago, so its fade is still running.</li>
     * </ul>
     * <p>Everything else — another room, a stale timestamp, a clock that went backwards — is
     * {@link #RUN}: retry beats waiting forever.</p>
     *
     * @param dungeonBeaten  {@code AbstractDungeon.isDungeonBeaten}
     * @param handOverAtMs   when we last started this room's hand-over, {@code 0} = never
     * @param sameRoom       that hand-over was started for the room we are in right now
     * @param nowMs          wall clock
     */
    public static int decision(boolean dungeonBeaten, long handOverAtMs, boolean sameRoom, long nowMs) {
        if (dungeonBeaten) {
            return ACT_TRANSITION;
        }
        if (sameRoom && handOverAtMs > 0L) {
            long age = nowMs - handOverAtMs;
            if (age >= 0L && age < HAND_OVER_BUDGET_MS) {
                return HAND_OVER_IN_FLIGHT;
            }
        }
        return RUN;
    }

    /** True when {@link #decision} says "do not touch the transition this time". */
    public static boolean waits(int decision) {
        return decision == ACT_TRANSITION || decision == HAND_OVER_IN_FLIGHT;
    }

    /**
     * The sentence a {@code proceed} answers with while the game is already switching acts (#118).
     *
     * <p>It has to say that nothing was restarted and that the run is moving by itself — an agent that
     * only sees "queued" sends the same act again and burns another wall-clock window.</p>
     */
    public static String actTransitionHint(long ageMs) {
        return "这一局已经离开 Boss 房、正在切下一幕（已等 " + Math.max(0L, ageMs)
                + "ms）；这次 proceed 没有推进也不会推进，请轮询 get_state 直到 ui 不再是 treasure";
    }

    /**
     * The sentence a {@code proceed} answers with while our own hand-over is still fading (#118).
     *
     * <p>Rejected instead of answered ok on purpose: the old answer was {@code ok} with nothing
     * started, so the caller saw {@code queued} forever.</p>
     */
    public static String handOverInFlightHint(long ageMs) {
        return "上一段 Boss 过场刚开始（已等 " + Math.max(0L, ageMs) + "ms / 预算 "
                + HAND_OVER_BUDGET_MS + "ms），房间正在切换；这次 proceed 没有重启过场，请等状态变化后再看"
                + " legal_actions";
    }

    /**
     * The sentence a refused boss {@code proceed} answers with.
     *
     * <p>#114 requires "做不到就明确拒绝 proceed 并说明原因，绝不能留下死地图": a transition that cannot
     * be run must be a visible business error, never a silent close that lands on an exhausted map.</p>
     */
    public static String handOverFailedHint(String roomClass) {
        return "Boss 战后的 proceed 必须先把这一局推进到 Boss 遗物房/下一幕，现在做不到"
                + "（room=" + (roomClass == null ? "null" : roomClass)
                + "，原版过场方法调用失败）；已拒绝，以免丢掉 Boss 遗物或留下只能 abandon_mid_run 的死地图";
    }

    /**
     * Label of the reward screen's exit action, so {@code legal_actions} says where {@code proceed}
     * actually goes instead of the generic "放弃剩余奖励" (#114).
     */
    public static String rewardExitLabel(String roomClass, String dungeonId, boolean allDone) {
        if (!bossFightRoom(roomClass)) {
            return allDone ? "继续" : "继续（放弃剩余奖励）";
        }
        String where = chestRoomNext(roomClass, dungeonId) ? "进入 Boss 宝箱房（选 Boss 遗物）"
                : "离开本幕";
        return allDone ? "继续（" + where + "）" : "继续（" + where + "，放弃剩余奖励）";
    }
}
