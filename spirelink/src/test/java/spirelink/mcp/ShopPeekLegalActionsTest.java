package spirelink.mcp;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #115, actions half: the shop advertisement must survive a read-only peek.
 *
 * <p>现场（QA 第 9 轮 / build17）：{@code ui=shop} 上 {@code get_state{include:["deck"]}} 打开牌表
 * peek（{@code screen=MASTER_DECK_VIEW}）后，{@code legal_actions} 退化成 shop_room 的
 * {@code [open_shop, leave]}；硬发 {@code open_shop} 得到 {@code ILLEGAL_ACTION}「商店已经打开了」，
 * 而 {@code buy} 真能买成却没被广告。</p>
 *
 * <p>根因在 planner 的那行判据：{@code shop()} 原来要求
 * {@code AbstractDungeon.screen == SHOP}，而执行侧只问 {@code ShopScreen.isActive}
 * （{@code buy} / {@code purge_card}），并且 {@code ActionExecutor#execute} 在过 legality gate 之前
 * 就会 {@code DeckPeek.closeForced()}，所以 {@code open_shop} 到 gate 时屏幕已经回到 SHOP →
 * 「商店已经打开了」。一广告一执行，两边判据不同源。</p>
 *
 * <p>{@code ActionPlanner} 需要 {@code AbstractDungeon}，无游戏的单测里跑不起来，所以这条不变量
 * 用「读源码 + 断言判据同源」钉住（同 {@code TransformUiWiringTest}）：peek 期间「商人还在屏上」
 * 必须由 {@code merchantVisible()} 回答，且它问的是 {@code DeckPeek} 记下的那个 ui。</p>
 */
public class ShopPeekLegalActionsTest {

    private static final String PLANNER = "src/main/java/spirelink/actions/ActionPlanner.java";

    @Test
    public void theShopAdvertisementAsksMerchantVisibleInsteadOfTheLiveScreen() throws IOException {
        String shop = methodBody(planner(), "private static void shop(List<ActionSpec> out) {",
                "private static boolean merchantVisible() {");
        assertTrue("#115：shop() 的 onScreen 判据必须走 merchantVisible(): " + shop,
                shop.contains("merchantVisible()"));
        assertFalse("#115：shop() 里不许再直接拿 AbstractDungeon.screen == SHOP 当判据: " + shop,
                shop.contains("CurrentScreen.SHOP"));
    }

    @Test
    public void merchantVisibleUnderstandsAReadOnlyPeek() throws IOException {
        String helper = methodBody(planner(), "private static boolean merchantVisible() {",
                "/** Colored + colorless shop cards");
        // 真是 SHOP 屏 → 在屏上（原有语义，一行都不改）。
        assertTrue("merchantVisible 必须保留 screen==SHOP 这个基本情形: " + helper,
                helper.contains("AbstractDungeon.CurrentScreen.SHOP"));
        // peek 期间 → 商人仍然是 agent 正在决策的那个屏（与 StateBuilder#deriveUi 同口径）。
        assertTrue("merchantVisible 必须问 DeckPeek 是否正开着: " + helper,
                helper.contains("DeckPeek.active()"));
        assertTrue("merchantVisible 必须比对 peek 打开时的 ui 是 shop: " + helper,
                helper.contains("DeckPeek.underlyingUi()") && helper.contains("\"shop\""));
    }

    /**
     * 两份判据必须指着同一件事：state 报的 ui 与 planner 的「商人在屏上」都只认
     * {@code DeckPeek.underlyingUi()}，否则 #115 会以另一种形式回来。
     */
    @Test
    public void stateAndPlannerAgreeOnWhatThePeekCovers() throws IOException {
        String state = read("src/main/java/spirelink/state/StateBuilder.java");
        assertTrue("StateBuilder#deriveUi 必须仍然把 peek 的 ui 报成 underlyingUi()",
                state.contains("DeckPeek.underlyingUi()"));
        String helper = methodBody(planner(), "private static boolean merchantVisible() {",
                "/** Colored + colorless shop cards");
        assertTrue("planner 与 state 必须同源（都问 DeckPeek.underlyingUi()）: " + helper,
                helper.contains("DeckPeek.underlyingUi()"));
    }

    private static String planner() throws IOException {
        return read(PLANNER);
    }

    /** {@code source} 里从 {@code signature} 起、到 {@code endMarker} 之前的那一段。 */
    private static String methodBody(String source, String signature, String endMarker) {
        int at = source.indexOf(signature);
        if (at < 0) {
            fail("找不到: " + signature);
        }
        int end = source.indexOf(endMarker, at + signature.length());
        if (end < 0) {
            fail("找不到结束标记: " + endMarker);
        }
        return source.substring(at, end);
    }

    /** Reads a repo file relative to the maven module directory (same trick as the other wiring tests). */
    private static String read(String relative) throws IOException {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
            dir = dir.getParentFile();
        }
        fail("找不到 " + relative + "（user.dir=" + System.getProperty("user.dir") + "）");
        return null;
    }
}
