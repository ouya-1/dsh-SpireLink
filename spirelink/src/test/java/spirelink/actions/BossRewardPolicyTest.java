package spirelink.actions;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * #114 (S1): 打死 Boss 后 {@code combat_reward} 的 proceed 直接「已打开地图」，跳过 Boss 遗物三选一，
 * 之后地图 {@code reachable=[]}、只能 {@code abandon_mid_run}。
 *
 * <p>根因不在某一行写错，而在「Boss 房间的 proceed 该跑哪个原版过场」这件事被写死在
 * {@code ActionExecutor} 里、且只在「房间已完成」那条分支里做对了：奖励屏那条分支对所有房间一律
 * 「清空奖励 → 关屏 → 打开地图」，于是 Boss 遗物房（{@code TreasureRoomBoss}）和它背后的
 * {@code BossRelicSelectScreen} 从来没被进入过。</p>
 *
 * <p>所以判断被抽成纯函数 {@link BossRewardPolicy}（房间类名/幕 id 都是字符串），表本身钉在这里；
 * 反汇编依据见该类 javadoc（{@code ProceedButton.update()}：{@code MonsterRoomBoss} → 奖励屏上
 * {@code goToTreasureRoom()}）。</p>
 */
public class BossRewardPolicyTest {

    private static final String ACT1 = "Exordium";
    private static final String ACT2 = "TheCity";
    private static final String ACT3 = "TheBeyond";
    private static final String ACT4 = "TheEnding";

    // ------------------------------------------------------------------ 房间归类

    /** 只有打完 Boss 的那间房需要这条规则；普通战斗/精英/宝箱房都不该被它接管。 */
    @Test
    public void onlyTheBossRoomIsABossFightRoom() {
        assertTrue(BossRewardPolicy.bossFightRoom("MonsterRoomBoss"));
        for (String room : new String[] {"MonsterRoom", "MonsterRoomElite", "TreasureRoom",
                "TreasureRoomBoss", "RestRoom", "EventRoom", "ShopRoom", "VictoryRoom", ""}) {
            assertFalse(room, BossRewardPolicy.bossFightRoom(room));
        }
        assertFalse(BossRewardPolicy.bossFightRoom(null));
    }

    /** 第 1/2 幕的 Boss 之后必须先进 Boss 宝箱房，遗物就在那里面。 */
    @Test
    public void actsOneAndTwoHandOverToTheChestRoom() {
        assertTrue(BossRewardPolicy.chestRoomNext("MonsterRoomBoss", ACT1));
        assertTrue(BossRewardPolicy.chestRoomNext("MonsterRoomBoss", ACT2));
        // 幕 id 读不到时按「还没到第 3 幕」处理：宁可进宝箱房，也不要开地图把遗物吞掉。
        assertTrue(BossRewardPolicy.chestRoomNext("MonsterRoomBoss", null));
    }

    /** 第 3 幕（TheBeyond）与第 4 幕心脏（TheEnding）沿用原来的门/真结局过场，这次不改。 */
    @Test
    public void theLaterActsKeepTheirOwnTransition() {
        assertFalse(BossRewardPolicy.chestRoomNext("MonsterRoomBoss", ACT3));
        assertFalse(BossRewardPolicy.chestRoomNext("MonsterRoomBoss", ACT4));
        // 非 Boss 房永远没有「下一个是宝箱房」这回事。
        assertFalse(BossRewardPolicy.chestRoomNext("MonsterRoom", ACT1));
        assertFalse(BossRewardPolicy.chestRoomNext(null, ACT1));
    }

    // ------------------------------------------------------------------ 过场方法

    /**
     * 原版方法名就是契约：{@code ActionExecutor} 拿这个字符串去反射调用 {@code ProceedButton}。
     * 名字拼错会退化成「反射失败」，所以值本身也要钉住。
     */
    @Test
    public void theTransitionNamesAreTheVanillaOnes() {
        assertEquals("goToTreasureRoom", BossRewardPolicy.GO_TO_TREASURE_ROOM);
        assertEquals("goToTrueVictoryRoom", BossRewardPolicy.GO_TO_TRUE_VICTORY_ROOM);
        assertEquals("goToNextDungeon", BossRewardPolicy.GO_TO_NEXT_DUNGEON);
        assertEquals("goToDoubleBoss", BossRewardPolicy.GO_TO_DOUBLE_BOSS);
        assertEquals("goToVictoryRoomOrTheDoor", BossRewardPolicy.GO_TO_VICTORY_ROOM_OR_THE_DOOR);
    }

