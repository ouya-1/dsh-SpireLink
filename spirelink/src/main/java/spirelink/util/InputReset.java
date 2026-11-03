package spirelink.util;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

/**
 * Clears stale UI click state.
 *
 * <p>{@code CancelButton.show()} resets {@code hb.hovered} but NOT {@code hb.clicked}, and
 * {@code CancelButton.update()} closes the current screen when {@code hb.clicked} is set. A stale
 * click therefore kills the next screen opened with a cancel button (that is how {@code open_shop}
 * reported success while {@code AbstractDungeon.screen} fell straight back to NONE).
 */
public final class InputReset {

    private InputReset() {
    }

    public static void clearStaleClicks() {
        try {
            InputHelper.pressedEscape = false;
            InputHelper.justClickedLeft = false;
            InputHelper.justClickedRight = false;
        } catch (Throwable ignored) {
            // input class always exists; best effort
        }
        try {
            if (AbstractDungeon.overlayMenu != null) {
                clearButton(AbstractDungeon.overlayMenu.cancelButton);
                clearButton(AbstractDungeon.overlayMenu.proceedButton);
                clearButton(AbstractDungeon.overlayMenu.endTurnButton);
            }
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    private static void clearButton(Object button) {
        if (button == null) {
            return;
        }
        Object raw = FieldKit.fieldOrNull(button, "hb");
        if (raw instanceof com.megacrit.cardcrawl.helpers.Hitbox) {
            com.megacrit.cardcrawl.helpers.Hitbox hb = (com.megacrit.cardcrawl.helpers.Hitbox) raw;
            hb.clicked = false;
            hb.hovered = false;
            hb.clickStarted = false;
        }
    }
}
