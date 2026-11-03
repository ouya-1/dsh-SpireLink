package spirelink.actions;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Source-level wiring for #114 (S1), #116 and #118 (S1).
 *
 * <p>What a headless test cannot reach is the call site: {@code ActionExecutor#proceed} and
 * {@code #takeReward} read {@code AbstractDungeon}/{@code CombatRewardScreen}, whose static
 * initialisers need the game. The three bugs are exactly call sites that drifted away from a rule that
 * is testable on its own — a reward screen that opened the map instead of the boss chest (#114), a
 * rejection that named an action the screen did not have (#116a), and a vanilla claim that swept a
 * neighbouring reward row away (#116b) — so the call sites are pinned here, the same way
 * {@code Fix8ActionsWiringTest} / {@code GateWiringTest} do it.</p>
 *
 * <p>The pure cores are tested properly in {@link BossRewardPolicyTest} (#114) and
 * {@link ActionLegalityTest} (#116a).</p>
 */
public class Fix9ActionsWiringTest {

    private static String read(String relative) throws IOException {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
            dir = dir.getParentFile();
        }
        throw new IOException("找不到 " + relative);
    }

    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        if (at < 0) {
            fail("找不到方法: " + signature);
        }
        int cut = source.length();
        for (String next : new String[] {"\n    private static ", "\n    static ", "\n    public static "}) {
            int candidate = source.indexOf(next, at + signature.length());
            if (candidate > at && candidate < cut) {
                cut = candidate;
            }
        }
        return source.substring(at, cut);
    }

    /** The {@code chars} characters right after {@code marker} (assertions must stay local). */
    private static String after(String source, String marker, int chars) {
        int at = source.indexOf(marker);
        if (at < 0) {
            fail("找不到: " + marker);
        }
        return source.substring(at, Math.min(source.length(), at + chars));
    }

    private static int countOf(String source, String marker) {
        int count = 0;
        int at = source.indexOf(marker);
        while (at >= 0) {
            count++;
            at = source.indexOf(marker, at + marker.length());
        }
        return count;
    }

    private static String executor() throws IOException {
        return read("src/main/java/spirelink/actions/ActionExecutor.java");
    }

    private static String planner() throws IOException {
        return read("src/main/java/spirelink/actions/ActionPlanner.java");
    }

    // ---------------------------------------------------------------- #114 proceed

    /**
     * #114: the reward screen's {@code proceed} must hand a boss room over to
     * {@code BossRewardPolicy} <b>before</b> the generic "drop the rewards, close the screen, open the
     * map" path. That generic path is what skipped the boss relic pick for good.
     */
    @Test
    public void theRewardScreenProceedSplitsBossRoomsBeforeItCanOpenTheMap() throws IOException {
        String proceed = methodBody(executor(), "private static ActionResult proceed()");
        int combatReward = proceed.indexOf("CurrentScreen.COMBAT_REWARD");
        int boss = proceed.indexOf("BossRewardPolicy.bossFightRoom(");
        int map = proceed.indexOf("openMapScreen()");
        assertTrue("COMBAT_REWARD 分支要存在", combatReward >= 0);
        assertTrue("#114: 奖励屏要先分流 Boss 房间", boss > combatReward);
        assertTrue("#114: Boss 房间的分流必须排在 openMapScreen() 之前", map < 0 || boss < map);
        assertTrue("Boss 房间交给 bossRewardProceed()", proceed.contains("return bossRewardProceed(rewardRoom);"));
    }

    /**
     * The hand-over must be attempted <b>first</b>: if the reflection call fails, the agent must not be
     * left on the same screen with its rewards already thrown away, and #114 requires an explicit
     * refusal ("做不到就明确拒绝 proceed 并说明原因，绝不能留下死地图").
     */
    @Test
    public void theBossHandOverRunsBeforeTheRewardsAreDropped() throws IOException {
        String body = methodBody(executor(), "private static ActionResult bossRewardProceed(AbstractRoom room)");
        int handOver = body.indexOf("handOverOrExplain(");
        int drop = body.indexOf("dropRewardLists()");
        assertTrue("Boss 过场要走 handOverOrExplain()", handOver >= 0);
        assertTrue("先跑过场，只有成功了才清奖励", handOver < drop);
        assertTrue("没推进就原样返回拒绝（不丢奖励）", body.contains("if (!moved.ok)"));
        assertFalse("Boss 奖励屏的 proceed 绝不能开地图", body.contains("openMapScreen("));

        // The refusals themselves live in the one shared answer (#118 moved them there).
        String explain = methodBody(executor(), "private static ActionResult handOverOrExplain(");
        assertTrue("做不到要明确拒绝（#114）", explain.contains("BossRewardPolicy.handOverFailedHint("));
        assertTrue("拒绝是 NOT_READY 业务错误", explain.contains("ErrorCodes.NOT_READY"));
        assertTrue("遗物没拿也要拒绝（沿用原判据）", explain.contains("BossRewardPolicy.RELIC_PENDING"));
    }

    /** The transition name must come from the policy: no room-type switch may grow back here. */
    @Test
    public void everyBossTransitionComesFromThePolicy() throws IOException {
        String body = methodBody(executor(), "private static String advanceFromBoss(");
        assertTrue("过场方法名由 BossRewardPolicy.handOver() 决定", body.contains("BossRewardPolicy.handOver("));
        for (String hardCoded : new String[] {"\"goToTreasureRoom\"", "\"goToVictoryRoomOrTheDoor\"",
                "\"goToDoubleBoss\"", "\"goToTrueVictoryRoom\"", "\"goToNextDungeon\""}) {
            assertFalse("方法名不许再写死: " + hardCoded, body.contains(hardCoded));
        }
        assertTrue("goToNextDungeon 要带 AbstractRoom 参数",
                body.contains("BossRewardPolicy.GO_TO_NEXT_DUNGEON"));
    }

    // ---------------------------------------------------------------- #118 proceed 死锁

    /**
     * #118 (S1): the "already under way, do not restart it" test must not ask a flag that can stay true
     * forever.
     *
     * <p>{@code AbstractDungeon.waitingOnFadeOut} is <b>write-only</b> in this build: the disassembly
     * shows {@code nextRoomTransitionStart()} setting it true and no read or clear anywhere in the jar.
     * The first act-1/2 hand-over therefore made it permanently true, so every later {@code proceed} -
     * including the "leave the boss chest room" one - answered ok without running a transition:
     * {@code ui=treasure} with {@code proceed} as the only action, {@code state_version} frozen for
     * 60 s+, and two wall-clock releases of an action that never landed.</p>
     */
    @Test
    public void theBossHandOverDoesNotTrustAFadeFlag() throws IOException {
        String body = methodBody(executor(), "private static String advanceFromBoss(");
        assertFalse("#118: 不许再看 waitingOnFadeOut（只写不读的死标志）",
                body.contains("waitingOnFadeOut"));
        assertFalse("#118: 也不许把 isFadingOut 当门（它不是我们的过场状态）",
                body.contains("isFadingOut"));
        assertTrue("有界判据来自 BossRewardPolicy.decision()",
                body.contains("BossRewardPolicy.decision("));
        assertTrue("要记下自己上次起过场的时间", body.contains("handOverAtMs"));
        assertTrue("要记下是给哪个房间起的过场", body.contains("handOverRoom"));
    }

    /**
     * #118 的第二半：一个跑不动的 {@code proceed} 必须回一个可理解的错误，绝不能答 ok。
     *
     * <p>{@code ToolCatalog} 只在 ok 且状态没动时才 {@code ActionInFlight.mark(...)}，所以「ok 但什么都没
     * 发生」正是「act 永远回 queued / 墙钟超时才解除」的来源。每个不推进的分支都必须 {@code reject}。</p>
     */
    @Test
    public void aProceedThatCannotAdvanceIsRejectedNotAnsweredOk() throws IOException {
        String body = methodBody(executor(), "private static ActionResult handOverOrExplain(");
        for (String sentinel : new String[] {"BossRewardPolicy.RELIC_PENDING",
                "BossRewardPolicy.ACT_TRANSITION_PENDING", "BossRewardPolicy.IN_FLIGHT"}) {
            assertTrue("每个「不跑」哨兵都要有分支: " + sentinel, body.contains(sentinel));
        }
        assertTrue("不推进的分支必须全部是拒绝（遗物/切幕/自身过场/反射失败）: "
                + countOf(body, "ActionResult.reject("), countOf(body, "ActionResult.reject(") >= 4);
        assertEquals("只有真调用过过场才允许 ok", 1, countOf(body, "ActionResult.ok()"));
        assertTrue("ok 只能出现在 advanceFromBoss() 之后",
                body.indexOf("advanceFromBoss(") < body.indexOf("ActionResult.ok()"));
        assertFalse("哨兵字符串只能定义在 BossRewardPolicy 里，executor 不许再写死",
                executor().contains("\"!transition_pending\""));
    }

    /**
     * 切幕那一段（{@code isDungeonBeaten}）也是「不推进」：它已经是游戏自己在推进，再起一次过场只会重启
     * 淡出；但同样必须回错误而不是 queued。
     */
    @Test
    public void aCommittedActTransitionIsExplainedNotQueued() throws IOException {
        String body = methodBody(executor(), "private static String advanceFromBoss(");
        int beaten = body.indexOf("AbstractDungeon.isDungeonBeaten");
        int invoke = body.indexOf("invokeProceed(");
        assertTrue("要问游戏是不是已经在切幕", beaten >= 0);
        assertTrue("切幕判断必须排在真正起过场之前", beaten < invoke);
        assertTrue("切幕时返回哨兵", body.contains("BossRewardPolicy.ACT_TRANSITION_PENDING"));

        String explain = methodBody(executor(), "private static ActionResult handOverOrExplain(");
        assertTrue("哨兵要变成 NOT_READY", explain.contains("BossRewardPolicy.actTransitionHint("));
    }

    /** legal_actions 里那个「继续」得说清去哪，否则 agent 只知道「放弃剩余奖励」。 */
    @Test
    public void theRewardExitLabelComesFromThePolicy() throws IOException {
        String body = methodBody(planner(), "private static void combatReward(List<ActionSpec> out)");
        assertTrue("退出 label 由 BossRewardPolicy 给（#114）",
                body.contains("BossRewardPolicy.rewardExitLabel("));
        assertFalse("不许再写死「继续（放弃剩余奖励）」", body.contains("\"继续（放弃剩余奖励）\""));
    }

    // ---------------------------------------------------------------- #116a 药水满

    /**
     * #116a: while a potion reward is skipped because the belt is full, the screen has to advertise the
     * discard that both {@code blocked_reason} and the {@code POTION_SLOT_FULL} rejection ask for.
     */
    @Test
    public void aFullBeltAdvertisesTheDiscardItAsksFor() throws IOException {
        String body = methodBody(planner(), "private static void combatReward(List<ActionSpec> out)");
        int skipped = body.indexOf("skippedPotion");
        int discard = body.indexOf("discardPotionOptions(out)");
        assertTrue("要认出「有药水奖励被跳过」", skipped >= 0);
        assertTrue("#116a: 跳过药水奖励时要广告 discard_potion", discard > skipped);

        String helper = methodBody(planner(),
                "private static void discardPotionOptions(List<ActionSpec> out)");
        assertTrue("advertised == executable：用 executor 同一个判据",
                helper.contains("potion.canDiscard()"));
        assertTrue(helper.contains("ActionTypes.DISCARD_POTION"));
        assertTrue("payload.slot 必须和药水槽号一致", helper.contains("payload.addProperty(\"slot\", slot)"));
        assertFalse("奖励屏没有玩家回合，不能广告 use_potion", helper.contains("USE_POTION"));
    }

    /** The rejection must name the action that really exists now, and list the slots. */
    @Test
    public void theFullBeltRejectionNamesAnExecutableAction() throws IOException {
        String body = methodBody(executor(), "private static ActionResult takeReward(JsonObject payload)");
        String block = after(body, "ErrorCodes.POTION_SLOT_FULL", 400);
        assertTrue("rejection 要点名 discard_potion: " + block, block.contains("discard_potion"));
        assertTrue("还要列出可丢的槽位", block.contains("potionSlotsSummary()"));

        String slots = methodBody(executor(), "private static String potionSlotsSummary()");
        assertTrue("槽位清单也要用同一个 canDiscard() 判据", slots.contains("potion.canDiscard()"));
        assertTrue("槽号要跟 payload 一致", slots.contains("IndexListText.entry(slot"));
    }

    /** Discarding while the reward screen owns the display must not be blocked by a screen check. */
    @Test
    public void discardPotionCanRunWhileTheRewardScreenIsUp() throws IOException {
        String body = methodBody(executor(), "private static ActionResult potion(JsonObject payload, boolean use)");
        assertFalse("丢药水不能被 screen 判定挡住（奖励屏上就要能丢）",
                body.contains("AbstractDungeon.screen"));
        assertTrue(body.contains("potion.canDiscard()"));
    }

    // ---------------------------------------------------------------- #116b 钥匙吞奖励

    /**
     * #116b: a key reward must be claimed by the mod, not by vanilla's linked claim — the
     * {@code SAPPHIRE_KEY} branch of {@code RewardItem.claimReward()} marks {@code relicLink}
     * ({@code isDone}/{@code ignoreReward}), and in a real chest that link is the relic row next to it.
     */
    @Test
    public void aKeyRewardNoLongerFallsThroughToTheVanillaClaim() throws IOException {
        String body = methodBody(executor(), "private static ActionResult takeReward(JsonObject payload)");
        assertTrue("SAPPHIRE_KEY 要有自己的分支", body.contains("RewardItem.RewardType.SAPPHIRE_KEY"));
        assertTrue("EMERALD_KEY 同理", body.contains("RewardItem.RewardType.EMERALD_KEY"));
        int key = body.indexOf("claimKeyReward(reward)");
        int fallback = body.indexOf("reward.claimReward()");
        assertTrue("钥匙要走 claimKeyReward()", key >= 0);
        assertTrue("钥匙分支必须排在 claimReward() 兜底之前", fallback < 0 || key < fallback);
    }

    @Test
    public void claimingAKeyKeepsTheRowItIsLinkedTo() throws IOException {
        String key = methodBody(executor(), "private static void claimKeyReward(RewardItem reward)");
        assertFalse("不能再走 vanilla 的 claimReward()（它会吞掉 relicLink）",
                key.contains(".claimReward()"));
        assertTrue("钥匙要放原版的特效（它的最后一帧才置位 hasSapphireKey）",
                key.contains("ObtainKeyEffect"));
        assertTrue(key.contains("KeyColor.BLUE"));
        assertTrue(key.contains("KeyColor.GREEN"));
        assertTrue("特效失败时要直接置位，否则钥匙不算数", key.contains("Settings.hasSapphireKey = true"));
        assertTrue("只断自己这一侧", key.contains("reward.relicLink = null"));
        assertTrue("对方指回来的链接也要断", key.contains("link.relicLink == reward"));
        assertTrue("钥匙自己要被标记为已领", key.contains("reward.isDone = true"));
        assertFalse("不要再 dispose 贴图（万一再画一帧就崩）", key.contains(".dispose()"));
    }
}
