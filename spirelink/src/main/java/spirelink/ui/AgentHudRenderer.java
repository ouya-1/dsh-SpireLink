package spirelink.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;

import java.util.List;

/**
 * Draws the agent HUD (design P0.5 v2).
 *
 * <p>Text only: no background plate, no rectangle, no border, no shadow box — the panel is meant
 * to blend into the art. Readability comes from an 8-direction black outline, the same trick the
 * game's own subtitles use. Everything idles out of the way: after 8 s without an event the whole
 * panel dims to 55 %, after 20 s to 30 %, and any new line snaps it back to full brightness.
 *
 * <p>Game/libgdx types stay in this class; the buffer logic lives in {@link HudModel} and
 * {@link AgentHud} so it can be unit tested without a game runtime.
 */
public final class AgentHudRenderer {

    /** Left edge of the text block. */
    public static final float X = 24f;
    /** Top edge of the first (oldest) line, measured down from the top of the screen. */
    public static final float TOP_OFFSET = 118f;
    public static final float LINE_HEIGHT = 24f;
    /** Game default size: readable from a distance at the 1280x720 virtual resolution. */
    public static final float FONT_SCALE = 1.0f;
    public static final float OUTLINE_OFFSET = 1.4f;
    public static final float OUTLINE_ALPHA = 0.62f;
    /** Fallback advance of the bullet when the font cannot measure it. */
    private static final float BULLET_FALLBACK = 16f;

    /**
     * The one font every HUD block resolves through {@link #hudFont()}.
     *
     * <p>Kept as a name so tests and logs can prove the thought block and the activity feed use the
     * <em>same</em> font instead of two look-alikes (issue #28: 乱码排查时第一件事就是确认这点).
     */
    public static final String FONT_RESOURCE = "FontHelper.cardDescFont_N";

    /**
     * Everything that decides how a block of HUD text looks: font, scale, line height, 8-way stroke.
     * Feed, thought block and (by the same constants) the note block share it; only the colour and
     * the characters-per-row budget differ.
     */
    public static final class TextStyle {
        public final String fontResource;
        public final float fontScale;
        public final float lineHeight;
        public final float outlineOffset;
        public final float outlineAlpha;
        public final int charsPerLine;

        TextStyle(String fontResource, float fontScale, float lineHeight, float outlineOffset,
                  float outlineAlpha, int charsPerLine) {
            this.fontResource = fontResource;
            this.fontScale = fontScale;
            this.lineHeight = lineHeight;
            this.outlineOffset = outlineOffset;
            this.outlineAlpha = outlineAlpha;
            this.charsPerLine = charsPerLine;
        }

        /** True when both blocks would use the same font object, size and stroke. */
        public boolean sameFontAndStroke(TextStyle other) {
            return other != null
                    && fontResource.equals(other.fontResource)
                    && Float.compare(fontScale, other.fontScale) == 0
                    && Float.compare(lineHeight, other.lineHeight) == 0
                    && Float.compare(outlineOffset, other.outlineOffset) == 0
                    && Float.compare(outlineAlpha, other.outlineAlpha) == 0;
        }

        @Override
        public String toString() {
            return fontResource + " scale=" + fontScale + " 行高=" + lineHeight
                    + " 描边=" + outlineOffset + "/" + outlineAlpha + " 每行字数=" + charsPerLine;
        }
    }

    /** Activity feed: 20 chars per row, the game's card description font at 100 %. */
    public static final TextStyle FEED_STYLE = new TextStyle(FONT_RESOURCE, FONT_SCALE, LINE_HEIGHT,
            OUTLINE_OFFSET, OUTLINE_ALPHA, HudModel.MAX_CHARS);
    /** Thought block: same font/scale/line height/stroke as the feed, 32 chars per row. */
    public static final TextStyle THOUGHT_STYLE = new TextStyle(FONT_RESOURCE, FONT_SCALE,
            LINE_HEIGHT, OUTLINE_OFFSET, OUTLINE_ALPHA, 32);

