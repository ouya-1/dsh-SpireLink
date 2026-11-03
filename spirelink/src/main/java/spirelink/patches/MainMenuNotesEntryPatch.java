package spirelink.patches;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import spirelink.SpireLink;
import spirelink.ui.AgentHud;
import spirelink.ui.AgentHudRenderer;
import spirelink.ui.NoteHud;
import spirelink.ui.NotesPanel;
import spirelink.util.Diag;
import spirelink.util.Notes;

import java.util.ArrayList;
import java.util.List;

/**
 * The main-menu "便签（55）" entry and its read-only notes panel (issue #46).
 *
 * <p>Two thin hooks, all the thinking is in {@link NotesPanel}:</p>
 * <ul>
 *   <li>{@code MainMenuScreen.render} postfix draws the entry (bottom-left corner of the title
 *       screen) and, when open, the dimmer plus the centred panel;</li>
 *   <li>{@code MainMenuScreen.update} <b>prefix</b> does the hit-testing and navigation. It has to be
 *       a prefix: the game's own {@code update()} would otherwise still see
 *       {@code InputHelper.justClickedLeft} and a click "through" the panel would press
 *       「单机游戏」. Clearing the flags before the original body runs makes the panel swallow the
 *       click, and the entry itself stays clickable afterwards.</li>
 * </ul>
 *
 * <p><b>Read-only, plus one confirmed reset.</b> The data source is {@code Notes.list(null)};
 * nothing here writes, edits or deletes a single note, and the panel has no text field or hotkey
 * that could. Opening, paging and closing never touch {@code notes.json}.</p>
 *
 * <p>The one exception is the bottom-right "清空全部" button (issue #47): it is the <b>only</b>
 * human trigger for {@link Notes#clearAll()}, and it needs two clicks three seconds apart - the
 * first arms it (red, "再点一次确认清空，3 秒后取消"), the second one really wipes the store. No MCP
 * tool maps onto that reset, so an agent can never reach it; {@code note_set} still deletes exactly
 * one key.</p>
 *
 * <p>Layout is expressed in 720p design units and scaled by {@code Settings.HEIGHT / 720}: the entry
 * sits in the empty strip to the right of the version/ModTheSpire lines, clear of every main-menu
 * button hitbox at 720p, 1080p and 1440p.</p>
 */
@SpirePatch(clz = MainMenuScreen.class, method = "render")
public class MainMenuNotesEntryPatch {

    // ---- layout, in 720p design units ----
    /** Left edge of the entry. Keep it right of the version / "ModTheSpire x.y.z" overlay text. */
    private static final float ENTRY_X = 300f;
    /** Top edge of the entry text (measured up from the bottom of the screen). */
    private static final float ENTRY_TOP = 50f;
    private static final float ENTRY_PAD = 8f;
    private static final float ENTRY_SCALE = 0.9f;
    private static final float PANEL_PAD = 22f;
    private static final float PANEL_W = 1060f;
    /**
     * 448 in issue #46. The extra 24 units are the footer row the human-only "清空全部" button
     * needs (issue #47): at 448 the button plate would sit on the last note row of a full page.
     */
    private static final float PANEL_H = 472f;
    private static final float TITLE_SCALE = 1.15f;
    private static final float ROW_SCALE = 0.9f;
    private static final float ROW_ADVANCE = 27f;
    private static final float HINT_SCALE = 0.78f;
    private static final float CLEAR_SCALE = 0.82f;
    private static final float CLEAR_PAD_X = 12f;
    private static final float CLEAR_PAD_Y = 6f;

    /** The entry count is refreshed at most this often while the panel is closed. */
    private static final long COUNT_TTL_MS = 1000L;

