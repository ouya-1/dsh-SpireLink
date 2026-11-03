package spirelink.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Issue #115, state half: a read-only peek must not change {@code meta.ui}.
 *
 * <p>{@code DeckPeek} opens one of four vanilla view screens and holds it for
 * {@code DeckPeek.HOLD_MS}. {@code UiNames} maps all four to {@code ui=tutorial_tip} (#50), so
 * without the passthrough in {@code StateBuilder#deriveUi} the whole run looks like an FTUE tip: the
 * agent's {@code legal_actions} shrinks to {@code [dismiss_tip]} while {@code play_card} /
 * {@code end_turn} — and in the shop {@code buy} / {@code purge_card} — are still executable.</p>
 *
 * <p>The passthrough used real to only cover {@code MASTER_DECK_VIEW}, so the three pile peeks
 * ({@code include:["discard_pile"] / ["exhaust_pile"] / ["draw_pile"]}) fell through every time.
 * The screen list is a pure string check ({@link StateBuilder#isPeekViewScreen(String)}) so it can be
 * pinned here without a game; the wiring itself is pinned at source level, the same technique
 * {@code TransformUiWiringTest} uses.</p>
 */
public class PeekUiTest {

    /** The four views {@code DeckPeek} can open (deck / discard / exhaust / draw). */
    private static final String[] PEEK_VIEWS = {
            "MASTER_DECK_VIEW",
            "DISCARD_VIEW",
            "EXHAUST_VIEW",
            "GAME_DECK_VIEW",
    };

    @Test
    public void everyViewThePeekOpensCountsAsAPeekView() {
        for (String view : PEEK_VIEWS) {
            assertTrue(view, StateBuilder.isPeekViewScreen(view));
        }
    }

    @Test
    public void nothingElseCountsAsAPeekView() {
        // null/空串必须安全（屏幕字段可能还没赋值）。
        assertFalse(StateBuilder.isPeekViewScreen(null));
        assertFalse(StateBuilder.isPeekViewScreen(""));
        // 真正的决策点 / 别的屏幕一律不算：peek 直通只给 DeckPeek 自己开的那四个视图。
        for (String other : new String[] {"NONE", "SHOP", "MAP", "FTUE", "GRID", "HAND_SELECT",
                "CARD_REWARD", "COMBAT_REWARD", "MASTER_DECK_VIEWS", "master_deck_view"}) {
            assertFalse(other, StateBuilder.isPeekViewScreen(other));
        }
    }

    /**
     * 这四个视图在 {@code UiNames} 里就是 {@code tutorial_tip}（#50 的根），
     * 所以 peek 期间必须由 deriveUi 把「底下那个 ui」报出来 —— 这条断言把两张表绑在一起。
     */
    @Test
    public void thePeekViewsAreExactlyTheScreensUiNamesCallsTutorialTip() {
        for (String view : PEEK_VIEWS) {
            assertEquals(view + " 应该仍然映射到 tutorial_tip（#50）",
                    "tutorial_tip", UiNames.ofScreen(view));
            assertTrue(view + " 必须被 peek 直通覆盖，否则 ui 会退化成 tutorial_tip",
                    StateBuilder.isPeekViewScreen(view));
        }
    }

    /** {@code deriveUi} 的 peek 分支必须用这张表，而不是只认 {@code MASTER_DECK_VIEW}。 */
    @Test
    public void deriveUiRoutesEveryPeekViewThroughTheSameTable() throws IOException {
        String body = deriveUiBody(read("src/main/java/spirelink/state/StateBuilder.java"));
        assertTrue("deriveUi 的 peek 分支必须调用 isPeekViewScreen(): " + body,
                body.contains("isPeekViewScreen("));
        assertTrue("peek 期间必须报 DeckPeek 记下的那个 ui: " + body,
                body.contains("DeckPeek.underlyingUi()"));
        assertFalse("#115：peek 直通不能再只认 MASTER_DECK_VIEW: " + body,
                body.contains("CurrentScreen.MASTER_DECK_VIEW"));
    }

    /** {@code deriveUi(boolean)} 的方法体，切到下一个声明为止。 */
    private static String deriveUiBody(String source) {
        int at = source.indexOf("private String deriveUi(boolean logUnmapped) {");
        if (at < 0) {
            fail("找不到 deriveUi(boolean)（issue #115 的 peek 直通就在里面）");
        }
        int end = source.indexOf("\n    private static String mapScreen(", at);
        if (end < 0) {
            end = source.indexOf("\n    static boolean isPeekViewScreen(", at);
        }
        if (end < 0) {
            end = source.length();
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
