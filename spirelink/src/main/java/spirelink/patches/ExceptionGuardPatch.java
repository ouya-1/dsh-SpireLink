package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

/**
 * Last-chance cleanup when the game itself reports an exception.
 *
 * <p>Vanilla's {@code CardCrawlGame.render/update} catch a {@code Throwable}, log it through
 * {@link com.megacrit.cardcrawl.core.ExceptionHandler} and then call {@code Gdx.app.exit()}. ModTheSpire
 * 3.x cannot replace a method body (no Replace API in this build), so the exit cannot be cancelled from
 * here - but this Prefix records our own stack and clears the poisoned action queues, which is what the
 * crash screen is usually caused by, and leaves the game in a state where a restart is not required for
 * diagnosis.
 */
@SpirePatch(clz = com.megacrit.cardcrawl.core.ExceptionHandler.class,
        method = "handleException",
        paramtypez = {java.lang.Exception.class, org.apache.logging.log4j.Logger.class})
public class ExceptionGuardPatch {

    private ExceptionGuardPatch() {
    }

    @SpirePrefixPatch
    public static void Prefix(java.lang.Exception exception) {
        try {
            spirelink.util.Diag.warn("CrashGuard: 游戏自身捕获到异常 " + exception + " | "
                    + spirelink.actions.ActionExecutor.firstFrames(exception, 10));
            spirelink.util.CrashGuard.noteGameCrash(exception);
            if (AbstractDungeon.actionManager != null) {
                AbstractDungeon.actionManager.actions.clear();
                AbstractDungeon.actionManager.preTurnActions.clear();
                AbstractDungeon.actionManager.cardQueue.clear();
                AbstractDungeon.actionManager.currentAction = null;
                AbstractDungeon.actionManager.hasControl = true;
                spirelink.util.Diag.warn("CrashGuard: 已清空动作队列并交还控制权");
            }
        } catch (Throwable ignored) {
            // never break the crash path itself
        }
    }
}