    /** 第 1/2 幕 Boss：唯一的答案是 goToTreasureRoom（#114 的回归点）。 */
    @Test
    public void aNormalBossRoomGoesToTheChestRoomAndNowhereElse() {
        for (String act : new String[] {ACT1, ACT2, null}) {
            assertEquals(act, BossRewardPolicy.GO_TO_TREASURE_ROOM,
                    BossRewardPolicy.handOver("MonsterRoomBoss", act, false));
            // 双 Boss（A20）也不改变第 1/2 幕的去向。
            assertEquals(act, BossRewardPolicy.GO_TO_TREASURE_ROOM,
                    BossRewardPolicy.handOver("MonsterRoomBoss", act, true));
        }
    }

    @Test
    public void everyBossRoomGetsANonNullTransition() {
        for (String room : new String[] {"MonsterRoomBoss", "VictoryRoom", "TreasureRoomBoss"}) {
            for (String act : new String[] {ACT1, ACT2, ACT3, ACT4, null}) {
                for (boolean doubleBoss : new boolean[] {false, true}) {
                    assertNotNull(room + "/" + act + "/" + doubleBoss,
                            BossRewardPolicy.handOver(room, act, doubleBoss));
                }
            }
        }
        assertNull("没有房间就没有过场", BossRewardPolicy.handOver(null, ACT1, false));
    }

    @Test
    public void theLaterActBossesUseTheDoorOrTheDoubleBoss() {
        assertEquals(BossRewardPolicy.GO_TO_VICTORY_ROOM_OR_THE_DOOR,
                BossRewardPolicy.handOver("MonsterRoomBoss", ACT3, false));
        assertEquals(BossRewardPolicy.GO_TO_DOUBLE_BOSS,
                BossRewardPolicy.handOver("MonsterRoomBoss", ACT3, true));
        assertEquals(BossRewardPolicy.GO_TO_VICTORY_ROOM_OR_THE_DOOR,
                BossRewardPolicy.handOver("MonsterRoomBoss", ACT4, false));
    }

    @Test
    public void theOtherBossishRoomsKeepTheirTransitions() {
        assertEquals(BossRewardPolicy.GO_TO_TRUE_VICTORY_ROOM,
                BossRewardPolicy.handOver("VictoryRoom", ACT4, false));
        assertEquals(BossRewardPolicy.GO_TO_NEXT_DUNGEON,
                BossRewardPolicy.handOver("TreasureRoomBoss", ACT1, false));
        // 未知房间沿用旧兜底：双 Boss 就双 Boss，否则门/胜利。
        assertEquals(BossRewardPolicy.GO_TO_DOUBLE_BOSS,
                BossRewardPolicy.handOver("MonsterRoom", ACT1, true));
        assertEquals(BossRewardPolicy.GO_TO_VICTORY_ROOM_OR_THE_DOOR,
                BossRewardPolicy.handOver("MonsterRoom", ACT1, false));
    }

    /**
     * 回归点：这条规则里根本没有「打开地图」这个选项。旧代码在奖励屏上对每个房间都开地图，
     * 正是 #114 的死地图来源。
     */
    @Test
    public void theMapIsNeverAnAnswer() {
        for (String room : new String[] {"MonsterRoomBoss", "VictoryRoom", "TreasureRoomBoss"}) {
            String went = BossRewardPolicy.handOver(room, ACT1, false);
            assertFalse(room, went == null || went.toLowerCase(java.util.Locale.ROOT).contains("map"));
        }
    }

    // ------------------------------------------------------------------ 文案

