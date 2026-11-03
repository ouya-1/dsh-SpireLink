package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import spirelink.SpireLink;

/**
 * Stops the HTTP server when the game disposes (same hook shape as MCPTheSpire, MIT).
 *
 * <p>ModTheSpire discovers this class because it carries {@code @SpirePatch}.
 */
@SpirePatch(clz = CardCrawlGame.class, method = "dispose")
public class CardCrawlGameDisposePatch {

    public static void Prefix(CardCrawlGame __instance) {
        SpireLink.dispose();
    }
}
