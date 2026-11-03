package spirelink.mcp;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;

/**
 * Test-only: build a card by id and <b>really play it</b> once, through the vanilla play path.
 *
 * <p>Card coverage used to be measured by playing every card in a real combat turn, which needs
 * energy, a full turn cycle and an empty draw pile. This tool asks the narrower question — does the
 * card's implementation throw when it runs? — but it must still do the thing it reports.
 *
 * <p>C6-02: it used to call {@code card.use()} and then <em>drop</em> everything the card queued
 * ({@code ActionExecutor.clearActionQueues()}). Those queued actions <b>are</b> the card's effect:
 * {@code Defend_R} only queues a {@code GainBlockAction}, {@code Inflame} an {@code ApplyPowerAction},
 * {@code Strike_R} a {@code DamageAction}. Dropping them is why the tool answered
 * {@code result:"played"} while block stayed 0, powers stayed empty and the monster kept its HP. The
 * same card through {@code play_card} worked, because that path never dropped anything.
 *
 * <p>So the tool now runs the play the way the game itself does it: {@link AbstractPlayer#useCard}
 * — {@code calculateCardDamage} → {@code card.use()} → queue {@code UseCardAction} → energy / hand
 * bookkeeping — and leaves the queued actions to the game's action manager, which applies them on
 * the following frames. Two dev-only switches keep it usable as a sweep tool:
 * <ul>
 *   <li>{@code freeToPlayOnce} — no energy is spent, otherwise card 4 of 236 would be refused;</li>
 *   <li>{@code purgeOnUse} — the card is a throwaway copy of the prototype, so it vanishes after
 *       the play instead of polluting the discard pile (two hundred phantom cards would end up in
 *       the next shuffle).</li>
 * </ul>
 *
 * <p>When the answer cannot be honest, the tool refuses instead of faking it. Outside combat there
 * is no play context at all: {@code GameActionManager.addToBottom()} only enqueues while the room
 * phase is {@code COMBAT} and drops everything else, so a card "played" on the map or in a shop
 * could never have an effect — that answers {@code NOT_READY} with the reason, which is exactly
 * the case that used to claim {@code result:"played"}. A card whose {@code use()} throws is still
 * classified {@code metadata_only} (the recorded coverage contract) and its half-queued actions are
 * cleaned up — see the warning on {@code ActionExecutor.clearActionQueues()} about actions running
 * out of context.
 *
 * <p>卡牌 ID 三种写法都行：类名（AThousandCuts）、游戏 ID（"A Thousand Cuts"）、全限定类名。
 * 解析先查 CardLibrary（游戏里很多卡的 ID 带空格），失败再反射构造（见 {@link DevCardResolve}）；
 * 这条回退永远启用，{@code force} 只是显式声明「允许强制构造」，不改变解析路径，只在响应里回显。
 *
 * <p>结果分类见 {@link CardUseClassifier}：{@code played} / {@code blocked_by_game_rule} /
 * {@code metadata_only}，{@code ok} 只对前两种为 true；{@code effect} 说这一步到底改了什么
 * （{@code queued} / {@code none} / {@code dropped}），{@code queued_actions} 是实测的排队动作数。
 *
 * <p>cards.json 里还混了几个不是卡牌的 id（Soul / SoulGroup / DamageInfo / DescriptionLine /
 * CardModUNUSED）：没有 {@code use()} 可调，直接回 {@code metadata_only} + 原因。
 */
public final class DevUseCardTool {

    private DevUseCardTool() {
    }

    public static JsonObject use(String id, int targetIndex) {
        return use(id, targetIndex, false);
    }