    /** 做不到时要「明确拒绝并说明原因」，这句话必须说清是哪个房间、为什么、以及后果。 */
    @Test
    public void theRefusalExplainsItself() {
        String hint = BossRewardPolicy.handOverFailedHint("MonsterRoomBoss");
        assertNotNull(hint);
        assertTrue("要点出 proceed: " + hint, hint.contains("proceed"));
        assertTrue("要点出房间: " + hint, hint.contains("MonsterRoomBoss"));
        assertTrue("要说清后果（丢遗物/死地图）: " + hint, hint.contains("死地图"));
        assertTrue("要给出下一步该做什么: " + hint, hint.contains("Boss 遗物"));
        assertFalse("不许再把「打开地图」当出路", hint.contains("已打开地图"));
        // null 房间也不能拼出 "room=null" 之外的半句话。
        assertTrue(BossRewardPolicy.handOverFailedHint(null).contains("room=null"));
    }

    /** 奖励屏的出口 label 要说清 proceed 到底去哪（agent 只按 label 决策的情况真的存在）。 */
    @Test
    public void theRewardExitLabelSaysWhereProceedGoes() {
        assertEquals("继续", BossRewardPolicy.rewardExitLabel("MonsterRoom", ACT1, true));
        assertEquals("继续（放弃剩余奖励）",
                BossRewardPolicy.rewardExitLabel("MonsterRoom", ACT1, false));

        String bossDone = BossRewardPolicy.rewardExitLabel("MonsterRoomBoss", ACT1, true);
        assertTrue(bossDone, bossDone.contains("Boss 宝箱房"));
        assertFalse("都领完了就不该说放弃", bossDone.contains("放弃"));

        String bossLeft = BossRewardPolicy.rewardExitLabel("MonsterRoomBoss", ACT2, false);
        assertTrue(bossLeft, bossLeft.contains("Boss 宝箱房"));
        assertTrue("还有奖励没领要说清楚会被放弃: " + bossLeft, bossLeft.contains("放弃剩余奖励"));

        // 第 3 幕走的是门，别谎报「宝箱房」。
        String act3 = BossRewardPolicy.rewardExitLabel("MonsterRoomBoss", ACT3, false);
        assertFalse(act3, act3.contains("宝箱"));
        assertTrue(act3, act3.contains("离开本幕"));
    }

    // ------------------------------------------------------------------ #118 过场等待是有界的

    private static final long NOW = 1_000_000L;

    /**
     * #118 (S1) 的回归点：**"过场还没落地" 必须是预算，不能是标志**。
     *
     * <p>真机死锁的形态就是「判定永久为真」：{@code proceed} 每次都答 ok、什么都没起，于是 act 一直回
     * queued、state_version 冻结 60s+，只能靠墙钟超时把 in-flight 拆掉。所以这里钉死两条：
     * 只有「我们自己刚给这个房间起过场」才等，而且等的时间有上限。</p>
     */
    @Test
    public void aStuckHandOverIsRetriedAfterTheBudget() {
        assertEquals("从来没起过场：跑",
                BossRewardPolicy.RUN,
                BossRewardPolicy.decision(false, 0L, false, NOW));
        assertEquals("刚起 100ms：等（别重启它自己的 fade）",
                BossRewardPolicy.HAND_OVER_IN_FLIGHT,
                BossRewardPolicy.decision(false, NOW - 100L, true, NOW));
        assertEquals("刚好到预算：跑",
                BossRewardPolicy.RUN,
                BossRewardPolicy.decision(false, NOW - BossRewardPolicy.HAND_OVER_BUDGET_MS, true, NOW));
        assertEquals("#118：等了 60 秒也必须重试，不能永久等",
                BossRewardPolicy.RUN,
                BossRewardPolicy.decision(false, NOW - 60_000L, true, NOW));
        assertTrue("预算要有界（0 会退化成每次重启、太大等于卡死）",
                BossRewardPolicy.HAND_OVER_BUDGET_MS >= 1000L
                        && BossRewardPolicy.HAND_OVER_BUDGET_MS <= 5000L);
    }

