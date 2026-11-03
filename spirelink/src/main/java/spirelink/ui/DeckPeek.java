package spirelink.ui;

import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

/**
 * Short read-only "look" at a card list, so a human watching the screen can see what the agent is
 * reading: the master deck, the discard pile, the exhaust pile or the draw pile.
 *
 * <p>A look is two beats, like a person doing it: the pointer glides to the pile button first, and
 * only when it gets there does the list open. The list closes itself after {@link #HOLD_MS} and any
 * real action closes it first. While it is open {@code ui} keeps reporting the screen it was opened
 * from, so {@code legal_actions} still lists the actions of that screen.</p>
 */
public final class DeckPeek {

    /** How long a list stays on screen before it closes itself. */
    public static final long HOLD_MS = 4000L;

    public static final String DECK = "deck";
    public static final String DISCARD = "discard";
    public static final String EXHAUST = "exhaust";
    public static final String DRAW = "draw";

    private static volatile boolean active;
    private static volatile String openKind = DECK;
    private static volatile long closeAt;
    private static volatile String fromUi = "";
    /** Screen that was active before the look, restored when it closes. */
    private static volatile AbstractDungeon.CurrentScreen savedScreen;
    private static volatile boolean savedScreenUp;
    private static volatile boolean requestPending;
    private static volatile String requestUi = "";
    private static volatile String requestKind = DECK;
    private static volatile long notBefore;
    /** Closing is its own beat: the pointer walks to the back button, then the list goes away. */
    private static volatile boolean closing;
    private static volatile long closeAfterMs;
    /** A card shown in the big popup (upgrade preview) closes itself at this time. */
    private static volatile long popupCloseAt;

    private DeckPeek() {
    }

    /** Thread safe: the MCP worker asks, the render thread opens once the pointer has arrived. */
    public static void request(String ui) {
        request(ui, DECK);
    }

    /**
     * @param kind one of {@link #DECK}, {@link #DISCARD}, {@link #EXHAUST}, {@link #DRAW}; the
     *             pointer is sent to that pile's own button first
     */
    public static void request(String ui, String kind) {
        if (ui == null || ui.isEmpty()) {
            return;
        }
        requestUi = ui;
        requestKind = kind == null ? DECK : kind;
        requestPending = true;
        long eta = 0L;
        try {
            float[] at = button(requestKind);
            if (at != null) {
                eta = AgentCursor.flyToEta(at[0], at[1]);
            }
        } catch (Throwable ignored) {
            // the pointer is decoration; the look still happens
        }
        notBefore = System.currentTimeMillis() + Math.min(eta + 60L, 700L);
    }

    public static boolean active() {
        return active;
    }

    /** The ui the look was opened from; {@code null} when none is running. */
    public static String underlyingUi() {
        return active ? fromUi : null;
    }

    /** Main thread, once per frame: opens a pending look, closes an expired one. */
    public static void tick() {
        long now = System.currentTimeMillis();
        if (requestPending && now >= notBefore) {
            requestPending = false;
            open(requestUi, requestKind);
        }
        if (active && !closing && now > closeAt) {
            beginClose("auto-close after " + (HOLD_MS / 1000) + "s");
        }
        if (closing && now >= closeAfterMs) {
            finishClose();
        }
        if (popupCloseAt > 0L && now >= popupCloseAt) {
            popupCloseAt = 0L;
            try {
                com.megacrit.cardcrawl.core.CardCrawlGame.cardPopup.close();
                com.megacrit.cardcrawl.core.CardCrawlGame.isPopupOpen = false;
                spirelink.util.Diag.info("card_popup: close");
            } catch (Throwable t) {
                spirelink.util.Diag.warn("card_popup: 关闭失败: " + t);
            }
        }
    }

    /**
     * Shows one card in the game's big card popup - the "what would this upgrade into?" view.
     *
     * <p>Main thread. The popup closes itself after {@code holdMs}, so it cannot get stuck on
     * screen the way a half-open screen would.</p>
     */
    public static void showCard(com.megacrit.cardcrawl.cards.AbstractCard card, long holdMs) {
        if (card == null) {
            return;
        }
        try {
            com.megacrit.cardcrawl.core.CardCrawlGame.cardPopup.open(card);
            // The popup itself only flips isOpen; the game's input layer keys off this flag.
            com.megacrit.cardcrawl.core.CardCrawlGame.isPopupOpen = true;
            popupCloseAt = System.currentTimeMillis() + Math.max(800L, holdMs);
            spirelink.util.Diag.info("card_popup: open " + card.cardID
                    + (card.upgraded ? "+" : "") + "（" + holdMs + "ms 后自动关）");
        } catch (Throwable t) {
            spirelink.util.Diag.warn("card_popup: 打开失败: " + t);
        }
    }

    /** The back button a human would click to leave the list. */
    private static float[] backButton() {
        try {
            com.megacrit.cardcrawl.ui.buttons.CancelButton button =
                    AbstractDungeon.overlayMenu == null ? null : AbstractDungeon.overlayMenu.cancelButton;
            if (button != null && button.hb != null) {
                return new float[]{button.hb.cX, button.hb.cY};
            }
        } catch (Throwable ignored) {
            // fall through to the instant close
        }
        return null;
    }

    private static void beginClose(String why) {
        closing = true;
        long eta = 140L;
        try {
            float[] back = backButton();
            if (back != null) {
                eta = Math.max(140L, AgentCursor.flyToEta(back[0], back[1]));
            }
        } catch (Throwable ignored) {
            // keep the fallback delay
        }
        closeAfterMs = System.currentTimeMillis() + eta;
        try {
            AgentCursor.acted(false);
        } catch (Throwable ignored) {
            // decoration
        }
        spirelink.util.Diag.info("peek_" + openKind + ": 去点返回（" + why + "，" + eta + "ms）");
    }

