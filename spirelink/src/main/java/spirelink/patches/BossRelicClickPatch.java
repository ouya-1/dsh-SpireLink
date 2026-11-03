package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.LineFinder;
import com.evacipated.cardcrawl.modthespire.lib.Matcher;
import com.evacipated.cardcrawl.modthespire.lib.SpireInsertLocator;
import com.evacipated.cardcrawl.modthespire.lib.SpireInsertPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.relics.AbstractRelic;

import javassist.CannotCompileException;
import javassist.CtBehavior;

/**
 * Boss relic click, done the way the game does it.
 *
 * <p>{@code BossRelicSelectScreen.update()} only sees a pick through {@code relic.isObtained}, which
 * {@code AbstractRelic.update()} sets when its hitbox reports a click. Writing {@code hb.hovered} from
 * outside does not survive because {@code AbstractRelic.update()} calls {@code hb.update()} first and
 * that recomputes hover from the real mouse - so the flag has to be forced <em>after</em> that call,
 * inside the same frame. Same technique as CommunicationMod's AbstractRelicUpdatePatch.
 */
@SpirePatch(clz = AbstractRelic.class, method = "update")
public class BossRelicClickPatch {

    /** Relic the next frame should click; cleared as soon as it is applied. */
    public static volatile AbstractRelic hoverRelic;
    public static volatile boolean doHover = false;

    public static void request(AbstractRelic relic) {
        hoverRelic = relic;
        doHover = true;
    }

    public static void cancel() {
        hoverRelic = null;
        doHover = false;
    }

    public static boolean pending() {
        return doHover && hoverRelic != null;
    }

    @SpireInsertPatch(locator = HitboxLocator.class)
    public static void DoHitboxHover(AbstractRelic instance) {
        if (!doHover) {
            return;
        }
        if (hoverRelic == instance) {
            instance.hb.hovered = true;
            instance.hb.clicked = true;
            doHover = false;
            spirelink.util.Diag.info("boss-relic-click: 注入点击 " + instance.relicId);
        } else {
            // Only the target may look hovered, exactly like a real pointer.
            instance.hb.hovered = false;
        }
    }

    private static class HitboxLocator extends SpireInsertLocator {
        @Override
        public int[] Locate(CtBehavior ctMethodToPatch)
                throws CannotCompileException, com.evacipated.cardcrawl.modthespire.patcher.PatchingException {
            Matcher matcher = new Matcher.MethodCallMatcher(Hitbox.class, "update");
            int[] results = LineFinder.findInOrder(ctMethodToPatch, new java.util.ArrayList<Matcher>(),
                    matcher);
            results[0] += 1;
            return results;
        }
    }
}
