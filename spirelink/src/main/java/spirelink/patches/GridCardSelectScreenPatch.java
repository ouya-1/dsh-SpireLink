package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;

/**
 * Grid selection the way CommunicationMod does it: no input simulation at all.
 *
 * <p>{@code update()} first calls {@code updateCardPositionsAndHoverLogic()} (which recomputes and
 * clears hoveredCard), then immediately checks {@code hoveredCard.hb.clicked} to select. Patching the
 * helper's Postfix therefore lands in exactly the right window: we write hoveredCard + clicked
 * right after the recompute and right before the check.
 */
@SpirePatch(clz = GridCardSelectScreen.class, method = "updateCardPositionsAndHoverLogic")
public class GridCardSelectScreenPatch {

    /** Card the next frame should select. */
    public static AbstractCard hoverCard;
    /** One-shot request flag; the Postfix clears it. */
    public static boolean replaceHoverCard;

    @SpirePostfixPatch
    public static void Postfix(GridCardSelectScreen __instance) {
        if (!replaceHoverCard || hoverCard == null) {
            return;
        }
        // Diagnostic: tells us whether MTS called us at all, and what kind of grid this is.
        spirelink.util.Diag.info("grid-postfix: 命中 hoveredCard=" + hoverCard.cardID
                + " forUpgrade=" + spirelink.util.FieldKit.fieldOrNull(__instance, "forUpgrade")
                + " confirmScreenUp=" + spirelink.util.FieldKit.fieldOrNull(__instance, "confirmScreenUp")
                + " isJustForConfirming=" + spirelink.util.FieldKit.fieldOrNull(__instance, "isJustForConfirming")
                + " targetGroup=" + (__instance.targetGroup == null ? -1 : __instance.targetGroup.size())
                + " numCards=" + spirelink.util.FieldKit.fieldOrNull(__instance, "numCards")
                + " selectedCards=" + (__instance.selectedCards == null ? -1 : __instance.selectedCards.size()));
        basemod.ReflectionHacks.setPrivate(__instance, GridCardSelectScreen.class,
                "hoveredCard", hoverCard);
        hoverCard.hb.hovered = true;
        hoverCard.hb.clicked = true;
        replaceHoverCard = false;
    }

    /** Requests that {@code card} be selected on the next frame. */
    public static void request(AbstractCard card) {
        hoverCard = card;
        replaceHoverCard = card != null;
    }

    /** Drops any pending request; returns true when something was pending. */
    public static boolean clear() {
        boolean was = replaceHoverCard;
        replaceHoverCard = false;
        hoverCard = null;
        return was;
    }
}