    private static final Color TEXT_RGB = new Color(1.00f, 0.93f, 0.74f, 1f);
    private static final Color BULLET_NEWEST_RGB = new Color(1.00f, 0.84f, 0.35f, 1f);
    /** Note line: light cyan so a human can tell "notes" from "actions" at a glance. */
    private static final Color NOTE_RGB = new Color(0.72f, 0.93f, 1.00f, 1f);
    /**
     * Note block: top-right corner, right aligned, wrapped to 3 rows.
     *
     * <p>The y convention is exactly the one the activity feed uses - measured DOWN from the top of
     * the screen, i.e. {@code Settings.HEIGHT - offset} in libgdx's bottom-up space. 196px clears the
     * potion/relic bar (which reaches ~160px and is drawn over this layer on some screens).
     */
    private static final float NOTE_TOP = 196f;
    private static final float NOTE_MARGIN_RIGHT = 28f;
    private static final int NOTE_CHARS_PER_LINE = 26;
    /** 0.1.4: 3 行装不下一条便签（笔记本来就长），改成 6 行；右上角还有空间。 */
    private static final int NOTE_MAX_LINES = 6;

    /** Last note text whose on-screen position was logged (diagnostics only). */
    private static String loggedNote;

    /**
     * Thought block (issue #28): the agent's reasoning, top-left, right above the activity feed.
     *
     * <p>{@link #THOUGHT_TOP} is the row the feed itself uses today, so the thought lands on a spot
     * that is proven to be clear of the top bar and the relic column; the feed is then pushed down
     * by exactly the height of what was drawn, and comes back up when the thought's TTL runs out.
     */
    public static final float THOUGHT_TOP = 118f;
    /** 32 chars per row fits the 1280x720 virtual width next to the left relic column. */
    public static final int THOUGHT_CHARS_PER_LINE = THOUGHT_STYLE.charsPerLine;
    /**
     * 0.1.4: 3 行 × 32 字只有 96 字，一条像样的打算就溢出了；放到 5 行（160 字）。
     *
     * <p>不能更高：活动流紧跟在思考块下面，6 行时 feedBottom 会越过 720p 的中线
     * （{@code AgentHudThoughtTest} 有一条断言就是守这个的）。
     */
    public static final int THOUGHT_MAX_LINES = 5;
    /** Empty rows between the thought block and the shifted activity feed. */
    public static final float THOUGHT_GAP = 10f;

    /** Soft lavender: different from both the feed's cream and the note's cyan. */
    private static final Color THOUGHT_RGB = new Color(0.86f, 0.84f, 1.00f, 1f);

    /** Last thought text whose on-screen position was logged (diagnostics only). */
    private static String loggedThought;

    /**
     * Logs where the thought block is drawn, once per thought. Acceptance for #28 reads this line
     * out of the debug log and compares it with the screenshots, the same way #4 does for the feed.
     *
     * <p>It carries the font, the scale, the line height and the measured width of the first row, so
     * a garbled block can be told apart from a wrong font/width without re-running anything: a CJK
     * row measures roughly 13px per character, U+FFFD rows measure nothing at all.
     */
    private static void logThoughtPlacementOnce(String thought, int lines, float x, float y,
                                                float feedTopOffset, float firstRowWidth) {
        if (thought.equals(loggedThought)) {
            return;
        }
        loggedThought = thought;
        spirelink.util.Diag.info("HUD 思考绘制: 行数=" + lines + " x=" + Math.round(x)
                + " y=" + Math.round(y) + " 首行宽度=" + Math.round(firstRowWidth) + "px（字体="
                + THOUGHT_STYLE.fontResource + " scale=" + THOUGHT_STYLE.fontScale + " 行高="
                + Math.round(THOUGHT_STYLE.lineHeight) + " 描边=" + THOUGHT_STYLE.outlineOffset
                + " 屏幕 " + Math.round(Settings.WIDTH) + "x" + Math.round(Settings.HEIGHT)
                + "，距顶 " + Math.round(THOUGHT_TOP) + "px，活动流起点 "
                + Math.round(feedTopOffset) + "px）");
    }

    /**
     * The single font access point of the HUD: feed, thought block and cursor captions all come
     * through here, so "draw the thought with the same font as the feed" is structural, not a copy
     * of a constant. Null when the game has not loaded its fonts yet (render then does nothing).
     */
    static BitmapFont hudFont() {
        return FontHelper.cardDescFont_N;
    }

    /**
     * Draws one block of already-wrapped rows, top-down, with the shared font and 8-way stroke.
     * The activity feed uses it too (its bullet is drawn separately), so both blocks cannot drift
     * to a different font or stroke by accident.
     */
    private static void drawRows(SpriteBatch sb, BitmapFont font, List<String> rows, float x,
                                 float topDownY, float alpha, Color color, TextStyle style) {
        FILL.set(color.r, color.g, color.b, alpha);
        float y = Settings.HEIGHT - topDownY;
        for (int i = 0; i < rows.size(); i++) {
            drawOutlined(sb, font, rows.get(i), x, y - i * style.lineHeight, FILL, alpha);
        }
    }