    /**
     * @param force true = 强制构造（跳过可获得性检查）；回退本来就走反射，所以只作显式声明与回显
     */
    public static JsonObject use(String id, int targetIndex, boolean force) {
        if (!spirelink.util.TestMode.enabled()) {
            return err("DEV_TOOL_DISABLED", null, null);
        }
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null) {
            return err("NOT_READY", "还没有玩家（未进地牢）", null);
        }
        DevCardResolve.Hit resolved = DevCardResolve.find(id);
        if (resolved == null) {
            return err("CARD_NOT_FOUND", "找不到卡牌: " + id, null);
        }
        if (resolved.card == null) {
            // cards.json 里混了几个不是卡牌的数据类（Soul / SoulGroup / DamageInfo …）：
            // 没有 use() 可调，归到 metadata_only，别让它看起来像「卡牌找不到」。
            return metadataOnly(id, resolved, force);
        }
        if (!inCombat()) {
            // C6-02: 战斗外没有任何出牌上下文 —— GameActionManager.addToBottom() 只在
            // RoomPhase.COMBAT 时把动作放进队列，别的房间直接丢弃。旧版本在这里照样回
            // result=played（再自己清一次队列），「报 played、零效果」就是这么来的。宁可明确拒绝。
            return err("NOT_READY", "不在战斗中：dev_use_card 走原版出牌路径"
                    + "（AbstractPlayer.useCard），而游戏只在战斗中执行动作队列"
                    + "（GameActionManager.addToBottom 仅在 COMBAT 房间入队），战斗外打出的牌必定零效果；"
                    + "先 dev_room monster 进战斗再打", null);
        }
        if (spirelink.actions.ActionExecutor.firstAliveMonster() == null) {
            // Several cards assume a monster exists (JustLucky, Reaper): without one their use() NPEs.
            return err("NOT_READY", "没有活怪，先 dev_room monster", null);
        }
        AbstractMonster target = target(targetIndex);
        if (targetIndex >= 0 && target == null) {
            return err("BAD_REQUEST", "target_index 越界或目标已死: " + targetIndex, null);
        }
        AbstractCard card;
        try {
            card = resolved.fresh();
        } catch (Throwable t) {
            return err("CARD_USE_FAILED", "复制卡牌失败: " + t, null);
        }
        int cost = card.cost;
        int energyBefore = energyNow();
        // Count what is already in flight so the reported number is this card's own contribution.
        int pendingBefore = pendingActions();
        Throwable failure = null;
        try {
            // 规则上不可打出的牌（cost=-2）也照打：单测要的是「实现会不会崩」，不是「能不能打出」。
            play(player, card, target);
        } catch (Throwable t) {
            failure = t;
        }
        int pendingAfter = pendingActions();
        int queued = pendingBefore < 0 || pendingAfter < 0
                ? -1 : Math.max(0, pendingAfter - pendingBefore);
        int dropped = 0;
        if (failure != null) {
            // use() 崩在半路：这一轮已经排进去的动作不能再跑到后面的帧里
            // （见 ActionExecutor.clearActionQueues() 里 RemoveAllBlockAction NPE 的记录）。
            dropped = spirelink.actions.ActionExecutor.clearActionQueues();
            if (player.cardInUse == card) {
                player.cardInUse = null;
            }
            spirelink.util.Diag.warn("dev_use_card: " + id + " use() 抛异常，已清空动作队列（" + dropped
                    + " 条）");
        }
        String result = CardUseClassifier.classify(cost, failure != null);
        boolean ok = CardUseClassifier.ok(result);
        String effect = CardUseClassifier.effect(queued, failure != null);
        JsonObject out = new JsonObject();
        out.addProperty("ok", ok);
        out.addProperty("id", id);
        out.addProperty("name", card.name);
        out.addProperty("type", card.type == null ? "" : card.type.name());
        out.addProperty("cost", cost);
        out.addProperty("target", target == null ? "" : target.name);
        out.addProperty("result", result);
        // Issue #124 · C15-03: capture the energy BEFORE the forced play, so the reason can say
        // "energy was 0, the tool bypassed it" instead of claiming the card was playable.
        out.addProperty("reason", CardUseClassifier.reason(result, cost, energyBefore, failure)
                + "；" + CardUseClassifier.effectNote(effect, queued, dropped));
        if (energyBefore >= 0) {
            out.addProperty("energy_before", energyBefore);
        }
        out.addProperty("effect", effect);
        if (queued >= 0) {
            out.addProperty("queued_actions", queued);
        }
        if (dropped != 0) {
            out.addProperty("dropped_actions", dropped);
        }
        out.addProperty("force", force);
        out.addProperty("resolved_by", resolved.via);
        String screen = screenName();
        if (screen != null) {
            out.addProperty("needs_screen", screen);
        }
        if (failure != null) {
            out.addProperty("stack", spirelink.actions.ActionExecutor.firstFrames(failure, 5));
            if (!ok) {
                // metadata_only：工具上下文限制导致的异常，调用方据此判「不是卡牌实现的问题」。
                out.addProperty("error", "CARD_USE_FAILED");
                out.addProperty("message", CardUseClassifier.describe(failure));
            }
            spirelink.util.Diag.warn("dev_use_card: " + id + " " + result + " " + failure + " | "
                    + spirelink.actions.ActionExecutor.firstFrames(failure, 5));
        } else {
            spirelink.util.Diag.info("dev_use_card: " + id + " " + result + " effect=" + effect
                    + " queued=" + queued
                    + (screen == null ? "" : " needs_screen=" + screen));
        }
        return out;
    }

    /**
     * 原版出牌路径：{@code AbstractPlayer.useCard} 就是玩家点出一张手牌时游戏内部做的事 ——
     * {@code calculateCardDamage} → {@code card.use()} → 排队 {@code UseCardAction} → 能量与手牌收尾。
     * 走这条路，卡牌排出的动作会在正常上下文里由 {@code GameActionManager} 执行，效果是真的。
     *
     * <p>两个 dev 口径的开关（卡牌实现本身都不读这两个字段，见 C6-02 的核对）：
     * 不扣能量、打完即消失（临时复制品不进弃牌堆）。
     */
    private static void play(AbstractPlayer player, AbstractCard card, AbstractMonster target) {
        card.freeToPlayOnce = true;
        card.purgeOnUse = true;
        player.useCard(card, target, 0);
    }

    /** 当前房间是不是战斗；读不到就按「不是」处理（不冒进）。 */
    private static boolean inCombat() {
        try {
            AbstractRoom room = AbstractDungeon.getCurrRoom();
            return room != null && room.phase == AbstractRoom.RoomPhase.COMBAT;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 动作队列里现在有多少条；读不到返回 -1（调用方据此不报数字，而不是报 0 骗人）。 */
    private static int pendingActions() {
        try {
            if (AbstractDungeon.actionManager == null
                    || AbstractDungeon.actionManager.actions == null) {
                return -1;
            }
            return AbstractDungeon.actionManager.actions.size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 没有 use() 可调的结果（不是卡牌类 / 反射构造失败），仍然给出 result 与原因。 */
    private static JsonObject metadataOnly(String id, DevCardResolve.Hit resolved, boolean force) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("id", id);
        out.addProperty("result", CardUseClassifier.METADATA);
        out.addProperty("reason", resolved.problem);
        out.addProperty("force", force);
        out.addProperty("resolved_by", resolved.via);
        out.addProperty("error", "CARD_USE_FAILED");
        out.addProperty("message", resolved.problem);
        spirelink.util.Diag.warn("dev_use_card: " + id + " metadata_only: " + resolved.problem);
        return out;
    }

    /** Target for the card: the requested index, else the first living monster. */
    private static AbstractMonster target(int targetIndex) {
        try {
            if (AbstractDungeon.getCurrRoom() == null
                    || AbstractDungeon.getCurrRoom().monsters == null) {
                return null;
            }
            java.util.List<AbstractMonster> monsters =
                    AbstractDungeon.getCurrRoom().monsters.monsters;
            if (targetIndex >= 0) {
                if (targetIndex >= monsters.size()) {
                    return null;
                }
                AbstractMonster monster = monsters.get(targetIndex);
                return monster != null && !monster.isDeadOrEscaped() ? monster : null;
            }
            for (AbstractMonster monster : monsters) {
                if (monster != null && !monster.isDeadOrEscaped() && monster.currentHealth > 0) {
                    return monster;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    /**
     * Issue #124 · C15-03: the player's energy right now ({@code -1} when it cannot be read).
     *
     * <p>Main thread only - this tool always runs there. Used for the reason text, never to decide
     * whether to play: the whole point of {@code dev_use_card} is to call {@code use()} regardless.</p>
     */
    private static int energyNow() {
        try {
            return com.megacrit.cardcrawl.ui.panels.EnergyPanel.getCurrentEnergy();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Name of a screen the card parked us on, or null when the card finished by itself. */
    private static String screenName() {
        try {
            if (AbstractDungeon.screen == null) {
                return null;
            }
            switch (AbstractDungeon.screen) {
                case GRID:
                    return "grid";
                case HAND_SELECT:
                    return "hand_select";
                case CARD_REWARD:
                    return "card_reward";
                case COMBAT_REWARD:
                    return "combat_reward";
                default:
                    return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static JsonObject err(String code, String message, String stack) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", code);
        if (message != null) {
            out.addProperty("message", message);
        }
        if (stack != null) {
            out.addProperty("stack", stack);
        }
        return out;
    }
}
