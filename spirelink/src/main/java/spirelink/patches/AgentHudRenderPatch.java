package spirelink.patches;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import spirelink.ui.AgentHudRenderer;

/**
 * Draws the agent HUD inside a run (combat, map, events, Neow, rewards).
 *
 * <p>Nothing here depends on window focus, mouse position or screenshots: the panel is pure
 * game-side drawing, so the mod keeps working while the game window is in the background.
 */
@SpirePatch(clz = AbstractDungeon.class, method = "render")
public class AgentHudRenderPatch {

    @SpirePostfixPatch
    public static void Postfix(AbstractDungeon __instance, SpriteBatch sb) {
        spirelink.inspect.Inspector.render(sb);
        AgentHudRenderer.renderSafely(sb);
        // The fish pointer is independent of the text HUD: it is the "where the agent is" cue.
        spirelink.ui.AgentCursor.renderSafely(sb);
    }
}
