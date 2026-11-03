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
 * Source-level wiring for the round-7 actions batch (C7-01 / C7-03 / C7-04 / C7-05 / C7-07).
 *
 * <p>Each rule has a pure core tested properly elsewhere
 * ({@code CardRewardPolicyTest}, {@code GridConfirmPolicyTest}, {@code ActionLegalityTest}). What a
 * headless test cannot reach is the plumbing inside {@code ActionExecutor} / {@code ActionPlanner},
 * because it reads {@code AbstractDungeon} (whose static initialiser needs the game). Those call
 * sites are pinned down here, the same way {@code GateWiringTest} does it: the bug these issues
 * report is exactly a call site drifting away from its predicate.</p>
 */
public class RoundSevenActionsWiringTest {

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

    private static String executor() throws IOException {
        return read("src/main/java/spirelink/actions/ActionExecutor.java");
    }

    private static String planner() throws IOException {
        return read("src/main/java/spirelink/actions/ActionPlanner.java");
    }

    // ---------------------------------------------------------------- C7-01 skip_card

    /** Advertised and executed from one predicate: the planner's own skip-button probe. */
    @Test
    public void skipCardIsAdvertisedAndExecutedFromOnePredicate() throws IOException {
        String cardReward = methodBody(planner(), "private static void cardReward(List<ActionSpec> out)");
        assertTrue("planner 要用 CardRewardPolicy 决定是否广告 skip_card（C7-01）",
                cardReward.contains("CardRewardPolicy.skipAvailable("));
        assertTrue(cardReward.contains("skipButtonVisible()"));

        String skip = methodBody(executor(), "private static ActionResult skipCard()");
        assertTrue("executor 要用同一个判定（C7-01）",
                skip.contains("CardRewardPolicy.skipAvailable("));
        assertTrue("executor 要复用 planner 的按钮探针，别自己再写一份",
                skip.contains("ActionPlanner.skipButtonVisible()"));
        assertTrue("不能跳过时要给 ILLEGAL_ACTION（不是 NOT_READY）",
                skip.contains("CardRewardPolicy.notSkippableMessage()"));
    }

    /** The native path: skippedCards() records the metrics line, closeCurrentScreen() is the skip. */
    @Test
    public void skipCardRunsTheNativePathAndDropsTheFakeFallback() throws IOException {
        String skip = methodBody(executor(), "private static ActionResult skipCard()");
        assertTrue("要调 skippedCards()（原版的 SKIP 记录）", skip.contains("skippedCards()"));
        assertTrue("真正的跳过是 closeCurrentScreen()（SkipCardButton.update 就是这么做的）",
                skip.contains("AbstractDungeon.closeCurrentScreen()"));
        int metrics = skip.indexOf("skippedCards()");
        int close = skip.indexOf("AbstractDungeon.closeCurrentScreen()");
        assertTrue("先记录再关屏，和按钮顺序一致", metrics >= 0 && close > metrics);
        assertFalse("不能再打「skippedCards 无效」的 WARN", skip.contains("skippedCards 无效"));
        assertFalse("不能再有「兜底 takeReward」这条救援链", skip.contains("兜底 takeReward"));
        int settle = skip.indexOf("settleRewardItemAfterSkip(");
        assertTrue("奖励项结算要排在原生关屏之后（而不是拿来救场）", settle > close);
    }

    /** Settlement reuses the screen's own takeReward(), exactly like a successful pick does. */
    @Test
    public void theSkippedRewardIsSettledWithVanillasOwnTakeReward() throws IOException {
        String settle = methodBody(executor(), "private static void settleRewardItemAfterSkip(");
        assertTrue("要用选牌界面自己的 takeReward()（take_card 也是这么结算的）",
                settle.contains("getDeclaredMethod(\"takeReward\")"));
        assertFalse("绝不能再写 rItem.isDone：奖励项还在列表里时 rewardViewUpdate 会把它重新打开",
                settle.contains("isDone = true"));
    }

    // ---------------------------------------------------------------- C7-03 confirm_grid

