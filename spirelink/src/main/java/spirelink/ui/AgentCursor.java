package spirelink.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The 大肥鱼 pointer: a game-side cursor that shows what the agent is doing right now.
 *
 * <p>Design rules:</p>
 * <ul>
 *   <li><b>Drawn by the game, never by the OS.</b> Nothing here touches the real mouse or the
 *       system cursor, so it disappears with the game window and never leaks onto the desktop.</li>
 *   <li><b>Two pointers, two owners.</b> The fish follows only the agent's own action targets. It
 *       never reads {@code InputHelper.mX/mY} (the human's cursor) and never writes to it, so the
 *       human cannot drag the agent's pointer around and the agent cannot move the human's.</li>
 *   <li><b>It moves like a hand, not like a teleport.</b> Speed and shape come from a measured
 *       human session (see {@code docs/大肥鱼指针.md}): duration grows with distance
 *       (T ≈ 0.12 + 0.11·log2(D/40+1) s), the velocity profile is a minimum-jerk bell, and the
 *       press is a short dip <i>after</i> arrival — the pointer is rarely still at the instant the
 *       action lands.</li>
 *   <li><b>Never breaks rendering.</b> Any failure is swallowed.</li>
 * </ul>
 */
public final class AgentCursor {

    // ---- the six faces (file names under spirelink/ui/cursor/) ----
    /** Idle at the last point the agent touched. */
    public static final String NORMAL = "normal";
    /** Gliding toward a target. */
    public static final String HOVER = "hover";
    /** Just acted, or acting right now. */
    public static final String CLICK = "click";
    /** Picking cards on a selection grid. */
    public static final String DRAG = "drag";
    /** Waiting on the harness / model. */
    public static final String BUSY = "busy";
    /** Something went wrong: the action was refused, or the mod is not ready. */
    public static final String HELP = "help";

    private static final String DIR = "spirelink/ui/cursor/";
    /**
     * Reference size at 1080p. Everything (sprite, offset, anchor, drift, travel look-ahead) is
     * multiplied by {@link #uiScale()}, so the pointer keeps the same <i>apparent</i> size and the
     * same feel at 720p, 1080p, 1440p or 4K - the game's virtual screen is whatever the window is,
     * so a fixed 148 px fish would be a speck at 4K and a billboard at 720p.
     */
    private static final float SIZE = 148f;
    /** The fish sits up-right of the exact point so the anchor cross stays visible. */
    private static final float OFFSET_X = 74f;
    private static final float OFFSET_Y = 44f;
    private static final float ANCHOR_ARM = 16f;
    private static final float ANCHOR_THICK = 3.5f;
    /**
     * Free space kept between the fish and the window edge, on top of half the sprite size
     * (issue #53). Scaled by {@link #uiScale()} like everything else.
     */
    private static final float EDGE_MARGIN = 8f;

    /** 1.0 at 1080p, 0.67 at 720p, 2.0 at 4K. */
    private static float uiScale() {
        float s = Settings.HEIGHT / 1080f;
        return Math.max(0.5f, Math.min(s, 3.0f));
    }

    // ---- motion, fitted to a real 41-minute session ----
    /** T = BASE + SLOPE * log2(distance / 40 + 1), clamped: 340 px ≈ 0.47 s, like the human's 0.51 s. */
    private static final float TRAVEL_BASE = 0.12f;
    private static final float TRAVEL_SLOPE = 0.11f;
    private static final float TRAVEL_MIN = 0.12f;
    private static final float TRAVEL_MAX = 0.60f;
    /** The finger goes down a moment after the hand stops (measured: 0.08 s median within 6 px). */
    private static final float PRESS_DELAY = 0.06f;
    /** How long the pressed pose lasts (scale dips and comes back). */
    private static final float PRESS_TIME = 0.10f;
    /** After the press the hand leaves: ~46 px in 300 ms was the measured median. */
    private static final float RELAX_AFTER = 0.50f;
    private static final float RELAX_TIME = 0.45f;
    private static final float RELAX_PIXELS = 22f;

    /** How long a transient state (click / drag) stays on screen. */
    private static final float TRANSIENT = 0.9f;
    /** How long the "you got rejected" face stays. */
    private static final float HELP_LIFE = 1.6f;
    private static final float LABEL_LIFE = 4.0f;
    private static final float ACTIVE_ALPHA = 1.0f;
    private static final float IDLE_ALPHA = 0.45f;

    private static final Map<String, Texture> TEXTURES = new HashMap<String, Texture>();
    private static Texture dotTexture;
    private static boolean dotTried;
    private static boolean enabled = true;
    private static float scale = 1.0f;

    private static String label;
    private static float labelAge = 999f;
    private static String transientState = CLICK;
    private static float transientAge = 999f;
    private static boolean busy;
    /** Last action refused (decays after HELP_LIFE seconds). */
    private static boolean trouble;
    private static float troubleAge = 999f;
    /** Bridge not ready yet - the "?" face stays up. */
    private static boolean notReady;

    /** Seconds since the first rendered frame; the animation clock. */
    private static float clock;
    /** Where the pointer is drawn (game coordinates). */
    private static float curX;
    private static float curY;
    private static boolean positioned;
    /** The point the agent touched: the anchor cross, and the end of the glide. */
    private static float targetX;
    private static float targetY;
    private static boolean hasTarget;
    /** Glide state. */
    private static boolean moving;
    private static float fromX;
    private static float fromY;
    private static float travelStart;
    private static float travelDur = TRAVEL_MIN;
    /** Press / relax state. */
    private static boolean pressQueued;
    private static float pressAge = 999f;
    private static float arriveAge = 999f;

    /** Where the pointer rests before the agent has touched anything (bottom middle). */
    private static float homeX() {
        // Asked at draw time on purpose: Settings.WIDTH/HEIGHT are not reliable while the mod is
        // still booting, and a static final here froze them at 0 (the fish parked in the corner).
        return Settings.WIDTH * 0.5f;
    }

    private static float homeY() {
        return Settings.HEIGHT * 0.16f;
    }

    private static final Color TINT = new Color(1f, 1f, 1f, 1f);

    // ---- defensive batch state (issue #44) ----
    /** Identity transform: what every screen leaves behind except the rotated-text helpers. */
    private static final Matrix4 IDENTITY = new Matrix4();
    /** Copy of the batch colour we found on entry, put back in the finally block. */
    private static final Color SAVED_COLOR = new Color();

    // ---- draw diagnostics (issue #44) ----
    /**
     * The idle wobble moves the fish by about one pixel per frame, so "paint it, log it" wrote a
     * line every time the rounding flipped (QA: 257 lines in 5 minutes, and the log file is
     * already megabytes). A line is therefore only worth writing when the screen, the face, the
     * alpha or the flight changes, or when the fish really moved; plain movement is additionally
     * capped to one line per second and the skipped ones are counted into the next line.
     */
    static final float LOG_MOVE_PX = 8f;
    /** Minimum gap between two movement lines (screen/state changes are never held back). */
    static final long LOG_MIN_INTERVAL_MS = 1000L;
    /** {@link #drawLogDecision} outcomes. */
    static final int LOG_NEVER = 0;
    static final int LOG_YES = 1;
    static final int LOG_SUPPRESSED = 2;

    private static String loggedScreen;
    private static String loggedState;
    private static float loggedX;
    private static float loggedY;
    private static float loggedAlpha = -1f;
    private static boolean loggedMoving;
    private static long loggedAt;
    /** Draws skipped since the last written line ("此前抑制 N 条"). */
    private static int suppressed;
    private static String loggedNotDrawing;
    /**
     * Issue #53: the clamp line is worth exactly one entry per target, not one per frame - during a
     * glide the sprite stays clamped for hundreds of frames, and a heartbeat here would rebuild the
     * noise #44 removed. The target whose clamp was already written is remembered below.
     */
    private static boolean clampReported;
    private static float clampLoggedTargetX;
    private static float clampLoggedTargetY;

    private AgentCursor() {
    }

    /** Called from the bootstrap whenever the config is (re)read. */
    public static void configure(boolean on, float scaleFactor) {
        enabled = on;
        scale = scaleFactor <= 0f ? 1.0f : Math.min(scaleFactor, 3.0f);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** The caption under the fish ("打出手牌《痛击》"), refreshed on every agent action. */
    public static void label(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        label = text;
        labelAge = 0f;
    }

    /**
     * Sends the fish to a point in game coordinates.
     *
     * <p>Deliberately not "the mouse position": the human's cursor and the agent's pointer are two
     * separate things, and neither may move the other. A new target mid-glide simply re-aims from
     * wherever the fish is, the way a hand would.</p>
     */
    public static void flyTo(float x, float y) {
        float sx = positioned ? curX : homeX();
        float sy = positioned ? curY : homeY();
        targetX = x;
        targetY = y;
        hasTarget = true;
        fromX = sx;
        fromY = sy;
        float distance = (float) Math.hypot(x - sx, y - sy);
        travelDur = travelSeconds(distance / uiScale());
        travelStart = clock;
        moving = distance > 1.5f;
        if (!moving) {
            curX = x;
            curY = y;
        }
        pressQueued = false;
    }

    /**
     * Duration of a glide, fitted to the human session: {@code T = 0.12 + 0.11·log2(D/40+1)}.
     *
     * <p>Median human reach in that session was 339 px in 0.51 s; the fish is allowed to be a
     * little quicker because it also has to keep up with an agent that acts every second or two.</p>
     */
    private static float travelSeconds(float distance) {
        if (distance < 1f) {
            return TRAVEL_MIN;
        }
        float t = TRAVEL_BASE + TRAVEL_SLOPE * (float) (Math.log(distance / 40f + 1f) / Math.log(2));
        return Math.max(TRAVEL_MIN, Math.min(TRAVEL_MAX, t));
    }

    /**
     * Same as {@link #flyTo} but returns how long the glide will take, in milliseconds.
     *
     * <p>Used by the "look" peeks: the screen should open when the hand gets there, not before, so
     * the caller schedules the open at {@code now + eta}.</p>
     */
    public static long flyToEta(float x, float y) {
        flyTo(x, y);
        return moving ? (long) (travelDur * 1000f) : 0L;
    }

    /**
     * Blocks the caller until the fish has arrived (or {@code maxMs} passes).
     *
     * <p>The game applies an action the instant it is called, so a pointer sent afterwards would
     * always be a beat late — the card is already flying, the reward already taken, and the click
     * reads as delayed. Waiting for the hand to land first puts the two in the order a human
     * produces them: reach, press, effect.</p>
     */
    public static void awaitArrival(int maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (moving && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(4L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Records that an action just ran, so the face matches it.
     *
     * <p>The press is <i>queued</i>: it plays when the hand gets there, not in the air. On a long
     * glide that means the click face trails the action by a few hundred ms, exactly like a person
     * who is still moving when the game registers the click.</p>
     *
     * @param gridPick the action picked cards on a selection grid
     */
    public static void acted(boolean gridPick) {
        transientState = gridPick ? DRAG : CLICK;
        transientAge = 0f;
        if (moving) {
            pressQueued = true;
        } else {
            pressAge = -PRESS_DELAY;
        }
        busy = false;
        trouble = false;
    }

    /** The harness is thinking (request queued/in flight). */
    public static void setBusy(boolean value) {
        busy = value;
    }

    /** The action was refused - the fish pulls a "?" face so a human sees the agent is stuck. */
    public static void rejected() {
        trouble = true;
        troubleAge = 0f;
        busy = false;
    }

    /** Not connected / not ready: the "?" face stays up until things work again. */
    public static void setUnavailable(boolean value) {
        notReady = value;
    }

    /** Forgets everything (new run / disconnect): the fish disappears until the next action. */
    public static void reset() {
        label = null;
        labelAge = 999f;
        transientAge = 999f;
        troubleAge = 999f;
        busy = false;
        trouble = false;
        notReady = false;
        hasTarget = false;
        positioned = false;
        moving = false;
        pressQueued = false;
        pressAge = 999f;
        arriveAge = 999f;
        clampReported = false;
    }

    public static void renderSafely(SpriteBatch sb) {
        if (!enabled || sb == null) {
            return;
        }
        try {
            render(sb);
        } catch (Throwable ignored) {
            // a decorative sprite must never break the render loop
        }
    }

    /**
     * Picks the face for this frame.
     *
     * <p>Order matters: "something is wrong" wins over "I am busy", which wins over "I am still
     * flying there" (hover), which wins over "I just did something" (click/drag).</p>
     */
    private static String effectiveState() {
        if (notReady || (trouble && troubleAge < HELP_LIFE)) {
            return HELP;
        }
        if (busy) {
            return BUSY;
        }
        if (moving) {
            return HOVER;
        }
        if (transientAge < TRANSIENT) {
            return transientState;
        }
        if (gridSelecting()) {
            return DRAG;
        }
        return NORMAL;
    }

    private static boolean gridSelecting() {
        try {
            com.megacrit.cardcrawl.screens.select.GridCardSelectScreen grid =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.gridSelectScreen;
            return grid != null && grid.selectedCards != null && !grid.selectedCards.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Minimum-jerk easing (zero velocity at both ends, peak speed in the middle), time-warped so the
     * hand gets going early: a real reach covers 18% of the distance in the first quarter of the
     * time, plain minimum-jerk only 10%. {@code u^0.85} closes that gap and matches the measured
     * cumulative curve to within a couple of percent.
     */
    private static float smooth(float u) {
        if (u <= 0f) {
            return 0f;
        }
        if (u >= 1f) {
            return 1f;
        }
        float w = (float) Math.pow(u, 0.85);
        return w * w * w * (10f + w * (-15f + 6f * w));
    }

    /** Advances the glide, the press and the "hand leaves the card" drift. */
    private static void advance(float dt) {
        // No action yet (fresh menu / new run): the goal is the quiet home spot at the bottom.
        float gx = hasTarget ? targetX : homeX();
        float gy = hasTarget ? targetY : homeY();
        if (moving) {
            float age = clock - travelStart;
            float u = travelDur <= 0f ? 1f : Math.min(1f, age / travelDur);
            float e = smooth(u);
            curX = fromX + (gx - fromX) * e;
            curY = fromY + (gy - fromY) * e;
            if (u >= 1f) {
                moving = false;
                curX = gx;
                curY = gy;
                positioned = true;
                arriveAge = 0f;
                if (pressQueued) {
                    pressQueued = false;
                    pressAge = -PRESS_DELAY;
                }
            }
        } else {
            curX = gx;
            curY = gy;
            positioned = true;
        }
        if (pressAge < PRESS_TIME) {
            pressAge += dt;
        }
        if (arriveAge < 999f) {
            arriveAge += dt;
        }
    }

    private static void render(SpriteBatch sb) {
        // Two hooks call this (AbstractDungeon.render inside a run, MainMenuScreen.render on the
        // menu), so there is no game-mode test here: the caller already knows where it is.
        float dt = Gdx.graphics.getDeltaTime();
        if (dt < 0f || dt > 0.5f) {
            dt = 0f;
        }
        clock += dt;
        labelAge += dt;
        transientAge += dt;
        troubleAge += dt;
        advance(dt);

        String state = effectiveState();
        Texture tex = texture(state);
        if (tex == null) {
            return;
        }
        String screen = screenName();
        if (!sb.isDrawing()) {
            // Whoever ran before us called end() and never began again: every draw below would
            // throw and the fish would vanish with no trace anywhere. Say it once per screen.
            logNotDrawing(screen);
            return;
        }

        // Idle drift: a real hand is never perfectly still (1-3 px of tremor at rest), and after a
        // click it slides off the card instead of staying parked on it.
        float relax = 0f;
        if (!moving && hasTarget && arriveAge > RELAX_AFTER) {
            relax = smooth(Math.min(1f, (arriveAge - RELAX_AFTER) / RELAX_TIME));
        }
        float u = uiScale();
        float driftX = 0f;
        float driftY = 0f;
        if (relax > 0f) {
            float dx = homeX() - targetX;
            float dy = homeY() - targetY;
            float len = (float) Math.hypot(dx, dy);
            if (len > 1f) {
                driftX = dx / len * RELAX_PIXELS * u * relax;
                driftY = dy / len * RELAX_PIXELS * u * relax;
            }
        }
        float wobble = (moving || pressAge < PRESS_TIME) ? 0f : 1f;
        float x = curX + driftX + (float) Math.sin(clock * 2.2f) * 0.9f * u * wobble;
        float y = curY + driftY + (float) Math.sin(clock * 3.1f + 1.3f) * 0.7f * u * wobble;

        boolean lively = labelAge < LABEL_LIFE || transientAge < TRANSIENT || busy || notReady
                || moving || (trouble && troubleAge < HELP_LIFE);
        float alpha = lively ? ACTIVE_ALPHA : IDLE_ALPHA;
        float bob = (labelAge < LABEL_LIFE || moving) ? (float) Math.sin(labelAge * 5.0) * 2.2f * u : 0f;
        // Press: the sprite dips for a moment, like a finger pushing down.
        float press = (pressAge >= 0f && pressAge < PRESS_TIME)
                ? (float) Math.sin(Math.PI * pressAge / PRESS_TIME) : 0f;
        float side = SIZE * scale * u * (1f - 0.09f * press);

        // ---- keep the whole fish inside the window (issue #53) ----
        // The sprite is drawn up-right of the anchor (OFFSET_*), so a target close to an edge
        // pushes part of it outside the window: hand cards sit at the bottom of the screen and
        // choose_hand_cards pointed at 234,-7, which left the lower quarter of the fish scissored
        // away. Only the *picture* moves here - the anchor cross below stays on the touched point,
        // and the coordinates the agent reports are untouched.
        float fishCx = x + OFFSET_X * scale * u;
        float fishCy = y + OFFSET_Y * scale * u + bob;
        float safeHalf = side * 0.5f + EDGE_MARGIN * u;
        float[] fish = clampToWindow(fishCx, fishCy, Settings.WIDTH, Settings.HEIGHT, safeHalf);
        boolean clamped = fish[0] != fishCx || fish[1] != fishCy;

        // ---- defensive batch state (issue #44) ----
        // Nothing in the screen chain (map / rewards / shop) touches the matrices - verified over
        // the whole game jar: only FontHelper calls setTransformMatrix, and only to restore it -
        // but the fish is drawn last in the frame, so it must not inherit anything either. Draw our
        // own quads through the identity transform and hand the caller's state back untouched: the
        // pointer can then never land somewhere else because of a stale matrix, and never leaks one.
        Matrix4 originalTransform = sb.getTransformMatrix();
        SAVED_COLOR.set(sb.getColor());
        boolean repaired = !isIdentity(originalTransform);
        if (repaired) {
            sb.flush();
            sb.setTransformMatrix(IDENTITY);
        }
        try {
            TINT.set(1f, 1f, 1f, alpha);
            sb.setColor(TINT);
            sb.draw(tex, fish[0] - side / 2f, fish[1] - side / 2f, side, side);

            // A tiny cross exactly on the point the agent touched: the fish is cute, the cross is
            // the actual "this is what I pressed" marker - so it is drawn last, on top of the fish.
            Texture dot = dot();
            if (dot != null && hasTarget) {
                TINT.set(1f, 0.98f, 0.86f, 0.95f * alpha);
                sb.setColor(TINT);
                float arm = ANCHOR_ARM * u;
                float thick = ANCHOR_THICK * u;
                sb.draw(dot, x - arm, y - thick / 2f, arm * 2f, thick);
                sb.draw(dot, x - thick / 2f, y - arm, thick, arm * 2f);
            }

            if (label != null && labelAge < LABEL_LIFE) {
                BitmapFont font = FontHelper.cardDescFont_N;
                if (font != null) {
                    float labelAlpha = Math.min(1f, (LABEL_LIFE - labelAge) / 0.5f) * alpha;
                    String text = label.length() > 26 ? label.substring(0, 25) + "…" : label;
                    float width = AgentHudRenderer.measurePublic(font, text);
                    // The caption follows the (possibly clamped) fish, not the untethered anchor.
                    float lx = Math.min(fish[0] - width / 2f, Settings.WIDTH - width - 12f);
                    lx = Math.max(12f, lx);
                    float ly = fish[1] - side / 2f - 6f;
                    if (ly < 26f) {
                        ly = fish[1] + side / 2f + 30f;
                    }
                    AgentHudRenderer.drawCaption(sb, font, text, lx, ly, labelAlpha);
                }
            }
        } finally {
            if (repaired) {
                sb.flush();
                sb.setTransformMatrix(originalTransform);
            }
            sb.setColor(SAVED_COLOR);
        }
        logClamp(fishCx, fishCy, fish[0], fish[1], clamped);
        logDraw(sb, screen, state, x, y, alpha, side, repaired);
    }

    /**
     * One axis of {@link #clampToWindow}; {@code size} is the window's extent on this axis.
     *
     * @return the value itself when it already fits, {@code half} / {@code size - half} when it does
     *         not, the middle of the window when the window is smaller than the sprite
     */
    static float clampAxis(float value, float size, float half) {
        if (!(size > 0f)) {
            return value;                   // window size not measured yet (still booting)
        }
        float margin = Math.max(0f, half);
        float lo = margin;
        float hi = size - margin;
        if (hi < lo) {
            return size * 0.5f;             // window smaller than the sprite: no fit exists
        }
        if (value < lo) {
            return lo;
        }
        if (value > hi) {
            return hi;
        }
        return value;
    }

    /**
     * Keeps a sprite of half-size {@code half} (plus the edge margin) inside a {@code w x h} window
     * (issue #53).
     *
     * <p>Pure and static on purpose: the fish being half outside the window is a geometry bug that
     * can be pinned without a running game, and the rule is used on every frame, so it must not
     * touch anything but its arguments.</p>
     *
     * <p>Degenerate inputs are defined rather than "whatever the clamp does":</p>
     * <ul>
     *   <li>an unmeasured window ({@code w} or {@code h} ≤ 0, which is what {@link Settings} looks
     *       like while the mod boots) leaves the point alone - the pointer must not be shoved around
     *       by a window size that has not been set yet;</li>
     *   <li>a window smaller than the sprite ({@code 2*half > w}) centres on that axis: no position
     *       can show the whole fish there, and cropped in the middle beats cropped in a corner.</li>
     * </ul>
     *
     * @param x    sprite centre, game coordinates
     * @param y    sprite centre, game coordinates
     * @param w    window width ({@code Settings.WIDTH})
     * @param h    window height ({@code Settings.HEIGHT})
     * @param half half the sprite size plus the margin wanted at each edge
     * @return {@code {x', y'}} - the same point when it fits, the clamped one otherwise
     */
    static float[] clampToWindow(float x, float y, float w, float h, float half) {
        return new float[]{clampAxis(x, w, half), clampAxis(y, h, half)};
    }

    /**
     * Identity test with a float-noise tolerance: the batch transform is only ever meant to be the
     * identity at draw time, so anything else is a leftover from a rotated-text helper.
     */
    public static boolean isIdentity(Matrix4 m) {
        if (m == null) {
            return true;
        }
        float[] v = m.val;
        if (v == null || v.length < 16) {
            return true;
        }
        for (int i = 0; i < 16; i++) {
            float want = (i % 5 == 0) ? 1f : 0f;
            if (Math.abs(v[i] - want) > 1.0e-4f) {
                return false;
            }
        }
        return true;
    }

    /** Best-effort name of the screen the fish is drawn on ("map", "combat", "menu", …). */
    private static String screenName() {
        try {
            com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen screen =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.screen;
            if (screen != null
                    && screen != com.megacrit.cardcrawl.dungeons.AbstractDungeon.CurrentScreen.NONE) {
                return screen.name().toLowerCase(java.util.Locale.ROOT);
            }
            if (com.megacrit.cardcrawl.core.CardCrawlGame.dungeon == null) {
                return "menu";
            }
            com.megacrit.cardcrawl.rooms.AbstractRoom room =
                    com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom();
            if (room == null) {
                return "room";
            }
            return room.phase == com.megacrit.cardcrawl.rooms.AbstractRoom.RoomPhase.COMBAT
                    ? "combat" : "room";
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * Where the fish actually went, in game coordinates and in screenshot coordinates, under which
     * batch state. Issue #44 ("the fish is not on the map") was impossible to tell apart from
     * "the fish is somewhere else" without exactly this line - but it must not become a per-frame
     * heartbeat, so the decision is debounced by {@link #drawLogDecision}.
     */
    private static void logDraw(SpriteBatch sb, String screen, String state, float x, float y,
                                float alpha, float size, boolean repaired) {
        try {
            boolean first = loggedAt == 0L;
            boolean important = first || repaired || !screen.equals(loggedScreen)
                    || !state.equals(loggedState) || Math.abs(alpha - loggedAlpha) > 0.05f
                    || moving != loggedMoving;
            float moved = first ? Float.MAX_VALUE : (float) Math.hypot(x - loggedX, y - loggedY);
            long now = System.currentTimeMillis();
            int decision = drawLogDecision(important, moved, now - loggedAt);
            if (decision == LOG_SUPPRESSED) {
                suppressed++;
                return;
            }
            if (decision == LOG_NEVER) {
                return;
            }
            loggedAt = now;
            loggedScreen = screen;
            loggedState = state;
            loggedX = x;
            loggedY = y;
            loggedAlpha = alpha;
            loggedMoving = moving;
            String quiet = suppressed > 0 ? "（此前抑制 " + suppressed + " 条）" : "";
            suppressed = 0;
            float[] p = sb.getProjectionMatrix() == null ? null : sb.getProjectionMatrix().val;
            int projW = (p == null || p[0] == 0f) ? 0 : Math.round(2f / p[0]);
            int projH = (p == null || p[5] == 0f) ? 0 : Math.round(2f / p[5]);
            spirelink.util.Diag.info("cursor: draw screen=" + screen
                    + " x=" + Math.round(x) + " y=" + Math.round(y)
                    + "（屏幕 y=" + Math.round(Settings.HEIGHT - y) + "）"
                    + " state=" + state + " alpha=" + Math.round(alpha * 100f) / 100f
                    + " size=" + Math.round(size)
                    + " proj=" + projW + "x" + projH
                    + " settings=" + Settings.WIDTH + "x" + Settings.HEIGHT
                    + " transform=" + (repaired ? "非 identity（已临时修正）" : "identity")
                    + quiet);
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    /**
     * Says once per target that the fish had to be moved to stay in the window (issue #53).
     *
     * <p>Deliberately a <i>separate</i> line: the {@code cursor: <action> -> x,y 到位 Nms} line is
     * the contract QA checks (#44) and keeps reporting the real target coordinates, so the clamp is
     * never folded into it.</p>
     */
    private static void logClamp(float fromX, float fromY, float toX, float toY, boolean clamped) {
        if (!clamped) {
            return;
        }
        if (clampReported && targetX == clampLoggedTargetX && targetY == clampLoggedTargetY) {
            return;
        }
        clampReported = true;
        clampLoggedTargetX = targetX;
        clampLoggedTargetY = targetY;
        try {
            spirelink.util.Diag.info("cursor: 目标被夹取到窗口内 ("
                    + Math.round(fromX) + "," + Math.round(fromY) + ") -> ("
                    + Math.round(toX) + "," + Math.round(toY) + ")"
                    + " 目标 " + (hasTarget
                            ? Math.round(targetX) + "," + Math.round(targetY) : "无目标")
                    + " 窗口 " + Settings.WIDTH + "x" + Settings.HEIGHT);
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    /**
     * Pure debounce rule, unit tested because it is the whole difference between a usable trace and
     * a log file nobody can read: a 1 px idle wobble must never write, a real move writes at most
     * once a second (the skipped attempts are counted), and a screen / face / flight / alpha change
     * always writes.
     *
     * @return {@link #LOG_NEVER}, {@link #LOG_YES} or {@link #LOG_SUPPRESSED}
     */
    static int drawLogDecision(boolean important, float movedPx, long sinceLastMs) {
        if (important) {
            return LOG_YES;
        }
        if (!(movedPx > LOG_MOVE_PX)) {
            return LOG_NEVER;
        }
        return sinceLastMs >= LOG_MIN_INTERVAL_MS ? LOG_YES : LOG_SUPPRESSED;
    }

    /** The batch is not in begin() state: whatever ran before us broke it. Say it once per screen. */
    private static void logNotDrawing(String screen) {
        if (screen == null || screen.equals(loggedNotDrawing)) {
            return;
        }
        loggedNotDrawing = screen;
        spirelink.util.Diag.warn("cursor: draw screen=" + screen
                + " 跳过绘制（SpriteBatch 不在 begin 状态：上一屏 end() 后没有再 begin()，鱼画不上去）");
    }

    /** 1x1 white pixel used for the anchor cross (cheaper than a ShapeRenderer). */
    private static Texture dot() {
        if (dotTexture != null || dotTried) {
            return dotTexture;
        }
        dotTried = true;
        try {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(1f, 1f, 1f, 1f);
            pm.fill();
            dotTexture = new Texture(pm);
            pm.dispose();
        } catch (Throwable t) {
            dotTexture = null;
        }
        return dotTexture;
    }

    /** Lazily loads one state sprite; a missing/broken PNG falls back to the normal face. */
    private static Texture texture(String state) {
        Texture cached = TEXTURES.get(state);
        if (cached != null) {
            return cached;
        }
        Texture loaded = load(DIR + state + ".png");
        if (loaded == null && !NORMAL.equals(state)) {
            loaded = texture(NORMAL);
        }
        if (loaded != null) {
            TEXTURES.put(state, loaded);
        }
        return loaded;
    }

    private static Texture load(String path) {
        InputStream in = null;
        try {
            in = AgentCursor.class.getClassLoader().getResourceAsStream(path);
            if (in == null) {
                spirelink.util.Diag.warn("指针贴图缺失: " + path);
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
            Pixmap pixmap = new Pixmap(out.toByteArray(), 0, out.size());
            Texture tex = new Texture(pixmap);
            tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            pixmap.dispose();
            spirelink.util.Diag.info("指针贴图已加载: " + path);
            return tex;
        } catch (Throwable t) {
            spirelink.util.Diag.warn("指针贴图加载失败: " + path + " " + t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // nothing to do
                }
            }
        }
    }
}
