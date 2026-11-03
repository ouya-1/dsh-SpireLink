package spirelink.util;

import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.vfx.AbstractGameEffect;

/**
 * Guards the dev teleport tools against states that are known to crash the game.
 *
 * <p>Two independent defences, both needed:
 * <ol>
 *   <li>BattleStartEffect check across effectList, effectsQueue and topLevelEffectsQueue — the
 *       effect is queued a frame before it lands in effectList, so scanning only effectList misses</li>
 *       the exact window that crashed.</li>
 *   <li>A hard cooldown since the last teleport, because the effect may not even have been created
 *       yet on the frame right after a room swap.</li>
 * </ol>
 * Busy-looking states (map animations, ambient effects) are explicitly allowed: an over-strict
 * guard made the tools unusable.
 */
public final class RoomGuard {

    /** Minimum gap between two teleports; must be written *before* the switch happens. */
    private static final long COOLDOWN_MS = 2500L;
    private static volatile long lastSwitchMs;

    private RoomGuard() {
    }

    /** Records that a teleport is about to happen. Call this immediately before switching. */
    public static void noteSwitch() {
        lastSwitchMs = System.currentTimeMillis();
    }

    /**
     * Empties the action queue before a room swap.
     *
     * <p>A teleport replaces {@code currMapNode.room}; any action queued for the *old* room (the map
     * click that started the transition, a reward pop-out, …) can never complete afterwards, so the
     * queue stays non-empty forever and the state machine reports {@code actions_running} until the
     * game is restarted. Clearing first is what makes {@code dev_encounter} usable in a long run.
     */
    public static void clearActions() {
        try {
            GameActionManager am = AbstractDungeon.actionManager;
            if (am == null) {
                return;
            }
            am.clear();
            if (am.actions != null) {
                am.actions.clear();
            }
            if (am.preTurnActions != null) {
                am.preTurnActions.clear();
            }
            am.currentAction = null;
            am.previousAction = null;
            am.phase = GameActionManager.Phase.WAITING_ON_USER;
            // Effects queued for the old room fire on the new one one or two frames later; a stale
            // BattleStartEffect calls showIntent() on monsters that are not initialised yet and dies
            // with an NPE inside createIntent(), which kills the render loop.
            int purged = purgeBattleStartEffects();
            Diag.info("dev: 已清空 GameActionManager（actions=0，phase=WAITING_ON_USER，"
                    + "清掉开战特效 " + purged + " 个）");
        } catch (Throwable t) {
            Diag.warn("dev: 清空动作队列失败: " + t);
        }
    }

    /**
     * Null when it is safe to teleport, otherwise a short human readable reason.
     *
     * <p><b>This method is intentionally not side-effect free — that is a deliberate self-heal, not
     * an oversight.</b> While it inspects the action queue it feeds the head action to
     * {@link #noteStuckAction}, which watches one action instance: once the <em>same</em> instance has
     * blocked teleports for {@link #STUCK_ACTION_MS} it calls {@link #forceClearActions} and drops it
     * plus everything queued behind it.</p>
     *
     * <p>Why it has to live here: actions such as {@code ExhaustAction} can linger in
     * {@code GameActionManager} forever inside an event, which used to leave every {@code dev_*}
     * tool answering NOT_READY until the game was restarted — a dead end with no way out from the
     * MCP side. Clearing the queue from the guard is what makes those tools usable again.</p>
     *
     * <p>Consequence to keep in mind when reasoning about "被拒 = 零副作用" (issue #64): a
     * <em>rejected</em> dev call may still have cleared a stuck action queue. The room, the screen,
     * {@code nextRoom} and the monster/boss pools are never touched by this method — those are the
     * fields the dev tools roll back, and those are the fields the vanilla crashes came from.
     * If a future change ever needs the guard to be read-only, split it into a pure
     * {@code busyReason()} plus an explicit {@code recover()} call instead of removing the healing —
     * the hang it fixes is real.</p>
     */
    public static String busyReason() {
        try {
            long dt = System.currentTimeMillis() - lastSwitchMs;
            if (dt < COOLDOWN_MS) {
                return "刚切过房间，等特效结束（还需 " + (COOLDOWN_MS - dt) + "ms）";
            }
            if (AbstractDungeon.isFadingOut || AbstractDungeon.isFadingIn) {
                return "正在过渡（淡入淡出）";
            }
            GameActionManager am = AbstractDungeon.actionManager;
            if (am != null) {
                if (am.currentAction != null) {
                    String stuck = noteStuckAction(am.currentAction);
                    if (stuck == null) {
                        return "动作正在执行: " + am.currentAction;
                    }
                    return stuck;
                }
                if (am.actions != null && !am.actions.isEmpty()) {
                    String stuck = noteStuckAction(am.actions.get(0));
                    if (stuck == null) {
                        return "动作队列还有 " + am.actions.size() + " 项";
                    }
                    return stuck;
                }
                clearStuckWatch();
                AbstractRoom room = AbstractDungeon.getCurrRoom();
                if (room != null && room.phase == AbstractRoom.RoomPhase.COMBAT
                        && am.phase != GameActionManager.Phase.WAITING_ON_USER) {
                    return "战斗中非等待输入: " + am.phase;
                }
            }
            if (hasBattleStartEffect()) {
                return "开战特效播放中";
            }
            return null;
        } catch (Throwable t) {
            return "状态读取失败: " + t;
        }
    }

