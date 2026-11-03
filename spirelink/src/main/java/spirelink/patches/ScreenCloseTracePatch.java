package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import spirelink.util.Diag;

/**
 * Traces who closes a screen we care about.
 *
 * <p>{@code AbstractDungeon.closeCurrentScreen()} is the only way {@code screen} falls back to NONE;
 * a stale {@code CancelButton.hb.clicked} used to close the shop one frame after we opened it. Logging
 * the closing screen plus a short stack makes that class of bug visible instead of silent.
 */
@SpirePatch(clz = AbstractDungeon.class, method = "closeCurrentScreen")
public class ScreenCloseTracePatch {

    private ScreenCloseTracePatch() {
    }

    @SpirePrefixPatch
    public static void prefix() {
        try {
            AbstractDungeon.CurrentScreen screen = AbstractDungeon.screen;
            if (screen != AbstractDungeon.CurrentScreen.SHOP
                    && screen != AbstractDungeon.CurrentScreen.GRID
                    && screen != AbstractDungeon.CurrentScreen.BOSS_REWARD
                    && screen != AbstractDungeon.CurrentScreen.CARD_REWARD) {
                return;
            }
            StringBuilder sb = new StringBuilder("screen-close: " + screen
                    + " escape=" + com.megacrit.cardcrawl.helpers.input.InputHelper.pressedEscape
                    + " click=" + com.megacrit.cardcrawl.helpers.input.InputHelper.justClickedLeft);
            StackTraceElement[] frames = new Throwable().getStackTrace();
            int shown = 0;
            for (StackTraceElement frame : frames) {
                String cls = frame.getClassName();
                if (cls.startsWith("spirelink.patches.ScreenCloseTracePatch")
                        || cls.startsWith("java.lang.Thread")) {
                    continue;
                }
                sb.append(" | ").append(cls).append('.').append(frame.getMethodName());
                if (++shown >= 5) {
                    break;
                }
            }
            Diag.warn(sb.toString());
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }
}
