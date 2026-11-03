package spirelink.actions;

import java.util.List;

/**
 * C7-01: <b>can the card-reward screen be skipped right now, and how does a rejection talk about the
 * card list?</b> — decided in exactly one place, for the planner and the executor both.
 *
 * <p>The report: the agent fired {@code proceed} on {@code card_reward} (executable, never
 * advertised), which ran our {@code skip_card}, which logged</p>
 * <pre>
 *   WARN skip_card: 屏幕没关（skippedCards 无效），已手动 closeCurrentScreen()
 *   WARN skip_card: 兜底 takeReward() 已调用
 * </pre>
 * <p>Checked against the shipped jar with {@code javap -c desktop-1.0.jar}:</p>
 * <ul>
 *   <li>{@code CardRewardScreen.skippedCards()} is <b>only</b> {@code recordMetrics("SKIP")} — it
 *       records the metrics line and closes nothing. That is why the old "call skippedCards() and
 *       hope the screen goes away" implementation always fell through to the manual close.</li>
 *   <li>The real skip is the button: {@code SkipCardButton.update()} marks the hitbox clicked and
 *       then calls {@code AbstractDungeon.closeCurrentScreen()}. So the native path is
 *       <i>skippedCards() + closeCurrentScreen()</i>, which is exactly what
 *       {@code ActionExecutor#skipCard()} now does.</li>
 *   <li>The screen decides whether a skip exists with the skip button itself: {@code open()} shows
 *       it, {@code draftOpen()} / {@code chooseOneOpen()} / {@code discoveryOpen()} hide it
 *       ({@code skippable = false}), while {@code codexOpen()} / {@code carveRealityOpen()} /
 *       {@code foreignInfluenceOpen()} show it again. The private {@code skippable} flag goes stale
 *       across those flavours (it is only written by {@code open}/{@code customCombatOpen}/
 *       {@code draftOpen}/{@code chooseOneOpen}), so the button's own visibility — the thing a human
 *       clicks — is the honest predicate.</li>
 * </ul>
 *
 * <p>Pure: no game classes. The caller reads the button state and hands it in as a tri-state.</p>
 */
public final class CardRewardPolicy {

    private CardRewardPolicy() {
    }

    /**
     * @param hasRewardGroup    the screen really holds a card reward ({@code screen.rewardGroup != null})
     * @param skipButtonVisible {@code !SkipCardButton.isHidden}; {@code null} when the button cannot be
     *                          read at all
     * @return true when {@code skip_card} may be advertised and executed
     */
    public static boolean skipAvailable(boolean hasRewardGroup, Boolean skipButtonVisible) {
        if (!hasRewardGroup) {
            return false;
        }
        // Fail open: an unreadable button must not take the exit away (the old planner always
        // advertised skip for exactly that reason - a card reward with no exit deadlocked a run).
        return skipButtonVisible == null || skipButtonVisible.booleanValue();
    }

    /** Why {@code skip_card} was refused: this reward flavour has no skip button in vanilla. */
    public static String notSkippableMessage() {
        return "这个选牌界面原版没有跳过按钮（draft / choose-one / 战斗内发现类），"
                + "不能 skip_card；请 take_card 选一张";
    }

    /**
     * "合法范围 0..2（共 3 张）" — the sentence every out-of-range {@code take_card} carries
     * (C7-04). The wording itself lives in {@link IndexListText}, the same one {@code take_reward},
     * {@code claim_relic} and {@code buy} answer with (C8-01 / C8-02).
     */
    public static String rangeText(int size) {
        return IndexListText.range(size, "张", "这一屏没有可拿的牌（合法范围为空）");
    }

    /**
     * "0=打击(Strike), 1=防御(Defend)" — the cards the caller may actually name, so a rejected
     * {@code take_card} answers with the same shape {@code take_reward} does (C7-04).
     */
    public static String cardsText(List<String> entries) {
        return IndexListText.list(entries, "（这一屏没有可拿的牌）");
    }
}
