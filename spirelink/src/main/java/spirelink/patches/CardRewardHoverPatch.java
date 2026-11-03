package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import spirelink.actions.RewardPick;

/**
 * The card-reward screen only reacts to a card whose hitbox is hovered, and {@code Hitbox.update}
 * recomputes hover from the mouse every frame. So the hover has to be re-asserted right after the
 * card's own update, for as long as our pick request is alive.
 */
@SpirePatch(clz = AbstractCard.class, method = "update")
public class CardRewardHoverPatch {

    @SpirePostfixPatch
    public static void Postfix(AbstractCard __instance) {
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.CARD_REWARD) {
            return;
        }
        if (__instance.hb == null || __instance.uuid == null) {
            return;
        }
        if (!RewardPick.matches(String.valueOf(__instance.uuid))) {
            return;
        }
        __instance.hb.hovered = true;
    }
}
