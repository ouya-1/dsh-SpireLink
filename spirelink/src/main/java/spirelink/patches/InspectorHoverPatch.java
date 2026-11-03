package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import spirelink.inspect.Inspector;

/**
 * After the engine reads the real pointer, overwrite the coordinates with the inspected card's
 * hitbox centre. Everything downstream (hover detection, card lift, keyword tips) then runs on the
 * engine's own path, and the next frame simply re-reads the real pointer when the gaze expires.
 */
@SpirePatch(clz = InputHelper.class, method = "updateFirst")
public class InspectorHoverPatch {

    @SpirePostfixPatch
    public static void Postfix() {
        Inspector.applyHover();
    }
}
