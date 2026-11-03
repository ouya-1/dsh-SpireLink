package spirelink.mcp;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.helpers.CardLibrary;

/**
 * 卡牌 ID 的统一解析，dev_card / dev_use_card 共用。
 *
 * <p>顺序固定：先 {@code CardLibrary.getCard(id)}（游戏里很多卡的 ID 带空格，例如
 * {@code A Thousand Cuts}，脚本却按类名 {@code AThousandCuts} 传），失败再反射
 * {@code com.megacrit.cardcrawl.cards.&lt;pkg&gt;.&lt;类名&gt;} 并 {@code newInstance()}；
 * 全限定类名也走反射。这条回退<b>永远启用</b>，所以 dev_use_card 的 {@code force} 只是
 * 显式声明「允许强制构造」，不改变解析路径，只在响应里回显。
 *
 * <p>还有一类 id 在 cards.json 里但不是卡牌（{@code Soul} / {@code SoulGroup} /
 * {@code DamageInfo} / {@code DescriptionLine} / {@code CardModUNUSED}）：类存在、却没有
 * {@code use()}，见 {@link Hit#problem}。
 */
final class DevCardResolve {

    private DevCardResolve() {
    }

    /** 解析结果：卡牌原型（或「不是卡牌」的原因）+ 来源。 */
    static final class Hit {

        /** 原型；{@code null} 表示解析到了类/表项但构造不出卡牌，原因在 {@link #problem}。 */
        final AbstractCard card;

        /** {@code library} / {@code reflection} / {@code none}，响应里叫 resolved_by。 */
        final String via;

        /** 只有在 CardLibrary 里能查到的卡才算「可获得」；反射出来的特殊卡不是。 */
        final boolean obtainable;

        /** 非空 = 不能构造卡牌的原因（不是卡牌类 / 反射构造失败）。 */
        final String problem;

        Hit(AbstractCard card, String via, boolean obtainable, String problem) {
            this.card = card;
            this.via = via;
            this.obtainable = obtainable;
            this.problem = problem;
        }

        /** 拿一张可以随便折腾的卡：CardLibrary 原型必须复制，反射出来的本来就是新实例。 */
        AbstractCard fresh() {
            if (card == null) {
                throw new IllegalStateException(problem == null ? "不是卡牌" : problem);
            }
            if (!"library".equals(via)) {
                return card;
            }
            return card.makeCopy();
        }
    }

    /** @return 解析结果；id 为空或两种写法都找不到时返回 {@code null}。 */
    static Hit find(String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim();
        if (key.isEmpty()) {
            return null;
        }
        AbstractCard fromLibrary = library(key);
        if (fromLibrary != null) {
            return new Hit(fromLibrary, "library", true, null);
        }
        Class<?> clazz = DevCardTool.find(DevCardTool.BASE, key, AbstractCard.class, DevCardTool.PKGS);
        if (clazz == null) {
            Class<?> notACard = DevCardTool.find(DevCardTool.BASE, key, Object.class, DevCardTool.PKGS);
            if (notACard == null) {
                return null;
            }
            // 类存在但不是 AbstractCard：cards.json 里混进来的数据类，没有 use() 可调。
            return new Hit(null, "none", false,
                    "不是卡牌类（" + notACard.getName() + " 没有 use()，只有元数据）");
        }
        try {
            return new Hit((AbstractCard) clazz.getDeclaredConstructor().newInstance(),
                    "reflection", false, null);
        } catch (Throwable t) {
            spirelink.util.Diag.warn("卡牌反射构造失败: " + key + " " + t);
            return new Hit(null, "reflection", false, "反射构造失败: " + t);
        }
    }

    /** CardLibrary 查表；未初始化 / 怪 id 一律当查不到。 */
    private static AbstractCard library(String id) {
        try {
            return CardLibrary.getCard(id);
        } catch (Throwable t) {
            spirelink.util.Diag.warn("CardLibrary.getCard 失败: " + id + " " + t);
            return null;
        }
    }
}
