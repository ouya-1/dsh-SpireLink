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
 * Source-level wiring for the QA round-3 batch (#91 / #92 / #93 / B2-12 / B2-14).
 *
 * <p>Each fix has a pure core that is tested properly elsewhere
 * ({@code ActionLegality.check(tailSentenceFollowsTheRejectedAction)},
 * {@code treasureRule}, {@code unknownTypeCode}, {@code ActionPlannerHandSelectTest}). What cannot
 * be reached from a headless test is the plumbing inside {@code ActionExecutor}, because it reads
 * {@code AbstractDungeon} (whose static initialiser needs the game). Those call sites are pinned
 * down here, the same way {@code ActionTypesCoverageTest} guards the I4 invariant.</p>
 */
public class GateWiringTest {

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

    // ---------------------------------------------------------------- B2-12

    @Test
    public void gateAnswersUnknownTypeBeforeTheUiMatrix() throws IOException {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        String gate = methodBody(executor, "static ActionResult gate(String type)");
        int unknownAt = gate.indexOf("ActionLegality.unknownTypeCode(type)");
        int uiAt = gate.indexOf("ActionLegality.uiOf(facts())");
        assertTrue("gate 必须先判未知类型（B2-12）", unknownAt >= 0);
        assertTrue("未知类型要在读 ui 之前判掉，否则会被矩阵吞成 ILLEGAL_ACTION",
                uiAt < 0 || unknownAt < uiAt);
        assertTrue(gate.contains("ErrorCodes.UNKNOWN_ACTION_TYPE")
                || gate.contains("ActionLegality.unknownTypeCode"));
    }

    // ---------------------------------------------------------------- #91

    @Test
    public void gatePrintsTheAdvertisedListNotTheMatrix() throws IOException {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        String gate = methodBody(executor, "static ActionResult gate(String type)");
        assertTrue("拒绝里要用 planner 当前广告的集合（#91）",
                gate.contains("advertisedTypes(ui)"));
        assertTrue("advertisedTypes 必须读 ActionPlanner.actionsFor",
                methodBody(executor, "static java.util.List<String> advertisedTypes(String ui)")
                        .contains("ActionPlanner.actionsFor(ui)"));
    }

    // ---------------------------------------------------------------- #92

    @Test
    public void gateBlocksTreasureProceedWhileTheChestIsClosed() throws IOException {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        String gate = methodBody(executor, "static ActionResult gate(String type)");
        assertTrue("gate 要请 treasureRule 裁决（#92）",
                gate.contains("ActionLegality.treasureRule(ui, type, treasureChestNeedsOpening())"));
        String probe = methodBody(executor, "static boolean treasureChestNeedsOpening()");
        assertTrue("只有 TreasureRoom 才判", probe.contains("TreasureRoom"));
        assertTrue(probe.contains("chest.isOpen"));
        // 必须和 planner 广告 open_chest 的条件逐项一致，否则会出现「广告了 proceed 又被拒」
        assertTrue("要算上 isScreenUp", probe.contains("isScreenUp"));
        assertTrue("要算上 rewardsPending", probe.contains("rewards"));
    }

    // ---------------------------------------------------------------- #93

    @Test
    public void verifyClaimRelicOnlyTrustsHasRelic() throws IOException {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        String verify = methodBody(executor, "private static ActionResult verifyClaimRelic(");
        assertFalse("leftScreen 不能当成功信号：RELIC_TIP 会把 BOSS_REWARD 换掉（#93）",
                verify.contains("owned || leftScreen"));
        assertTrue("必须以 player.hasRelic 为准", verify.contains("hasRelic(relicId)"));
        assertTrue("拿到遗物后要顺手收掉跟随弹出的教程提示",
                verify.contains("dismissFollowUpTip("));
    }

    @Test
    public void everyClaimPathDismissesTheFollowUpTip() throws IOException {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        assertTrue("takeGridRelic 之后也要收提示（instantObtain 同样会弹 RELIC_TIP）",
                methodBody(executor, "private static ActionResult takeGridRelic(")
                        .contains("dismissFollowUpTip("));
        String helper = methodBody(executor, "static void dismissFollowUpTip(String where)");
        assertTrue(helper.contains("AbstractDungeon.ftue"));
        assertTrue("只点 got-it 按钮", helper.contains("button.hb.clicked"));
        assertFalse("绝不能 closeCurrentScreen：遗物自己的网格可能就在提示下面",
                helper.contains("closeCurrentScreen"));
    }

    // ---------------------------------------------------------------- B2-14

    @Test
    public void handSelectCancelIsAdvertisedOnlyWhenItCanRun() throws IOException {
        String planner = read("src/main/java/spirelink/actions/ActionPlanner.java");
        String body = methodBody(planner, "static void handSelectActions(");
        // B2-14 + #102: the advertised set must come from the same pure function the executor
        // accepts with; the old inline "canPickZero || upTo" was the drift QA kept hitting.
        assertTrue("cancel 的广告条件要走 HandSelectPolicy（B2-14 / #102）",
                body.contains("HandSelectPolicy.canCancel("));
        assertTrue("confirm 的广告条件也要走同一个纯函数（#102）",
                body.contains("HandSelectPolicy.canConfirm("));
        assertTrue("还能选牌时用 canPickMore", body.contains("HandSelectPolicy.canPickMore("));
        assertFalse("不能再内联一份判断", body.contains("boolean canCancel = canPickZero || upTo;"));
        // 兜底仍要存在，避免 hand 为空时整个 ui 没有动作
        assertTrue("空结果兜底不能删（I4）", body.contains("out.isEmpty()"));
    }

    // ---------------------------------------------------------------- #95 locked event options

    @Test
    public void plannerSkipsLockedEventOptions() throws IOException {
        String planner = read("src/main/java/spirelink/actions/ActionPlanner.java");
        String event = methodBody(planner, "private static void event(List<ActionSpec> out)");
        assertTrue("planner 必须按 EventOptionPolicy 过滤锁定选项（#95）",
                event.contains("EventOptionPolicy.selectable("));
        assertTrue("所有选项都被锁时必须给兜底出口（I4）", event.contains("clickable == 0"));
    }

    @Test
    public void executorRefusesLockedEventOptions() throws IOException {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        String choose = methodBody(executor, "private static ActionResult chooseOption(");
        assertTrue("执行侧要有第二道防线（#95）",
                choose.contains("EventOptionPolicy.selectable("));
        assertTrue(choose.contains("EventOptionPolicy.lockedMessage("));
        // 必须在真正调用 buttonEffect 之前拦掉，否则改档已经发生了
        int guard = choose.indexOf("EventOptionPolicy.selectable(");
        int effect = choose.indexOf("invokeButtonEffect(");
        assertTrue("守卫要在 buttonEffect 之前", effect < 0 || guard < effect);
    }

    // ---------------------------------------------------------------- #96 confirm-only grids

    @Test
    public void plannerOnlyAdvertisesCancelWhenTheGridHasOne() throws IOException {
        String planner = read("src/main/java/spirelink/actions/ActionPlanner.java");
        String grid = methodBody(planner, "private static void grid(List<ActionSpec> out)");
        assertTrue("广告条件必须走 GridCancelPolicy（#96）",
                grid.contains("GridCancelPolicy.cancelAllowed(canCancel, anySelectable, confirmOffered)"));
        assertFalse("不能再无条件塞 cancel：那正是变形网格被结算的根因",
                grid.contains("取消（退出选牌）"));
        assertFalse("保命 cancel 也不能无条件给", grid.contains("取消（保命）"));
        assertTrue("canCancel 只能从格子里读", grid.contains("gridCanCancel(screen)"));
    }

    @Test
    public void executorRefusesCancelOnAConfirmOnlyGrid() throws IOException {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        String confirm = methodBody(executor, "private static ActionResult gridConfirm(boolean confirm)");
        assertTrue("执行侧要拒绝确认式网格的 cancel（#96）",
                confirm.contains("GridCancelPolicy.cancelAllowed("));
        assertTrue(confirm.contains("GridCancelPolicy.confirmOnlyMessage()"));
        // 守卫必须在清空 selectedCards 之前，取消被拒时不能留下副作用
        int guard = confirm.indexOf("GridCancelPolicy.cancelAllowed(");
        int clear = confirm.indexOf("screen.selectedCards.clear()");
        assertTrue("拒绝必须发生在清空选择之前（被拒 = 零副作用）", clear < 0 || guard < clear);
    }

    // ---------------------------------------------------------------- #98 deck peek

    @Test
    public void aDeckPeekIsClosedBeforeTheLegalityGate() throws IOException {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        String execute = methodBody(executor, "public static ActionResult execute(String type, JsonObject payload)");
        int close = execute.indexOf("DeckPeek.closeForced(");
        int gate = execute.indexOf("gate(type)");
        assertTrue("act 必须先关掉 peek 再判合法性（#98 的推荐修法）", close >= 0);
        assertTrue("关 peek 必须发生在 gate 之前，否则又会拿 tutorial_tip 去拒 grid 的动作",
                gate < 0 || close < gate);
        assertTrue("dismiss_tip 要豁免（它就是用来关屏幕的）",
                execute.contains("ActionTypes.DISMISS_TIP.equals(type)"));
    }

    @Test
    public void dismissTipClosesThePeekAndNotTheScreenUnderneath() throws IOException {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        String dismiss = methodBody(executor, "private static ActionResult dismissTip()");
        int peek = dismiss.indexOf("DeckPeek.active()");
        assertTrue("dismiss_tip 要认 peek", peek >= 0);
        assertTrue(dismiss.contains("DeckPeek.closeForced("));
        int generic = dismiss.indexOf("clickGotIt(");
        assertTrue("peek 分支必须排在通用路径之前，否则会顺带关掉下面的网格",
                generic < 0 || peek < generic);
    }

    // ---------------------------------------------------------------- purge grid flags

    @Test
    public void shopPurgeGridUsesVanillasCancelFlags() throws IOException {
        String executor = read("src/main/java/spirelink/actions/ActionExecutor.java");
        String purge = methodBody(executor, "private static ActionResult purgeCard(");
        assertTrue("净化网格必须照抄 vanilla（forUpgrade=false, forTransform=false, canCancel=true, forPurge=true）: "
                + "javap ShopScreen -> iconst_0 iconst_0 iconst_1 iconst_1",
                purge.contains("false, false, true, true"));
        assertFalse("不能再把 forTransform 误传成 true / canCancel 误传成 false",
                purge.contains("false, true, false, true"));
    }
}