    private static final Color ENTRY_TEXT = new Color(1.00f, 0.93f, 0.74f, 1f);
    private static final Color ENTRY_PLATE = new Color(0f, 0f, 0f, 0.42f);
    private static final Color ENTRY_PLATE_HOVER = new Color(0.10f, 0.14f, 0.20f, 0.72f);
    private static final Color ENTRY_UNDERLINE = new Color(1.00f, 0.84f, 0.35f, 0.85f);
    private static final Color OVERLAY = new Color(0f, 0f, 0f, 0.62f);
    private static final Color PLATE = new Color(0.06f, 0.07f, 0.10f, 0.93f);
    private static final Color BORDER = new Color(0.72f, 0.93f, 1.00f, 0.55f);
    private static final Color TEXT = new Color(1.00f, 0.93f, 0.74f, 1f);
    private static final Color HEADER = new Color(0.72f, 0.93f, 1.00f, 1f);
    private static final Color DIM = new Color(0.72f, 0.78f, 0.84f, 1f);
    private static final Color CLEAR_PLATE = new Color(0.26f, 0.09f, 0.09f, 0.90f);
    private static final Color CLEAR_PLATE_HOVER = new Color(0.40f, 0.13f, 0.13f, 0.95f);
    private static final Color CLEAR_TEXT = new Color(1.00f, 0.80f, 0.76f, 1f);
    private static final Color CLEAR_CONFIRM_PLATE = new Color(0.58f, 0.09f, 0.09f, 0.97f);
    private static final Color CLEAR_CONFIRM_TEXT = new Color(1.00f, 0.56f, 0.50f, 1f);

    private static final String TITLE = "便签 · 只读";
    private static final String HINT = "ESC 关闭 · 滚轮/↑↓ 逐行 · PgUp/PgDn 翻页 · Home/End 首尾 · 点空白关闭";
    private static final String EMPTY = "暂无便签：agent 还没记过东西";

    /** The one panel instance; the state machine keeps its own page window. */
    private static final NotesPanel PANEL = new NotesPanel(new NotesPanel.Formatter() {
        @Override
        public String kindLabel(String kind) {
            return NoteHud.kindZh(kind);
        }

        @Override
        public String displayName(String kind, String id) {
            return NoteHud.displayName(kind, id);
        }

        @Override
        public String body(String text) {
            return NoteHud.cleanMarkup(text);
        }
    });

    private static Texture dot;
    private static boolean dotTried;

    /** Entry hit rectangle (x, y, width, height) from the last draw, in game coordinates. */
    private static final float[] ENTRY_RECT = new float[4];
    private static boolean entryVisible;

    /** "清空全部" hit rectangle from the last draw, in game coordinates. */
    private static final float[] CLEAR_RECT = new float[4];
    private static boolean clearVisible;

    /** Screen size the rows were clipped for; a resize re-clips them. */
    private static int layoutW;
    private static int layoutH;

    /** Batch colour found on entry, handed back untouched: other patches draw after us. */
    private static final Color SAVED_COLOR = new Color();

    private static int countCache = -1;
    private static long countAt;

    /** One log line per session proving the entry really was drawn (and where). */
    private static boolean entryLogged;

    private MainMenuNotesEntryPatch() {
    }

    // ---- render ----

    @SpirePostfixPatch
    public static void Postfix(MainMenuScreen __instance, SpriteBatch sb) {
        if (!NotesPanel.isEnabled() || sb == null) {
            return;
        }
        if (Settings.WIDTH <= 0 || Settings.HEIGHT <= 0) {
            // Still booting: Settings are 0 and every rectangle below would be degenerate.
            return;
        }
        SAVED_COLOR.set(sb.getColor());
        try {
            if (!onMenu(__instance)) {
                entryVisible = false;
                clearVisible = false;
                return;
            }
            drawEntry(sb);
            if (PANEL.isOpen()) {
                drawPanel(sb);
            } else {
                // No panel, no button: a stale rectangle must never swallow a click.
                clearVisible = false;
            }
        } catch (Throwable t) {
            // a human-facing nicety must never break the menu
            Diag.warn("便签面板: 绘制失败: " + t);
        } finally {
            sb.setColor(SAVED_COLOR);
        }
    }

    /** The entry only exists on the title screen itself, not in settings / library / char select. */
    private static boolean onMenu(MainMenuScreen menu) {
        return menu != null && menu.screen == MainMenuScreen.CurScreen.MAIN_MENU;
    }

