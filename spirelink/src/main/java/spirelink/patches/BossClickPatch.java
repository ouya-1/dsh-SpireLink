package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.map.DungeonMap;

/**
 * Enters the boss from the map screen.
 *
 * <p>The boss is not a map node: vanilla draws {@code DungeonMap.bossHb} and evaluates the click in
 * {@code DungeonMap.update()} (DungeonMapScreen only moves the gamepad cursor). A Prefix there is the
 * one place where the virtual pointer survives long enough to be seen.
 */
@SpirePatch(clz = DungeonMap.class, method = "update")
public class BossClickPatch {

    private static volatile boolean pending;
    private static volatile boolean clicked;
    private static volatile int framesLeft;

    private BossClickPatch() {
    }

    /** Ask the next frames to click the boss icon. */
    public static void request() {
        pending = true;
        clicked = false;
        framesLeft = 20;
    }

    public static void clear() {
        pending = false;
        clicked = false;
        framesLeft = 0;
    }

    public static boolean pending() {
        return pending;
    }

    @SpirePrefixPatch
    public static void Prefix(DungeonMap __instance) {
        if (!pending) {
            return;
        }
        try {
            if (__instance == null || __instance.bossHb == null) {
                clear();
                return;
            }
            InputHelper.mX = (int) __instance.bossHb.cX;
            InputHelper.mY = (int) __instance.bossHb.cY;
            __instance.bossHb.hovered = true;
            if (!clicked) {
                InputHelper.justClickedLeft = true;
                clicked = true;
                spirelink.util.Diag.info("boss-click: 点击 Boss 图标");
            }
            if (--framesLeft <= 0) {
                clear();
            }
        } catch (Throwable t) {
            clear();
        }
    }
}