    /** How long the same action instance may block a teleport before it is treated as stuck. */
    private static final long STUCK_ACTION_MS = 5000L;

    private static volatile Object watchedAction;
    private static volatile long watchedSince;

    /**
     * Actions such as ExhaustAction can linger in the manager forever inside an event, which used to
     * leave {@code dev_event} permanently refusing and forced a game restart. Once the same instance
     * has blocked us for {@link #STUCK_ACTION_MS} we clear the queue and let the caller through.
     *
     * @return null when the caller may proceed, otherwise a reason still to report
     */
    private static String noteStuckAction(Object action) {
        if (action == null) {
            clearStuckWatch();
            return null;
        }
        if (watchedAction != action) {
            watchedAction = action;
            watchedSince = System.currentTimeMillis();
            return null;
        }
        long forMs = System.currentTimeMillis() - watchedSince;
        if (forMs < STUCK_ACTION_MS) {
            return null;
        }
        watchedAction = null;
        watchedSince = 0L;
        forceClearActions(action, forMs);
        return null;
    }

    private static void clearStuckWatch() {
        watchedAction = null;
        watchedSince = 0L;
    }

    /** Drops the stuck action and everything queued behind it. */
    private static void forceClearActions(Object stuck, long forMs) {
        try {
            GameActionManager am = AbstractDungeon.actionManager;
            if (am != null) {
                am.currentAction = null;
                if (am.actions != null) {
                    am.actions.clear();
                }
                if (am.preTurnActions != null) {
                    am.preTurnActions.clear();
                }
                if (am.cardQueue != null) {
                    am.cardQueue.clear();
                }
            }
            Diag.warn("滞留动作已清除（持续 " + forMs + "ms）: " + stuck);
        } catch (Throwable t) {
            Diag.warn("清除滞留动作失败: " + t);
        }
    }

    /** Drops any running/queued action unconditionally (recovery path for the dev tools). */
    public static void forceClear() {
        forceClearActions("force", 0L);
        clearStuckWatch();
    }

    /** BattleStartEffect travels effectList -> effectsQueue -> topLevelEffectsQueue between frames. */
    /** Removes stale {@code BattleStartEffect}s from every effect queue; returns how many. */
    private static int purgeBattleStartEffects() {
        int removed = 0;
        try {
            removed += purge(AbstractDungeon.effectList);
            removed += purge(AbstractDungeon.effectsQueue);
            removed += purge(AbstractDungeon.topLevelEffectsQueue);
            removed += purge(AbstractDungeon.topLevelEffects);
        } catch (Throwable t) {
            Diag.warn("dev: 清理开战特效失败: " + t);
        }
        return removed;
    }

    private static int purge(java.util.ArrayList<com.megacrit.cardcrawl.vfx.AbstractGameEffect> list) {
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int before = list.size();
        java.util.Iterator<com.megacrit.cardcrawl.vfx.AbstractGameEffect> it = list.iterator();
        while (it.hasNext()) {
            com.megacrit.cardcrawl.vfx.AbstractGameEffect effect = it.next();
            if (effect != null
                    && "BattleStartEffect".equals(effect.getClass().getSimpleName())) {
                it.remove();
            }
        }
        return before - list.size();
    }

    private static boolean hasBattleStartEffect() {
        return scan(AbstractDungeon.effectList)
                || scan(AbstractDungeon.effectsQueue)
                || scan(AbstractDungeon.topLevelEffectsQueue)
                || scan(AbstractDungeon.topLevelEffects);
    }

    private static boolean scan(java.util.List<AbstractGameEffect> effects) {
        if (effects == null) {
            return false;
        }
        for (AbstractGameEffect effect : effects) {
            if (effect instanceof com.megacrit.cardcrawl.vfx.combat.BattleStartEffect) {
                return true;
            }
        }
        return false;
    }

    /** Same as {@link #busyReason()}; kept for callers that read better that way. */
    public static String busyReasonOrNull() {
        return busyReason();
    }
}
