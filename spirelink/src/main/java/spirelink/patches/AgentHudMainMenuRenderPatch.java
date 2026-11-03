package spirelink.patches;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import spirelink.ui.AgentHudRenderer;

/**
 * Draws the agent HUD on the main menu / mode panel / character select screens.
 *
 * <p>The two patches are mutually exclusive: {@code MainMenuScreen.render} only runs while
 * {@code CardCrawlGame.mode == CHAR_SELECT}, {@link AgentHudRenderPatch} only inside a run, so the
 * panel is never drawn twice for one frame.
 */
@SpirePatch(clz = MainMenuScreen.class, method = "render")
public class AgentHudMainMenuRenderPatch {

    @SpirePostfixPatch
    public static void Postfix(MainMenuScreen __instance, SpriteBatch sb) {
        spirelink.inspect.Inspector.render(sb);
        AgentHudRenderer.renderSafely(sb);
        // The fish pointer is not a run-only thing: the agent also clicks around the menu, and the
        // human should see where it is pointing from the very first screen.
        spirelink.ui.AgentCursor.renderSafely(sb);
    }
}