    private static void drawEntry(SpriteBatch sb) {
        BitmapFont font = FontHelper.cardDescFont_N;
        if (font == null) {
            entryVisible = false;
            return;
        }
        float u = ui();
        String label = NotesPanel.entryLabel(notesCount());
        float scaleX = font.getData().scaleX;
        float scaleY = font.getData().scaleY;
        try {
            font.getData().setScale(ENTRY_SCALE * u);
            float x = ENTRY_X * u;
            float top = ENTRY_TOP * u;
            float width = AgentHudRenderer.measure(font, label);
            float height = Math.max(1f, font.getLineHeight());
            float pad = ENTRY_PAD * u;
            ENTRY_RECT[0] = x - pad;
            ENTRY_RECT[1] = top - height - pad;
            ENTRY_RECT[2] = width + pad * 2f;
            ENTRY_RECT[3] = height + pad * 2f;
            entryVisible = true;
            if (!entryLogged) {
                entryLogged = true;
                Diag.info("便签入口: x=" + Math.round(ENTRY_RECT[0])
                        + " y=" + Math.round(ENTRY_RECT[1])
                        + " w=" + Math.round(ENTRY_RECT[2])
                        + " h=" + Math.round(ENTRY_RECT[3])
                        + "（屏幕 " + Settings.WIDTH + "x" + Settings.HEIGHT
                        + "，ui 系数 " + Math.round(u * 100f) / 100f + "）");
            }
            boolean hover = contains(ENTRY_RECT[0], ENTRY_RECT[1], ENTRY_RECT[2], ENTRY_RECT[3],
                    InputHelper.mX, InputHelper.mY);
            fill(sb, ENTRY_RECT[0], ENTRY_RECT[1], ENTRY_RECT[2], ENTRY_RECT[3],
                    hover ? ENTRY_PLATE_HOVER : ENTRY_PLATE);
            AgentHudRenderer.drawOutlined(sb, font, label, x, top, ENTRY_TEXT, hover ? 1f : 0.9f);
            if (hover) {
                fill(sb, x, top - height - pad * 0.4f, width, Math.max(1f, 2f * u), ENTRY_UNDERLINE);
            }
        } finally {
            font.getData().setScale(scaleX, scaleY);
        }
    }

    private static void drawPanel(SpriteBatch sb) {
        float u = ui();
        float x = panelX();
        float y = panelY();
        float w = panelW();
        float h = panelH();
        float pad = PANEL_PAD * u;

        // Dimmer over everything, then the plate and a thin cyan border (HUD palette).
        fill(sb, 0f, 0f, Settings.WIDTH, Settings.HEIGHT, OVERLAY);
        fill(sb, x, y, w, h, PLATE);
        float border = Math.max(1f, 2f * u);
        fill(sb, x, y, w, border, BORDER);
        fill(sb, x, y + h - border, w, border, BORDER);
        fill(sb, x, y, border, h, BORDER);
        fill(sb, x + w - border, y, border, h, BORDER);

        BitmapFont font = FontHelper.cardDescFont_N;
        if (font == null) {
            return;
        }
        float scaleX = font.getData().scaleX;
        float scaleY = font.getData().scaleY;
        try {
            // Title (left) and page counter (right) share one baseline.
            font.getData().setScale(TITLE_SCALE * u);
            float titleHeight = Math.max(1f, font.getLineHeight());
            float titleTop = y + h - pad;
            AgentHudRenderer.drawOutlined(sb, font, TITLE, x + pad, titleTop, TEXT, 1f);
            String header = PANEL.header();
            float headerWidth = AgentHudRenderer.measure(font, header);
            AgentHudRenderer.drawOutlined(sb, font, header, x + w - pad - headerWidth, titleTop,
                    HEADER, 1f);

            // Rows: [类型] 名称：正文, one note per row, newest first.
            font.getData().setScale(ROW_SCALE * u);
            float rowTop = titleTop - titleHeight - pad * 0.5f;
            int visible = PANEL.visibleCount();
            for (int i = 0; i < visible; i++) {
                String row = PANEL.row(i);
                if (row == null || row.isEmpty()) {
                    continue;
                }
                AgentHudRenderer.drawOutlined(sb, font, row, x + pad, rowTop - i * ROW_ADVANCE * u,
                        TEXT, 0.97f);
            }
            if (PANEL.count() <= 0) {
                AgentHudRenderer.drawOutlined(sb, font, EMPTY, x + pad, rowTop, DIM, 1f);
            }

            // Footer: the interaction hint on the left, the human-only reset button on the right.
            // The hint is clipped to the space the button leaves, so the two can never overlap on a
            // narrow window.
            font.getData().setScale(HINT_SCALE * u);
            drawClearButton(sb, font, x, y, w, pad, u);
            float hintRoom = (clearVisible ? CLEAR_RECT[0] - pad * 0.5f : x + w - pad) - (x + pad);
            String hint = clipToWidth(font, HINT, hintRoom);
            if (!hint.isEmpty()) {
                AgentHudRenderer.drawOutlined(sb, font, hint, x + pad, y + pad, DIM, 0.9f);
            }
        } finally {
            font.getData().setScale(scaleX, scaleY);
        }
    }

