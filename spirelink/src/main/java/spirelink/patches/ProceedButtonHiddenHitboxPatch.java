package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.ui.buttons.ProceedButton;
import spirelink.util.Diag;

import java.lang.reflect.Field;

/**
 * Issue #44: a <i>hidden</i> overlay button still reports its hitbox, so anything that points at
 * "the proceed button" points at a place where no button is drawn.
 *
 * <p>Vanilla never re-lays that hitbox out: the constructor moves it once
 * ({@code ProceedButton.<init>} ends with {@code hb.move(SHOW_X, current_y)}), {@code hide()} only
 * sets {@code target_x = HIDE_X} and {@code isHidden = true}, and {@code update()} returns
 * immediately while {@code isHidden} (bytecode offset 0: {@code getfield isHidden; ifne <end>}).
 * A hidden button therefore keeps whatever position it had, and {@code
 * AbstractDungeon.overlayMenu.proceedButton.hb} keeps answering with it.</p>
 *
 * <p>On the map screen the overlay button is hidden (the map owns the bottom of the screen), and
 * {@code proceed} there means "close the map", so the pointer layer asked the button where it is
 * and sent the fish to {@code 1113,213} (720p) - the empty spot below the legend from the issue
 * report. The {@code leave} action (shop) shares that same target and the same problem.</p>
 *
 * <p>Fix, without touching the action layer: while the button is invisible its hitbox is parked
 * far outside the window. The pointer layer already has the rule "a point outside the window means
 * no target" ({@code ActionExecutor.saneValues}, ±64 px), so the fish simply stays on its last real
 * target and the log says why. The real position is put back the moment the button becomes visible
 * again, so the game's own click handling is untouched.</p>
 */
@SpirePatch(clz = ProceedButton.class, method = "update")
public class ProceedButtonHiddenHitboxPatch {

    /** Parked centre: far outside every window, so the ±64 px rule rejects it. */
    public static final float PARKED = -10000f;
    /** Mirror of {@code ActionExecutor.saneValues}: this much slack around the window is allowed. */
    public static final float WINDOW_SLACK = 64f;

    private static Field hiddenField;
    private static Field hitboxField;
    private static boolean fieldsResolved;
    /** Centre to put back when the button shows again; NaN means "nothing parked". */
    private static float savedX = Float.NaN;
    private static float savedY = Float.NaN;

    @SpirePostfixPatch
    public static void Postfix(ProceedButton __instance) {
        sync(__instance);
    }

    /**
     * Keeps the hitbox usable only while the button is visible. Runs once per frame (the overlay
     * updates this button every frame) and does nothing unless the hidden/visible state changed.
     */
    static void sync(ProceedButton button) {
        if (button == null) {
            return;
        }
        try {
            resolveFields();
            if (hitboxField == null) {
                return;
            }
            Hitbox hb = (Hitbox) hitboxField.get(button);
            if (hb == null) {
                return;
            }
            Boolean hidden = hiddenField == null ? null : (Boolean) hiddenField.get(button);
            if (hidden != null && hidden.booleanValue()) {
                park(hb);
            } else {
                restore(hb);
            }
        } catch (Throwable ignored) {
            // decoration only: the overlay's own update must never fail because of us
        }
    }

    /** Moves a hidden button's hitbox out of the window and remembers where it really was. */
    static void park(Hitbox hb) {
        if (hb == null || isParked(hb.cX, hb.cY)) {
            return;
        }
        savedX = hb.cX;
        savedY = hb.cY;
        hb.move(PARKED, PARKED);
        Diag.info("cursor: proceed 按钮已隐藏 → 目标停用（陈旧 hitbox " + Math.round(savedX) + ","
                + Math.round(savedY) + " 不再被指针使用）");
    }

    /** Puts the real centre back as soon as the button is visible again. */
    static void restore(Hitbox hb) {
        if (hb == null || !isParked(hb.cX, hb.cY)
                || Float.isNaN(savedX) || Float.isNaN(savedY)) {
            return;
        }
        hb.move(savedX, savedY);
        Diag.info("cursor: proceed 按钮已显示 → 目标恢复 " + Math.round(savedX) + ","
                + Math.round(savedY));
        savedX = Float.NaN;
        savedY = Float.NaN;
    }

    /** True when this centre is the parking spot rather than a real layout position. */
    public static boolean isParked(float x, float y) {
        return x <= PARKED + 1f && y <= PARKED + 1f;
    }

    /**
     * The action layer's bounds rule, kept here so a test can prove the parked point is rejected:
     * a coordinate this far outside the window is treated as "no target" instead of flying there.
     */
    public static boolean insideWindow(float x, float y, float width, float height) {
        if (Float.isNaN(x) || Float.isNaN(y)) {
            return false;
        }
        return x >= -WINDOW_SLACK && y >= -WINDOW_SLACK
                && x <= width + WINDOW_SLACK && y <= height + WINDOW_SLACK;
    }

    /** Cached reflective handles: this runs every frame, {@code getDeclaredField} would not. */
    private static void resolveFields() {
        if (fieldsResolved) {
            return;
        }
        fieldsResolved = true;
        try {
            hiddenField = ProceedButton.class.getDeclaredField("isHidden");
            hiddenField.setAccessible(true);
        } catch (Throwable t) {
            hiddenField = null;
        }
        try {
            hitboxField = ProceedButton.class.getDeclaredField("hb");
            hitboxField.setAccessible(true);
        } catch (Throwable t) {
            hitboxField = null;
        }
        if (hiddenField == null || hitboxField == null) {
            Diag.warn("cursor: ProceedButton 字段读不到（hidden=" + hiddenField + " hb=" + hitboxField
                    + "），隐藏按钮的指针目标无法停用");
        }
    }
}