    private static void finishClose() {
        closing = false;
        closeForced("closed");
    }

    /** Main thread: shows the list without changing what the agent may do. */
    public static void open(String ui, String kind) {
        try {
            if (!peekable(ui)) {
                return;
            }
            if (!active) {
                savedScreen = AbstractDungeon.screen;
                savedScreenUp = AbstractDungeon.isScreenUp;
            }
            AbstractDungeon.CurrentScreen screen = openScreen(kind);
            if (screen == null) {
                return;
            }
            AbstractDungeon.screen = screen;
            AbstractDungeon.isScreenUp = true;
            active = true;
            openKind = kind;
            fromUi = ui;
            closeAt = System.currentTimeMillis() + HOLD_MS;
            spirelink.util.Diag.info("peek_" + kind + ": open (from=" + ui + "，"
                    + (HOLD_MS / 1000) + "s 后自动关闭)");
        } catch (Throwable t) {
            spirelink.util.Diag.warn("peek_" + kind + ": 打开失败: " + t);
            active = false;
        }
    }

    /** Opens the matching vanilla view screen; null when that view is unavailable right now. */
    private static AbstractDungeon.CurrentScreen openScreen(String kind) {
        if (DISCARD.equals(kind)) {
            if (AbstractDungeon.discardPileViewScreen == null) {
                return null;
            }
            AbstractDungeon.discardPileViewScreen.open();
            return AbstractDungeon.CurrentScreen.DISCARD_VIEW;
        }
        if (EXHAUST.equals(kind)) {
            if (AbstractDungeon.exhaustPileViewScreen == null) {
                return null;
            }
            AbstractDungeon.exhaustPileViewScreen.open();
            return AbstractDungeon.CurrentScreen.EXHAUST_VIEW;
        }
        if (DRAW.equals(kind)) {
            if (AbstractDungeon.gameDeckViewScreen == null) {
                return null;
            }
            AbstractDungeon.gameDeckViewScreen.open();
            return AbstractDungeon.CurrentScreen.GAME_DECK_VIEW;
        }
        if (AbstractDungeon.deckViewScreen == null) {
            AbstractDungeon.deckViewScreen = new com.megacrit.cardcrawl.screens.MasterDeckViewScreen();
        }
        AbstractDungeon.deckViewScreen.open();
        return AbstractDungeon.CurrentScreen.MASTER_DECK_VIEW;
    }

    /** Where a human clicks to see that list: the pile buttons, or the deck button in the top bar. */
    private static float[] button(String kind) {
        if (DISCARD.equals(kind)) {
            return panel(AbstractDungeon.overlayMenu == null ? null
                    : AbstractDungeon.overlayMenu.discardPilePanel);
        }
        if (EXHAUST.equals(kind)) {
            return panel(AbstractDungeon.overlayMenu == null ? null
                    : AbstractDungeon.overlayMenu.exhaustPanel);
        }
        if (DRAW.equals(kind)) {
            return panel(AbstractDungeon.overlayMenu == null ? null
                    : AbstractDungeon.overlayMenu.combatDeckPanel);
        }
        if (AbstractDungeon.topPanel != null && AbstractDungeon.topPanel.deckHb != null) {
            return new float[]{AbstractDungeon.topPanel.deckHb.cX,
                    AbstractDungeon.topPanel.deckHb.cY};
        }
        return null;
    }

    private static float[] panel(Object candidate) {
        if (!(candidate instanceof com.megacrit.cardcrawl.ui.panels.AbstractPanel)) {
            return null;
        }
        com.megacrit.cardcrawl.ui.panels.AbstractPanel p =
                (com.megacrit.cardcrawl.ui.panels.AbstractPanel) candidate;
        // show_x/show_y, not current_x/current_y: while a view screen is up the combat panels are
        // hidden and their current position is off-screen, but the pile button is still "there".
        // Lift off the very bottom edge (the panels sit at y≈8) so the fish is not half clipped.
        float lift = 46f * (com.megacrit.cardcrawl.core.Settings.HEIGHT / 1080f);
        return new float[]{p.show_x, p.show_y + lift};
    }

    /** Main thread: closes the look now (before an action runs). */
    public static void closeForced(String why) {
        if (!active) {
            return;
        }
        active = false;
        closing = false;
        try {
            if (isView(AbstractDungeon.screen)) {
                AbstractDungeon.screen = savedScreen == null
                        ? AbstractDungeon.CurrentScreen.NONE : savedScreen;
                AbstractDungeon.isScreenUp = savedScreenUp;
            }
            spirelink.util.Diag.info("peek_" + openKind + ": close (" + why + "，screen→"
                    + AbstractDungeon.screen + ")");
        } catch (Throwable t) {
            spirelink.util.Diag.warn("peek_" + openKind + ": 关闭失败: " + t);
        }
    }

    private static boolean isView(AbstractDungeon.CurrentScreen screen) {
        return screen == AbstractDungeon.CurrentScreen.MASTER_DECK_VIEW
                || screen == AbstractDungeon.CurrentScreen.DISCARD_VIEW
                || screen == AbstractDungeon.CurrentScreen.EXHAUST_VIEW
                || screen == AbstractDungeon.CurrentScreen.GAME_DECK_VIEW;
    }

    /** Only while a real decision is on screen - never on the map or during animations. */
    private static boolean peekable(String ui) {
        if (!"card_reward".equals(ui) && !"grid".equals(ui) && !"combat".equals(ui)
                && !"shop".equals(ui) && !"hand_select".equals(ui)) {
            return false;
        }
        return AbstractDungeon.player != null && AbstractDungeon.player.masterDeck != null;
    }
}