    /**
     * Bottom-right reset button. Red plate + "再点一次确认清空，3 秒后取消" while armed; the label
     * comes from {@link NotesPanel#clearLabel()}, so what is drawn and what the state machine thinks
     * can never disagree.
     */
    private static void drawClearButton(SpriteBatch sb, BitmapFont font, float x, float y,
                                        float w, float pad, float u) {
        boolean armed = PANEL.isConfirmingClear();
        String label = PANEL.clearLabel();
        float scaleX = font.getData().scaleX;
        float scaleY = font.getData().scaleY;
        // The caller has already switched the font to HINT_SCALE: this is the hint line's height.
        float hintHeight = Math.max(1f, font.getLineHeight());
        try {
            font.getData().setScale(CLEAR_SCALE * u);
            float textWidth = AgentHudRenderer.measure(font, label);
            float height = Math.max(1f, font.getLineHeight());
            float padX = CLEAR_PAD_X * u;
            float padY = CLEAR_PAD_Y * u;
            float bw = textWidth + padX * 2f;
            float bh = height + padY * 2f;
            float bx = x + w - pad - bw;
            // The button is centred on the footer hint's line, then clamped so the plate always
            // stays inside the panel whatever the font metrics turn out to be at a resolution.
            float hintCenter = y + pad - hintHeight * 0.5f;
            float by = Math.max(y + 2f * u, hintCenter - bh * 0.5f);
            float textTop = by + bh - padY;
            CLEAR_RECT[0] = bx;
            CLEAR_RECT[1] = by;
            CLEAR_RECT[2] = bw;
            CLEAR_RECT[3] = bh;
            clearVisible = true;
            boolean hover = contains(bx, by, bw, bh, InputHelper.mX, InputHelper.mY);
            Color plate = armed ? CLEAR_CONFIRM_PLATE : (hover ? CLEAR_PLATE_HOVER : CLEAR_PLATE);
            Color text = armed ? CLEAR_CONFIRM_TEXT : CLEAR_TEXT;
            Color edge = armed ? CLEAR_CONFIRM_TEXT : BORDER;
            fill(sb, bx, by, bw, bh, plate);
            float line = Math.max(1f, u);
            fill(sb, bx, by, bw, line, edge);
            fill(sb, bx, by + bh - line, bw, line, edge);
            fill(sb, bx, by, line, bh, edge);
            fill(sb, bx + bw - line, by, line, bh, edge);
            AgentHudRenderer.drawOutlined(sb, font, label, bx + padX, textTop, text, 1f);
        } finally {
            font.getData().setScale(scaleX, scaleY);
        }
    }