    /**
     * Logs where the note block is drawn, once per note text. A missing note line in a screenshot is
     * otherwise impossible to tell apart from "rendered off screen" or "not rendered at all".
     */
    private static void logPlacementOnce(String note, int lines, float x, float y) {
        if (note.equals(loggedNote)) {
            return;
        }
        loggedNote = note;
        spirelink.util.Diag.info("HUD 笔记绘制: 行数=" + lines + " 首行 x=" + Math.round(x)
                + " y=" + Math.round(y) + "（屏幕 " + Math.round(Settings.WIDTH) + "x"
                + Math.round(Settings.HEIGHT) + "，距顶 " + Math.round(NOTE_TOP) + "px）");
    }

    /** Reused scratch colors so drawing allocates nothing per frame. */
    private static final Color FILL = new Color();
    private static final Color BULLET = new Color();
    private static final Color OUTLINE = new Color();

    private AgentHudRenderer() {
    }

    /**
     * Guarded entry used by both patches: no work at all when the HUD is disabled, and a broken
     * HUD can never break the render loop.
     */
    public static void renderSafely(SpriteBatch sb) {
        if (!AgentHud.isEnabled()) {
            return;
        }
        try {
            render(sb);
        } catch (Throwable ignored) {
            // never break rendering
        }
    }

    /** Draws the text block; returns immediately when there is nothing to show. */
    public static void render(SpriteBatch sb) {
        if (sb == null) {
            return;
        }
        List<HudModel.Line> lines = AgentHud.lines();
        String note = AgentHud.noteText();
        String thought = AgentHud.thoughtText();
        if (lines.isEmpty() && note == null && thought == null) {
            return;
        }
        BitmapFont font = hudFont();
        if (font == null) {
            return;
        }
        float idle = AgentHud.idleFactor();
        // Distance down from the top of the screen where the activity feed starts. The thought
        // block reserves rows above it, so the two never overlap (issue #28).
        float feedTopOffset = TOP_OFFSET;

        // Save/restore the shared font scale: never leak a global scale change.
        float scaleX = font.getData().scaleX;
        float scaleY = font.getData().scaleY;
        try {
            font.getData().setScale(FONT_SCALE);
            // Note block sits in the top-right corner (right aligned, wrapped, never truncated):
            // the activity feed keeps the left side, so the two never compete for the same rows.
            if (note != null) {
                float noteAlpha = AgentHud.noteAlpha() * idle;
                if (noteAlpha > 0.02f) {
                    FILL.set(NOTE_RGB.r, NOTE_RGB.g, NOTE_RGB.b, noteAlpha);
                    java.util.List<String> wrapped = wrap(note, NOTE_CHARS_PER_LINE, NOTE_MAX_LINES);
                    for (int i = 0; i < wrapped.size(); i++) {
                        String part = wrapped.get(i);
                        float width = measure(font, part);
                        float x = Math.max(40f, Settings.WIDTH - NOTE_MARGIN_RIGHT - width);
                        // Same convention as the activity feed: distance down from the top.
                        float y = Settings.HEIGHT - (NOTE_TOP + i * LINE_HEIGHT);
                        drawOutlined(sb, font, part, x, y, FILL, noteAlpha);
                        logPlacementOnce(note, wrapped.size(), x, y);
                    }
                }
            }
            // Thought block last, because it decides where the feed starts this frame.
            if (thought != null) {
                float thoughtAlpha = AgentHud.thoughtAlpha() * idle;
                if (thoughtAlpha > 0.02f) {
                    List<String> wrapped = thoughtLines(thought);
                    if (!wrapped.isEmpty()) {
                        // Same font object, same scale, same 8-way stroke and same row helper as the
                        // activity feed below: one code path, one look (issue #28).
                        drawRows(sb, font, wrapped, X, THOUGHT_TOP, thoughtAlpha, THOUGHT_RGB,
                                THOUGHT_STYLE);
                        feedTopOffset = THOUGHT_TOP
                                + wrapped.size() * THOUGHT_STYLE.lineHeight + THOUGHT_GAP;
                        logThoughtPlacementOnce(thought, wrapped.size(), X,
                                Settings.HEIGHT - THOUGHT_TOP, feedTopOffset,
                                measure(font, wrapped.get(0)));
                    }
                }
            }
            float top = Settings.HEIGHT - feedTopOffset;
            float bulletWidth = measure(font, HudModel.BULLET);
            for (int i = 0; i < lines.size(); i++) {
                HudModel.Line line = lines.get(i);
                float y = top - i * FEED_STYLE.lineHeight;
                float alpha = HudModel.lineAlpha(line.age) * idle;
                boolean newest = line.age == 0;

                if (newest) {
                    BULLET.set(BULLET_NEWEST_RGB.r, BULLET_NEWEST_RGB.g, BULLET_NEWEST_RGB.b, alpha);
                } else {
                    BULLET.set(TEXT_RGB.r, TEXT_RGB.g, TEXT_RGB.b, alpha);
                }
                drawOutlined(sb, font, HudModel.BULLET, X, y, BULLET, alpha);

                FILL.set(TEXT_RGB.r, TEXT_RGB.g, TEXT_RGB.b, alpha);
                drawOutlined(sb, font, line.display(), X + bulletWidth, y, FILL, alpha);
            }
        } finally {
            font.getData().setScale(scaleX, scaleY);
        }
    }