    /** 等只对「同一个房间 + 我们起的」成立；别的房间或没时间戳一律直接跑。 */
    @Test
    public void theWaitOnlyAppliesToOurOwnFreshHandOver() {
        assertEquals("换房间了：跑",
                BossRewardPolicy.RUN,
                BossRewardPolicy.decision(false, NOW - 100L, false, NOW));
        assertEquals("有记录但没时间戳：跑",
                BossRewardPolicy.RUN,
                BossRewardPolicy.decision(false, -5L, true, NOW));
        // 时钟回拨（两个时间源）不能让判定永远为真。
        assertEquals("时间戳在未来：跑",
                BossRewardPolicy.RUN,
                BossRewardPolicy.decision(false, NOW + 5_000L, true, NOW));
    }

    /**
     * 切幕（{@code isDungeonBeaten}）优先于一切：{@code goToNextDungeon()} 已经把这个幕交给游戏了，
     * 再起一次过场只会重启它的淡出。
     */
    @Test
    public void aCommittedActTransitionAlwaysWins() {
        assertEquals(BossRewardPolicy.ACT_TRANSITION,
                BossRewardPolicy.decision(true, 0L, false, NOW));
        assertEquals("比「刚起过场」优先",
                BossRewardPolicy.ACT_TRANSITION,
                BossRewardPolicy.decision(true, NOW - 100L, true, NOW));
        assertEquals("比「很久以前起过」也优先",
                BossRewardPolicy.ACT_TRANSITION,
                BossRewardPolicy.decision(true, NOW - 60_000L, true, NOW));
    }

    @Test
    public void waitsCoversExactlyTheTwoWaitingDecisions() {
        assertFalse(BossRewardPolicy.waits(BossRewardPolicy.RUN));
        assertTrue(BossRewardPolicy.waits(BossRewardPolicy.ACT_TRANSITION));
        assertTrue(BossRewardPolicy.waits(BossRewardPolicy.HAND_OVER_IN_FLIGHT));
    }

    /** 两种"等"的文案都要说：这次没有重启过场、以及调用方该干什么（否则 agent 只会重发同一条 act）。 */
    @Test
    public void theWaitingHintsSayNothingWasRestarted() {
        String act = BossRewardPolicy.actTransitionHint(1234L);
        assertNotNull(act);
        assertTrue(act, act.contains("1234"));
        assertTrue("要说清在切下一幕: " + act, act.contains("下一幕"));
        assertTrue("要告诉调用方去轮询: " + act, act.contains("get_state"));

        String inFlight = BossRewardPolicy.handOverInFlightHint(120L);
        assertNotNull(inFlight);
        assertTrue(inFlight, inFlight.contains("120"));
        assertTrue("要报出预算: " + inFlight, inFlight.contains(String.valueOf(BossRewardPolicy.HAND_OVER_BUDGET_MS)));
        assertTrue("要说清没有重启过场: " + inFlight, inFlight.contains("没有重启"));
        // 负数（时钟回拨）不能拼出 "-5ms"。
        assertTrue(BossRewardPolicy.handOverInFlightHint(-5L).contains("0ms"));
        assertTrue(BossRewardPolicy.actTransitionHint(-5L).contains("0ms"));
    }

    /** 三个哨兵必须互不相同：调用方按字符串分派，撞车就会把「切幕」当成「自己的过场」。 */
    @Test
    public void theSentinelsAreDistinct() {
        assertFalse(BossRewardPolicy.RELIC_PENDING.equals(BossRewardPolicy.IN_FLIGHT));
        assertFalse(BossRewardPolicy.RELIC_PENDING.equals(BossRewardPolicy.ACT_TRANSITION_PENDING));
        assertFalse(BossRewardPolicy.IN_FLIGHT.equals(BossRewardPolicy.ACT_TRANSITION_PENDING));
        for (String sentinel : new String[] {BossRewardPolicy.RELIC_PENDING,
                BossRewardPolicy.IN_FLIGHT, BossRewardPolicy.ACT_TRANSITION_PENDING}) {
            assertNotNull(sentinel);
            assertTrue("哨兵要以 ! 开头，避免和原版方法名混淆: " + sentinel,
                    sentinel.startsWith("!"));
        }
    }
}