    /**
     * Longest prefix of {@code text} still inside {@code maxWidth} pixels, finished with an
     * ellipsis. The footer hint uses it so a long hint never runs under the reset button.
     */
    private static String clipToWidth(BitmapFont font, String text, float maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0f) {
            return "";
        }
        try {
            if (AgentHudRenderer.measure(font, text) <= maxWidth) {
                return text;
            }
            int lo = 0;
            int hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (AgentHudRenderer.measure(font, text.substring(0, mid) + NotesPanel.ELLIPSIS)
                        <= maxWidth) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo <= 0 ? "" : text.substring(0, lo) + NotesPanel.ELLIPSIS;
        } catch (Throwable ignored) {
            return text;
        }
    }

    // ---- input ----

    /**
     * Prefix hook: the only place that decides whether the click belongs to the panel or the menu.
     * Runs before the game's own {@code update()}, so clearing {@code justClickedLeft} here really
     * does swallow the click.
     */
    @SpirePatch(clz = MainMenuScreen.class, method = "update")
    public static class Update {
        @SpirePrefixPatch
        public static void Prefix(MainMenuScreen __instance) {
            if (!NotesPanel.isEnabled()) {
                return;
            }
            try {
                handleInput(__instance);
            } catch (Throwable t) {
                Diag.warn("便签面板: 输入处理失败: " + t);
            }
        }
    }

    private static void handleInput(MainMenuScreen menu) {
        if (!onMenu(menu)) {
            if (PANEL.isOpen()) {
                PANEL.close();
            }
            return;
        }
        boolean clicked = InputHelper.justClickedLeft;
        int mx = InputHelper.mX;
        int my = InputHelper.mY;

        if (!PANEL.isOpen()) {
            if (clicked && entryVisible && contains(ENTRY_RECT[0], ENTRY_RECT[1], ENTRY_RECT[2],
                    ENTRY_RECT[3], mx, my)) {
                openPanel();
                consumeClick();
            }
            return;
        }

        if (Settings.WIDTH != layoutW || Settings.HEIGHT != layoutH) {
            rebuild(false);
        }
        // The confirm state times out on its own: 3 s after the first click the button is idle
        // again, so a forgotten first click can never be completed by a much later second one.
        if (PANEL.tickClear(System.currentTimeMillis())) {
            Diag.info("便签面板: 清空确认超时（3 秒无操作），已复位");
        }
        if (InputHelper.pressedEscape) {
            closePanel("ESC");
            InputHelper.pressedEscape = false;
            return;
        }
        if (InputHelper.scrolledUp) {
            PANEL.scrollRows(-1);
            InputHelper.scrolledUp = false;
        }
        if (InputHelper.scrolledDown) {
            PANEL.scrollRows(1);
            InputHelper.scrolledDown = false;
        }
        handleKeys();

        if (clicked) {
            if (clearVisible && contains(CLEAR_RECT[0], CLEAR_RECT[1], CLEAR_RECT[2], CLEAR_RECT[3],
                    mx, my)) {
                handleClearClick();
            } else if (!insidePanel(mx, my)) {
                closePanel("click-outside");
            } else if (PANEL.cancelClear()) {
                // "点别处" cancels: only the reset button itself may finish the two-click dance.
                Diag.info("便签面板: 清空确认已取消（点了别处）");
            }
            // Panel open: every left click is ours, whether it closed the panel or hit the plate.
            consumeClick();
        }
    }

    /**
     * The only route to {@link Notes#clearAll()}: a human clicking the reset button twice, three
     * seconds apart. The first click only arms the button.
     */
    private static void handleClearClick() {
        if (!PANEL.clickClear(System.currentTimeMillis())) {
            Diag.info("便签面板: 清空按钮已进入确认态（" + NotesPanel.CLEAR_CONFIRM_MS / 1000L
                    + " 秒内再点一次才会清空）");
            return;
        }
        performClear();
    }

    /**
     * Runs the reset after the human confirmed it, then makes every surface agree at once: the
     * panel re-opens on the empty store (第 1/1 页 · 共 0 条), {@code get_state} drops its cached
     * body (notes are not part of the game signature) and the HUD says what just happened.
     */
    private static void performClear() {
        int removed;
        try {
            removed = Notes.clearAll();
        } catch (Throwable t) {
            Diag.warn("便签面板: 清空失败: " + t);
            AgentHud.push("便签清空失败：" + t.getMessage());
            PANEL.cancelClear();
            return;
        }
        invalidateStateCache();
        countCache = -1;
        countAt = 0L;
        rebuild(true);
        AgentHud.push("便签已全部清空（人类操作 " + removed + " 条）");
        Diag.info("便签面板: 已清空 " + removed + " 条，面板回到 " + PANEL.header()
                + "，入口 " + NotesPanel.entryLabel(PANEL.count()));
    }

    /**
     * Notes are not part of what the game signature tracks, so the cached {@code get_state} body has
     * to be dropped by hand (exactly like {@code note_set} does) or the next read still reports the
     * notes the human just wiped.
     */
    private static void invalidateStateCache() {
        try {
            spirelink.state.StateBuilder builder = SpireLink.stateBuilderOrNull();
            if (builder != null) {
                builder.invalidate();
            }
        } catch (Throwable t) {
            // The wipe already happened; a stale cache only costs one delayed get_state.
            Diag.warn("便签面板: 让 get_state 失效失败（不影响清空）: " + t);
        }
    }

    private static void handleKeys() {
        if (Gdx.input == null) {
            return;
        }
        try {
            if (Gdx.input.isKeyJustPressed(Input.Keys.UP)) {
                PANEL.scrollRows(-1);
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.DOWN)) {
                PANEL.scrollRows(1);
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_UP)) {
                PANEL.pageStep(-1);
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_DOWN)) {
                PANEL.pageStep(1);
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.HOME)) {
                PANEL.first();
            }
            if (Gdx.input.isKeyJustPressed(Input.Keys.END)) {
                PANEL.last();
            }
        } catch (Throwable ignored) {
            // keyboard nicety only
        }
    }

    private static void consumeClick() {
        InputHelper.justClickedLeft = false;
        InputHelper.justReleasedClickLeft = false;
    }

    // ---- panel lifecycle ----

    private static void openPanel() {
        clearVisible = false;
        rebuild(true);
        Diag.info("便签面板: 打开，共 " + PANEL.count() + " 条，" + PANEL.header());
    }

    private static void closePanel(String reason) {
        if (!PANEL.isOpen()) {
            return;
        }
        PANEL.close();
        clearVisible = false;
        countCache = -1;
        countAt = 0L;
        Diag.info("便签面板: 关闭（" + reason + "）");
    }

    /**
     * Feeds the panel the font metrics it needs and (re)builds its rows. {@code open} reloads the
     * notes; otherwise only the clipping is redone after a window resize.
     */
    private static void rebuild(boolean open) {
        BitmapFont font = FontHelper.cardDescFont_N;
        float u = ui();
        float scaleX = font == null ? 1f : font.getData().scaleX;
        float scaleY = font == null ? 1f : font.getData().scaleY;
        try {
            if (font != null) {
                font.getData().setScale(ROW_SCALE * u);
                final BitmapFont measured = font;
                PANEL.setMeasurer(new NotesPanel.Measurer() {
                    @Override
                    public float width(String text) {
                        return AgentHudRenderer.measure(measured, text);
                    }
                });
                PANEL.setRowWidth(panelW() - 2f * PANEL_PAD * u);
            } else {
                PANEL.setMeasurer(null);
            }
            if (open) {
                PANEL.open(loadEntries());
            } else {
                PANEL.rebuildRows();
            }
        } finally {
            if (font != null) {
                font.getData().setScale(scaleX, scaleY);
            }
        }
        layoutW = Settings.WIDTH;
        layoutH = Settings.HEIGHT;
    }

    private static List<NotesPanel.Entry> loadEntries() {
        try {
            return NotesPanel.parse(Notes.list(null));
        } catch (Throwable t) {
            Diag.warn("便签面板: 读取便签失败: " + t);
            return new ArrayList<NotesPanel.Entry>();
        }
    }

    // ---- notes count shown on the entry ----

    private static int notesCount() {
        long now = System.currentTimeMillis();
        if (countCache >= 0 && now - countAt < COUNT_TTL_MS) {
            return countCache;
        }
        countAt = now;
        try {
            countCache = loadEntries().size();
        } catch (Throwable t) {
            if (countCache < 0) {
                countCache = 0;
            }
        }
        return countCache;
    }

    // ---- geometry ----

    /** 1.0 at 720p; the panel and the entry keep their apparent size on any window. */
    private static float ui() {
        float u = Settings.HEIGHT / 720f;
        return Math.max(0.75f, Math.min(u, 2.0f));
    }

    private static float panelW() {
        return Math.min(Settings.WIDTH - 80f * ui(), PANEL_W * ui());
    }

    private static float panelH() {
        return Math.min(Settings.HEIGHT - 80f * ui(), PANEL_H * ui());
    }

    private static float panelX() {
        return (Settings.WIDTH - panelW()) / 2f;
    }

    private static float panelY() {
        return (Settings.HEIGHT - panelH()) / 2f;
    }

    private static boolean insidePanel(float x, float y) {
        return contains(panelX(), panelY(), panelW(), panelH(), x, y);
    }

    /** Pure rectangle hit-test (game coordinates, bottom-up); the maths lives in {@link NotesPanel}. */
    private static boolean contains(float rx, float ry, float rw, float rh, float x, float y) {
        return NotesPanel.contains(rx, ry, rw, rh, x, y);
    }

    private static void fill(SpriteBatch sb, float x, float y, float w, float h, Color color) {
        Texture texture = dot();
        if (texture == null || w <= 0f || h <= 0f) {
            return;
        }
        sb.setColor(color);
        sb.draw(texture, x, y, w, h);
    }

    /** 1x1 white pixel for the dimmer / plate / border (cheaper than a ShapeRenderer). */
    private static Texture dot() {
        if (dot != null || dotTried) {
            return dot;
        }
        dotTried = true;
        try {
            Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pixmap.setColor(1f, 1f, 1f, 1f);
            pixmap.fill();
            dot = new Texture(pixmap);
            pixmap.dispose();
        } catch (Throwable t) {
            dot = null;
        }
        return dot;
    }
}