    /**
     * Rows the thought block draws for one thought: 32 chars x 3 rows, never more than
     * {@code THOUGHT_MAX_LINES} (issue #28: "2-3 行 × 26-40 字").
     */
    static List<String> thoughtLines(String text) {
        return wrap(text, THOUGHT_CHARS_PER_LINE, THOUGHT_MAX_LINES);
    }

    /**
     * Splits a note into at most {@code maxLines} rows of {@code maxChars} characters, breaking on
     * the last space when there is one. The last row gets an ellipsis only when text is really lost
     * (more than {@code maxLines * maxChars} characters).
     */
    static java.util.List<String> wrap(String text, int maxChars, int maxLines) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (text == null) {
            return out;
        }
        String rest = text.trim();
        while (!rest.isEmpty() && out.size() < maxLines) {
            if (rest.length() <= maxChars) {
                out.add(rest);
                rest = "";
                break;
            }
            int cut = maxChars;
            int space = rest.lastIndexOf(' ', maxChars);
            if (space > maxChars / 2) {
                cut = space;
            }
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty() && !out.isEmpty()) {
            String last = out.get(out.size() - 1);
            out.set(out.size() - 1, last.length() >= maxChars
                    ? last.substring(0, Math.max(0, maxChars - 1)) + "…"
                    : last + "…");
        }
        return out;
    }

    /**
     * 8 black offsets first, then the fill color on top.
     *
     * <p>Public so other human-facing panels (the main-menu notes panel, issue #46) get exactly the
     * HUD's outline instead of inventing a second text style.</p>
     */
    public static void drawOutlined(SpriteBatch sb, BitmapFont font, String text, float x, float y,
                                    Color fill, float alpha) {
        OUTLINE.set(0f, 0f, 0f, OUTLINE_ALPHA * alpha);
        float d = OUTLINE_OFFSET;
        draw(sb, font, text, x - d, y, OUTLINE);
        draw(sb, font, text, x + d, y, OUTLINE);
        draw(sb, font, text, x, y - d, OUTLINE);
        draw(sb, font, text, x, y + d, OUTLINE);
        draw(sb, font, text, x - d, y - d, OUTLINE);
        draw(sb, font, text, x + d, y - d, OUTLINE);
        draw(sb, font, text, x - d, y + d, OUTLINE);
        draw(sb, font, text, x + d, y + d, OUTLINE);
        draw(sb, font, text, x, y, fill);
    }

    private static void draw(SpriteBatch sb, BitmapFont font, String text, float x, float y,
                             Color color) {
        FontHelper.renderFontLeftTopAligned(sb, font, text, x, y, color);
    }

    /** Package-visible wrapper so the cursor can measure its caption with the same font metrics. */
    static float measurePublic(BitmapFont font, String text) {
        return measure(font, text);
    }

    /** Draws one outlined caption line in the caption colour (used by the fish pointer). */
    static void drawCaption(SpriteBatch sb, BitmapFont font, String text, float x, float y,
                            float alpha) {
        FILL.set(TEXT_RGB.r, TEXT_RGB.g, TEXT_RGB.b, alpha);
        drawOutlined(sb, font, text, x, y, FILL, alpha);
    }

    /**
     * Width of one line with the font's current scale; the notes panel uses it to clip a row to the
     * space it really has (a 208-character note must not run off the plate).
     */
    public static float measure(BitmapFont font, String text) {
        try {
            GlyphLayout layout = new GlyphLayout();
            layout.setText(font, text);
            return layout.width;
        } catch (Throwable ignored) {
            return BULLET_FALLBACK;
        }
    }
}
