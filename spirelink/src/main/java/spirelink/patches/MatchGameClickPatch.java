package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.events.shrines.GremlinMatchGame;
import com.megacrit.cardcrawl.helpers.input.InputHelper;

/**
 * Flips a card in GremlinMatchGame.
 *
 * <p>Setting {@code hb.hovered} from outside does not survive the frame: the minigame recomputes hover
 * from the real mouse inside its own {@code update()}. Injecting into a Prefix of that method keeps the
 * virtual pointer on the card for the whole frame, which is the only place the minigame can see it.
 */
@SpirePatch(clz = GremlinMatchGame.class, method = "update")
public class MatchGameClickPatch {

    private static volatile int pendingIndex = -1;
    private static volatile int framesLeft;

    private MatchGameClickPatch() {
    }

    /** Ask for exactly one click on the card at {@code index} (one request = one flip). */
    public static void request(int index) {
        pendingIndex = index;
        framesLeft = 1;
    }

    public static void cancel() {
        pendingIndex = -1;
        framesLeft = 0;
    }

    public static boolean pending() {
        return pendingIndex >= 0;
    }

    private static int cardsSize(GremlinMatchGame game) {
        Object group = spirelink.util.FieldKit.fieldOrNull(game, "cards");
        if (group instanceof com.megacrit.cardcrawl.cards.CardGroup) {
            return ((com.megacrit.cardcrawl.cards.CardGroup) group).group.size();
        }
        return 0;
    }

    private static AbstractCard cardAt(GremlinMatchGame game, int index) {
        Object group = spirelink.util.FieldKit.fieldOrNull(game, "cards");
        if (group instanceof com.megacrit.cardcrawl.cards.CardGroup) {
            java.util.List<AbstractCard> cards =
                    ((com.megacrit.cardcrawl.cards.CardGroup) group).group;
            if (index >= 0 && index < cards.size()) {
                return cards.get(index);
            }
        }
        return null;
    }

    @SpirePrefixPatch
    public static void Prefix(GremlinMatchGame __instance) {
        int index = pendingIndex;
        if (index < 0) {
            return;
        }
        try {
            // Only the PLAY screen accepts flips; INTRO / RULE_EXPLANATION ignore clicks, so holding
            // the request there just re-injected the same click every frame.
            Object screen = spirelink.util.FieldKit.fieldOrNull(__instance, "screen");
            if (screen == null || !"PLAY".equals(screen.toString())) {
                return;
            }
            Object chosen = spirelink.util.FieldKit.fieldOrNull(__instance, "chosenCard");
            if (chosen != null && chosen == cardAt(__instance, index)) {
                // That card is already face up; a second click on it does nothing but burn frames.
                cancel();
                return;
            }
            Object group = spirelink.util.FieldKit.fieldOrNull(__instance, "cards");
            if (!(group instanceof com.megacrit.cardcrawl.cards.CardGroup)) {
                cancel();
                return;
            }
            java.util.List<AbstractCard> cards =
                    ((com.megacrit.cardcrawl.cards.CardGroup) group).group;
            if (index >= cards.size() || cards.get(index) == null
                    || cards.get(index).hb == null) {
                cancel();
                return;
            }
            AbstractCard card = cards.get(index);
            // Vanilla only reacts when the hovered card is still face down (isFlipped == true).
            if (!card.isFlipped) {
                cancel();
                return;
            }
            InputHelper.mX = (int) card.hb.cX;
            InputHelper.mY = (int) card.hb.cY;
            card.hb.hovered = true;
            InputHelper.justClickedLeft = true;
            spirelink.util.Diag.info("match-game-click: 注入点击 index=" + index
                    + " id=" + card.cardID);
            // One request = exactly one click.
            cancel();
        } catch (Throwable t) {
            cancel();
        }
    }
}
