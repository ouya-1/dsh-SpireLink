package spirelink.actions;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Source-level wiring for the QA round 6 batch that lives in {@code actions/}
 * (#100 map soft lock, #101 take_reward index, #102 hand_select confirm/cancel, #103 shop potion,
 * #104 use_potion target, plus the ascension ladder).
 *
 * <p>Every fix has a pure core that is tested properly elsewhere ({@link MapExitPolicyTest},
 * {@link TakeRewardRequestTest}, {@link HandSelectPolicyTest}, {@link AscensionRequestTest},
 * {@link ActionPlannerHandSelectTest}). What a headless test cannot reach is the plumbing inside
 * {@code ActionPlanner} / {@code ActionExecutor}, because it reads {@code AbstractDungeon} (whose
 * static initialiser needs the game). Those call sites are pinned down here, the same way
 * {@code GateWiringTest} guards the round-3 fixes and {@code ActionTypesCoverageTest} guards I4.</p>
 */
public class RoundSixWiringTest {

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
        for (String next : new String[] {"\n    private static ", "\n    static ",
                "\n    public static "}) {
            int candidate = source.indexOf(next, at + signature.length());
            if (candidate > at && candidate < cut) {
                cut = candidate;
            }
        }
        return source.substring(at, cut);
    }

    private static String planner() throws IOException {
        return read("src/main/java/spirelink/actions/ActionPlanner.java");
    }

    private static String executor() throws IOException {
        return read("src/main/java/spirelink/actions/ActionExecutor.java");
    }

    // ---------------------------------------------------------------- #100a map

    @Test
    public void plannerMapNeverOffersAProceedThatCannotLand() throws IOException {
        String map = methodBody(planner(), "private static void map(List<ActionSpec> out)");
        assertTrue("地图决策必须走 MapExitPolicy（#100）", map.contains("MapExitPolicy.decide("));
        assertTrue("proceed 只在「关地图是真动作」时广告",
                map.contains("MapExitPolicy.proceedClosesMap(decision)"));
        assertTrue("死角仍要有真出路：放弃本局", map.contains("ActionTypes.ABANDON_MID_RUN"));
        assertFalse("老的「没有可达节点也硬给 proceed」注释/行为不能再回来",
                map.contains("ui=map 仍给 proceed 出口"));
    }

    @Test
    public void executorHandlesTheMapBeforeAnyRoomBranch() throws IOException {
        String proceed = methodBody(executor(), "private static ActionResult proceed()");
        int mapBranch = proceed.indexOf("return mapProceed()");
        int roomBranch = proceed.indexOf("com.megacrit.cardcrawl.rooms.RestRoom");
        assertTrue("proceed 要在地图屏上走 mapProceed()", mapBranch >= 0);
        assertTrue("地图分支必须排在房间分支之前 —— 顺序就是 #100 软锁的根因",
                roomBranch < 0 || mapBranch < roomBranch);

        String helper = methodBody(executor(), "private static ActionResult mapProceed()");
        assertTrue(helper.contains("MapExitPolicy.decide("));
        assertTrue(helper.contains("MapExitPolicy.proceedClosesMap(decision)"));
        int guard = helper.indexOf("MapExitPolicy.proceedClosesMap(decision)");
        int close = helper.indexOf("dungeonMapScreen.close()");
        assertTrue("拒绝必须发生在关地图之前（被拒 = 零副作用）", close < 0 || guard < close);
    }

    // ---------------------------------------------------------------- #101 take_reward

    @Test
    public void takeRewardRefusesBadIndexesInsteadOfPickingTheFirstReward() throws IOException {
        String source = executor();
        // The helper is gone from the file, not merely unused: a sleeping fallback is how #101 came
        // back last time. (Comments may still name it while explaining the history.)
        assertFalse("静默改用第一条奖励的兜底必须彻底消失（#101）",
                source.contains("private static int firstTakeableReward("));
        String take = methodBody(source, "private static ActionResult takeReward(");
        assertTrue(take.contains("TakeRewardRequest.parse("));
        assertTrue("越界要 BAD_REQUEST", take.contains("ErrorCodes.BAD_REQUEST"));
        assertTrue("越界要报出合法范围", take.contains("TakeRewardRequest.rangeText("));
        assertTrue("拒绝里要列出能领的奖励", take.contains("takeableRewardSummary(screen)"));
    }

    // ---------------------------------------------------------------- #102 hand_select

    @Test
    public void handSelectExecutorAcceptsOnlyWhatThePlannerAdvertises() throws IOException {
        String source = executor();
        String confirm = methodBody(source, "private static ActionResult confirmHandSelect()");
        String cancel = methodBody(source, "private static ActionResult cancelHandSelect()");
        String choose = methodBody(source, "private static ActionResult chooseHandCards(");

        assertTrue("confirm 要用同一个纯函数判定（#102）", confirm.contains("HandSelectPolicy.canConfirm("));
        assertTrue("cancel 要用同一个纯函数判定（#102）", cancel.contains("HandSelectPolicy.canCancel("));
        assertTrue("choose 要先整体校验再动屏幕", choose.contains("HandSelectPolicy.canSelect("));

        assertTrue("confirm 的拒绝要排在点按钮之前",
                confirm.indexOf("HandSelectPolicy.canConfirm(")
                        < confirm.indexOf("screen.button.hb.clicked"));
        assertTrue("cancel 的拒绝要排在点按钮之前",
                cancel.indexOf("HandSelectPolicy.canCancel(")
                        < cancel.indexOf("screen.button.hb.clicked"));
        int validate = choose.indexOf("HandSelectPolicy.canSelect(");
        int click = choose.indexOf("selectHandCard(screen, card)");
        assertTrue("整单校验必须发生在真正选牌之前（被拒 = 零副作用）", click < 0 || validate < click);
    }

    // ---------------------------------------------------------------- #103 shop potion

    @Test
    public void shopStopsAdvertisingPotionsWithAFullBelt() throws IOException {
        String shop = methodBody(planner(), "private static void shop(List<ActionSpec> out)");
        int belt = shop.indexOf("potionSlotsFull()");
        int skip = shop.indexOf("if (beltFull)");
        assertTrue("药水槽要参与广告条件（#103）", belt >= 0);
        assertTrue("槽满时要真的跳过这瓶", skip > belt);
        int potions = shop.indexOf("ShopAccess.potions(screen)");
        assertTrue("判定要在药水循环之前算好", potions < 0 || belt < potions);
    }

    // ---------------------------------------------------------------- #104 use_potion target

    @Test
    public void targetRequiredPotionsAskForATargetInsteadOfHittingTheFirstMonster() throws IOException {
        String potion = methodBody(executor(),
                "private static ActionResult potion(JsonObject payload, boolean use)");
        int required = potion.indexOf("ErrorCodes.TARGET_REQUIRED");
        int fallback = potion.indexOf("firstAliveMonster()");
        assertTrue("缺 target_index 要 TARGET_REQUIRED（#104）", required >= 0);
        assertTrue("TARGET_REQUIRED 必须排在「打第一只活怪」之前",
                fallback < 0 || required < fallback);
        assertTrue("拒绝里要列出可选目标", potion.contains("ActionPlanner.targetChoices()"));
    }

    // ---------------------------------------------------------------- ascension ladder

    @Test
    public void ascensionLadderHasExactlyOneSource() throws IOException {
        String charSelect = methodBody(planner(),
                "static void charSelectActions(");
        assertTrue("planner 的梯子来自 AscensionRequest.LADDER",
                charSelect.contains("AscensionRequest.LADDER"));
        assertFalse("不能再内联一份梯子", charSelect.contains("new int[]{0, 1, 5, 10, 15, 20}"));

        String ascension = methodBody(executor(), "private static ActionResult chooseAscension(");
        assertTrue("执行侧必须走同一个解析器（梯子外的等级会被拒）",
                ascension.contains("AscensionRequest.parse(payload)"));
    }
}
