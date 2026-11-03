package spirelink.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.megacrit.cardcrawl.actions.GameActionManager;

/**
 * Keeps a null entry out of the action queues.
 *
 * <p>{@code GameActionManager.getNextAction()} does {@code actions.get(0)} and dereferences it, so a
 * single null action (an event that queued an action built from a destroyed object, a reward that was
 * cleared underneath it) throws inside {@code CardCrawlGame.render} and puts the whole game into its
 * crash screen - fatal for an automation run. Dropping the nulls keeps the run alive.
 */
public final class ActionQueueGuardPatch {

    private static volatile long lastLogAt;

    private ActionQueueGuardPatch() {
    }

    static void purge(GameActionManager manager) {
        if (manager == null) {
            return;
        }
        int dropped = 0;
        dropped += purgeList(manager.actions);
        dropped += purgeList(manager.preTurnActions);
        dropped += purgeCardQueue(manager);
        if (dropped > 0) {
            long now = System.currentTimeMillis();
            if (now - lastLogAt > 1000L) {
                lastLogAt = now;
                spirelink.util.Diag.warn("CrashGuard: 动作队列里有 " + dropped
                        + " 个 null，已清除（actions=" + size(manager.actions)
                        + " preTurn=" + size(manager.preTurnActions) + "）");
            }
        }
    }

    private static int purgeList(java.util.List<?> list) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int dropped = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) == null) {
                list.remove(i);
                dropped++;
            }
        }
        return dropped;
    }

    private static int purgeCardQueue(GameActionManager manager) {
        java.util.List<?> queue = manager.cardQueue;
        if (queue == null || queue.isEmpty()) {
            return 0;
        }
        int dropped = 0;
        for (int i = queue.size() - 1; i >= 0; i--) {
            Object item = queue.get(i);
            if (item == null) {
                queue.remove(i);
                dropped++;
            }
        }
        return dropped;
    }

    private static int size(java.util.List<?> list) {
        return list == null ? -1 : list.size();
    }

    /** Runs before the action manager advances, i.e. before getNextAction() dereferences actions[0]. */
    @SpirePatch(clz = GameActionManager.class, method = "update")
    public static class OnUpdate {
        @SpirePrefixPatch
        public static void Prefix(GameActionManager __instance) {
            try {
                purge(__instance);
            } catch (Throwable t) {
                spirelink.util.Diag.warn("CrashGuard: 清理动作队列失败: " + t);
            }
        }
    }

    @SpirePatch(clz = GameActionManager.class, method = "getNextAction")
    public static class OnGetNextAction {
        @SpirePrefixPatch
        public static void Prefix(GameActionManager __instance) {
            try {
                purge(__instance);
            } catch (Throwable t) {
                spirelink.util.Diag.warn("CrashGuard: 清理动作队列失败: " + t);
            }
        }
    }
}
