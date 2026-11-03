package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton;

/**
 * Clicks an event dialog option the way the game itself does it.
 *
 * <p>{@code LargeDialogOptionButton.update()} sets {@code pressed = hb.hovered && hb.clicked}, and
 * {@code Hitbox.update()} recomputes {@code hovered} from {@code InputHelper.mX/mY} every frame. Just
 * writing {@code hb.clicked} from the executor was therefore wiped before it could be read: image
 * events (GremlinWheelGame) ignored the click, while room events happened to work because
 * {@code RoomEventDialog} reads {@code hb.clicked} directly.
 *
 * <p>A Prefix on the button's own {@code update()} runs before its {@code hb.update()}, so the virtual
 * pointer already sits on the button when hover is recomputed - the real cursor is never moved.
 */
@SpirePatch(clz = LargeDialogOptionButton.class, method = "update")
public class DialogClickPatch {

    /** How many frames the pointer stays parked (hover only after the click lands). */
    private static final int FRAMES = 3;

    private static volatile LargeDialogOptionButton pending;
    private static volatile int framesLeft;
    private static volatile boolean clickDelivered;

    private DialogClickPatch() {
    }

    /** Ask the next frames to click this option. */
    public static void request(LargeDialogOptionButton option) {
        pending = option;
        framesLeft = FRAMES;
        clickDelivered = false;
    }

    /** Drop any pending request. */
    public static void clear() {
        pending = null;
        framesLeft = 0;
        clickDelivered = false;
    }

    /** The option still waiting to be clicked, or null. */
    public static LargeDialogOptionButton pendingOption() {
        return pending;
    }

    @SpirePrefixPatch
    public static void Prefix(LargeDialogOptionButton __instance) {
        LargeDialogOptionButton target = pending;
        if (target == null || target != __instance) {
            return;
        }
        if (target.hb == null) {
            clear();
            return;
        }
        InputHelper.mX = (int) target.hb.cX;
        InputHelper.mY = (int) target.hb.cY;
        target.hb.hovered = true;
        if (!clickDelivered) {
            target.hb.clicked = true;
            clickDelivered = true;
            spirelink.util.Diag.info("dialog-click: 点击选项 msg="
                    + (target.msg == null ? "" : target.msg));
        }
        framesLeft--;
        if (framesLeft <= 0) {
            clear();
        }
    }
}