    @Test
    public void gridConfirmRuleIsSharedByPlannerAndExecutor() throws IOException {
        String grid = methodBody(planner(), "private static void grid(List<ActionSpec> out)");
        assertTrue("planner 的 confirm 广告条件要走 GridConfirmPolicy（C7-03）",
                grid.contains("GridConfirmPolicy.confirmAvailable("));
        assertTrue("按钮可用的探针要是同一份", grid.contains("gridConfirmAvailable(screen)"));

        String confirm = methodBody(executor(), "private static ActionResult gridConfirm(boolean confirm)");
        assertTrue("executor 的确认判据也要走同一个纯函数（C7-03）",
                confirm.contains("GridConfirmPolicy.confirmAvailable(")
                        || confirm.contains("gridConfirmAvailable(screen, wanted, selected, justForConfirming)"));
        assertTrue("未广告的 confirm 要给 ILLEGAL_ACTION",
                confirm.contains("GridConfirmPolicy.nothingToConfirmMessage()"));
        assertTrue(confirm.contains("ErrorCodes.ILLEGAL_ACTION"));
        assertFalse("不能再回 NOT_READY「还没选牌」", confirm.contains("还没选牌，先 choose_grid_cards"));
    }

    // ---------------------------------------------------------------- C7-04 take_card range

    @Test
    public void takeCardOutOfRangeNamesTheRangeAndTheCards() throws IOException {
        String take = methodBody(executor(), "private static ActionResult takeCard(JsonObject payload)");
        assertTrue("越界要给出合法范围（和 take_reward 一致）",
                take.contains("CardRewardPolicy.rangeText("));
        assertTrue("越界要列出可拿的牌", take.contains("cardRewardSummary("));
        assertFalse("不能再只回一句「牌序号越界」", take.contains("\"牌序号越界: \""));
    }

    // ---------------------------------------------------------------- C7-07 shop INFO

    @Test
    public void shopBeltFullLinePrintsOncePerState() throws IOException {
        String shop = methodBody(planner(), "private static void shop(List<ActionSpec> out)");
        assertTrue("药水槽满的 INFO 要走 planInfo 去重（C7-07）",
                shop.contains("planInfo(\"shop.beltFull\""));
        assertFalse("不能再每瓶打一条 Diag.info",
                shop.contains("Diag.info(\"shop: 药水槽已满"));
        String planInfo = methodBody(planner(), "private static void planInfo(String key, String message)");
        assertTrue("去重靠 last-line 缓存", planInfo.contains("PLAN_INFO_LAST"));
        assertTrue("条件消失时要能复位，否则这条日志再也不会出现",
                planInfo.contains("PLAN_INFO_LAST.remove(key)"));
    }

    // ---------------------------------------------------------------- C7-05 param level

    /**
     * C7-05: only a <b>type/state level</b> refusal may be reported as "planner still offers X but the
     * executor refuses X". Payload problems are {@code rejectParam} and must stay out of that WARN
     * ({@code ToolCatalog} asks {@code ActionResult.paramLevel}).
     */
    @Test
    public void payloadProblemsAreMarkedParamLevel() throws IOException {
        String take = methodBody(executor(), "private static ActionResult takeCard(JsonObject payload)");
        assertTrue("take_card 越界/uuid 不在列表都属于 payload 问题", take.contains("rejectParam("));
        String reward = methodBody(executor(), "private static ActionResult takeReward(JsonObject payload)");
        assertTrue("take_reward 越界/index 不可选都属于 payload 问题", reward.contains("rejectParam("));
        String buy = methodBody(executor(), "private static ActionResult buy(JsonObject payload)");
        assertTrue("buy 的序号越界属于 payload 问题", buy.contains("rejectParam("));
        String playCard = methodBody(executor(), "private static ActionResult playCard(JsonObject payload)");
        assertTrue("play_card 的 uuid / target 属于 payload 问题", playCard.contains("rejectParam("));
        // The legality gate itself is a type-level refusal: never param level.
        String gate = methodBody(executor(), "static ActionResult gate(String type)");
        assertFalse("gate 的拒绝是类型级/状态级，不能标 paramLevel", gate.contains("rejectParam"));
    }

    // ---------------------------------------------------------------- ActionResult contract

    @Test
    public void paramLevelIsOnlySetByRejectParam() {
        assertFalse("正常成功不带 paramLevel", ActionResult.ok().paramLevel);
        assertFalse("verified 成功不带 paramLevel", ActionResult.verified().paramLevel);
        assertFalse("普通 reject 是类型级/状态级", ActionResult.reject("ILLEGAL_ACTION", "x").paramLevel);
        assertTrue("rejectParam 才是参数级",
                ActionResult.rejectParam("BAD_REQUEST", "x").paramLevel);
        assertEquals("paramLevel 不能改 code/message 语义",
                "BAD_REQUEST", ActionResult.rejectParam("BAD_REQUEST", "x").code);
    }
}
