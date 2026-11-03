package spirelink.mcp;

/**
 * dev_use_card 的结果分类（纯逻辑，不碰游戏类，方便单测）。
 *
 * <ul>
 *   <li>{@code played}：{@code card.use()} 正常返回，且按游戏规则本可打出（cost >= 0）。</li>
 *   <li>{@code blocked_by_game_rule}：游戏规则上不可打出（cost == -2，典型诅咒/状态牌），
 *       仍然调用了 {@code use()}；异常（如果有）写进 reason 不影响分类。</li>
 *   <li>{@code metadata_only}：{@code use()} 抛异常且不属于上面两类 —— 工具上下文限制，
 *       不代表卡牌实现有问题。</li>
 * </ul>
 *
 * <p>{@code ok} 的语义就是「分类是 played 或 blocked_by_game_rule」。
 *
 * <p>C6-02 另加一个正交的 {@code effect} 维度：{@code result} 回答「该不该算通过」，
 * {@code effect} 回答「这一步到底改了什么」。旧实现只回 {@code played} 却说不出效果，
 * QA 看到 {@code played} 而格挡 0→0、powers 空、怪物血量不动 —— 两个问题分开命名后，
 * 「分类是 played」不再暗示「局面上发生过什么」。
 */
final class CardUseClassifier {

    static final String PLAYED = "played";
    static final String BLOCKED = "blocked_by_game_rule";
    static final String METADATA = "metadata_only";

    /** 卡牌通过原版出牌路径排出了动作，游戏会在下一拍执行（真的有效果）。 */
    static final String EFFECT_QUEUED = "queued";
    /** 走完了出牌路径，但这张牌没有排出任何动作（实现里本来就没有可执行效果）。 */
    static final String EFFECT_NONE = "none";
    /** use() 抛异常，半路排出的动作已清空，别让它们在后面的帧里执行。 */
    static final String EFFECT_DROPPED = "dropped";

    /** -2 是游戏里的「不可打出」，诅咒与状态牌都用这个值。 */
    static final int UNPLAYABLE_COST = -2;

    private CardUseClassifier() {
    }

    /** 按游戏规则这张牌能不能打出（cost == -2 不能；-1 是 X 费，能）。 */
    static boolean unplayableByRule(int cost) {
        return cost == UNPLAYABLE_COST;
    }

    /**
     * @param cost   卡牌的 cost（cost 是基础费用，costForTurn 才是本回合的）
     * @param threw  {@code use()} 是否抛了异常
     */
    static String classify(int cost, boolean threw) {
        if (unplayableByRule(cost)) {
            // 规则上就打不出去：即使 use() 抛异常也仍然是「规则拦截」，不是实现问题。
            return BLOCKED;
        }
        return threw ? METADATA : PLAYED;
    }

    /** {@code ok} 的唯一定义：played 或 blocked_by_game_rule。 */
    static boolean ok(String result) {
        return PLAYED.equals(result) || BLOCKED.equals(result);
    }

    /** 响应里的 reason：每种结果都要能独立解释清楚发生了什么。 */
    static String reason(String result, int cost, Throwable failure) {
        return reason(result, cost, -1, failure);
    }

    /**
     * Issue #124 · C15-03: same, but aware of the energy the play actually had.
     *
     * <p>现场：{@code energy=0} 时 {@code dev_use_card} 的 reason 仍写「cost=3 按规则可打出」——它当时只看
     * cost 是不是 -2，完全没看能量。这个工具是<b>强制</b>调 {@code card.use()}（{@code freeToPlayOnce}），
     * 能量不够也照样执行，所以「可打出」是错的；正确说法是「能量不够、被工具绕过了」。</p>
     *
     * @param energy 出牌前的能量；{@code -1} = 读不到（不猜）
     */
    static String reason(String result, int cost, int energy, Throwable failure) {
        if (BLOCKED.equals(result)) {
            String base = "cost=" + cost + " 不可打出（诅咒/状态牌）";
            return failure == null ? base : base + "；use() 抛异常: " + describe(failure);
        }
        if (PLAYED.equals(result)) {
            if (energy >= 0 && cost >= 0 && cost > energy) {
                return "card.use() 正常返回，cost=" + cost + "，但本回合能量只有 " + energy
                        + "：正常路径打不出这张牌，dev_use_card 是强制调用（freeToPlayOnce）绕过了能量检查";
            }
            return "card.use() 正常返回，cost=" + cost
                    + (energy >= 0 ? "（本回合能量 " + energy + "）" : "") + " 按规则可打出";
        }
        return "use() 抛异常（工具上下文限制，不代表卡牌实现有问题）: " + describe(failure)
                + " @ " + firstFrame(failure);
    }

    /**
     * C6-02: 这一步到底改了什么。
     *
     * @param queued 实测的排队动作数（{@code -1} = 读不到，按"不知道"处理）
     * @param threw  {@code use()} 是否抛了异常
     */
    static String effect(int queued, boolean threw) {
        if (threw) {
            return EFFECT_DROPPED;
        }
        return queued > 0 ? EFFECT_QUEUED : EFFECT_NONE;
    }

    /** {@code effect} 的人话解释，追加在 {@code reason} 后面；每种取值都要能独立说明发生了什么。 */
    static String effectNote(String effect, int queued, int dropped) {
        if (EFFECT_QUEUED.equals(effect)) {
            return "已走原版出牌路径（player.useCard），这张牌排出的 " + queued
                    + " 条动作进了游戏动作队列，下一拍真正生效"
                    + "（紧接着的 get_state 可能短暂 NOT_READY）";
        }
        if (EFFECT_NONE.equals(effect)) {
            return "已走原版出牌路径，但这张牌没有排出任何动作"
                    + "（若它在 use() 里直接改了状态，那是同步发生的；否则说明实现没有可执行效果）";
        }
        return "use() 抛异常：半路排出的 " + Math.max(0, queued) + " 条动作已丢弃"
                + (dropped > 0 ? "（清空 " + dropped + " 条）" : "")
                + "，不让半张牌留在动作队列里";
    }

    /** 异常类名 + 消息，一行内。 */
    static String describe(Throwable t) {
        if (t == null) {
            return "";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }

    /** 栈首帧（类名.方法(文件:行)），reason 里用它定位。 */
    static String firstFrame(Throwable t) {
        if (t == null) {
            return "";
        }
        StackTraceElement[] frames = t.getStackTrace();
        return frames == null || frames.length == 0 ? "" : frames[0].toString();
    }
}
