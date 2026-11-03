package spirelink.util;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

/**
 * Virtual pointer for screens that recompute hover from the real mouse position every frame.
 *
 * <p>Deliberately never touches the OS cursor (no Gdx.input.setCursorPosition) - only
 * InputHelper.mX/mY, exactly like the inspector hover patch. A pending card is consumed by
 * GridClickPatch at the very start of GridCardSelectScreen.update(), i.e. before
 * updateCardPositionsAndHoverLogic() can overwrite it.
 */
public final class VirtualMouse {

    /** Give up after this many frames and report, so a miss cannot hang the agent. */
    private static final int MAX_FRAMES = 40;

    private static volatile AbstractCard pending;
    private static volatile int framesLeft;
    private static volatile boolean clickDelivered;

    private VirtualMouse() {
    }

    /** Ask the next frames to point at (and click) this card. */
    public static void clickCard(AbstractCard card) {
        pending = card;
        framesLeft = MAX_FRAMES;
        clickDelivered = false;
    }

    /** Frames already spent on the current request. */
    public static int framesElapsed() {
        return MAX_FRAMES - framesLeft;
    }

    public static AbstractCard pendingCard() {
        return pending;
    }

    public static boolean isClickDelivered() {
        return clickDelivered;
    }

    /**
     * Called by the grid patch after it has written the pointer for this frame.
     *
     * @return the 0-based index of this frame, so the caller can alternate press/release
     */
    public static int bumpFrame() {
        int frame = MAX_FRAMES - framesLeft;
        clickDelivered = true;
        framesLeft--;
        if (framesLeft <= 0) {
            if (pending != null) {
                Diag.warn("虚拟鼠标: " + MAX_FRAMES + " 帧内没能完成点击，放弃 " + pending.cardID);
            }
            pending = null;
            framesLeft = 0;
        }
        return frame;
    }

    public static void clear() {
        pending = null;
        framesLeft = 0;
        clickDelivered = false;
    }

    /** Called once per frame from the main loop: drop a request that never reached the screen. */
    public static void tick() {
        if (pending != null && framesLeft <= 0) {
            pending = null;
        }
    }

    /** Convenience for the patch: put the pointer on the card's hitbox centre. */
    public static void pointAt(AbstractCard card) {
        try {
            InputHelper.mX = (int) card.hb.cX;
            InputHelper.mY = (int) card.hb.cY;
        } catch (Throwable ignored) {
            // best effort
        }
    }
}
