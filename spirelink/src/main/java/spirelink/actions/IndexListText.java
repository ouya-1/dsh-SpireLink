package spirelink.actions;

import java.util.List;

/**
 * One wording for "which values can I name?" — the legal range plus the items themselves
 * (C8-01 / C8-02).
 *
 * <p>{@code take_reward} and {@code take_card} have answered an out-of-range index with the legal
 * range and the entries that can really be named since #101 / C7-04. Two gaps stayed behind:</p>
 * <ul>
 *   <li><b>C8-01</b> — {@code claim_relic} and {@code buy} answered a bare {@code "遗物序号越界: 9"} /
 *       {@code "卡牌序号越界: 9"}: no range, no candidates, so the caller could only guess again;</li>
 *   <li><b>C8-02</b> — a {@code take_reward} with a non-integer / negative / missing index was
 *       answered <i>without</i> the range at all (only the item list was appended), even though the
 *       same action carries the range for an out-of-range integer.</li>
 * </ul>
 *
 * <p>So the two sentences live here once, with the counting unit as a parameter
 * ({@code 条} rewards, {@code 张} cards, {@code 个} relics, {@code 瓶} potions). The per-action
 * classes keep their own names — {@link TakeRewardRequest#rangeText},
 * {@link CardRewardPolicy#rangeText} — and delegate, so every index-addressed rejection reads the
 * same.</p>
 */
public final class IndexListText {

    private IndexListText() {
    }

    /**
     * {@code "合法范围 0..2（共 3 条）"} — the sentence every out-of-range index carries.
     *
     * @param size      how many entries the screen really offers
     * @param unit      the counting unit, e.g. {@code 条} / {@code 张} / {@code 个} / {@code 瓶}
     * @param emptyText the sentence for an empty list, e.g. {@code "这一屏没有奖励（合法范围为空）"}
     */
    public static String range(int size, String unit, String emptyText) {
        if (size <= 0) {
            return emptyText;
        }
        return "合法范围 0.." + (size - 1) + "（共 " + size + " " + unit + "）";
    }

    /**
     * {@code "0=金币(25 金), 1=遗物(小盒子)"} — so the caller can retry with a value that exists
     * without another round trip. Entries are formatted by the caller (this class never touches the
     * game); {@code null}/empty entries are skipped.
     *
     * @param emptyText the sentence for "there is nothing to name"
     */
    public static String list(List<String> entries, String emptyText) {
        if (entries == null || entries.isEmpty()) {
            return emptyText;
        }
        StringBuilder sb = new StringBuilder();
        for (String entry : entries) {
            if (entry == null || entry.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry);
        }
        return sb.length() == 0 ? emptyText : sb.toString();
    }

    /**
     * {@code "0=打击(Strike)"} — one candidate line. Every index-addressed screen (rewards, cards,
     * boss relics, shop shelves) numbers its entries the same way the payload does.
     *
     * @param id a machine id to append in parentheses, or {@code null}/empty for none
     */
    public static String entry(int index, String name, String id) {
        StringBuilder sb = new StringBuilder();
        sb.append(index).append('=').append(name == null ? "" : name);
        if (id != null && !id.isEmpty()) {
            sb.append('(').append(id).append(')');
        }
        return sb.toString();
    }

    /**
     * {@code "0=香蕉 回复 333 生命"} — a candidate that has no machine id (event options, minigame
     * cards): the label alone is what the caller can name.
     */
    public static String entry(int index, String label) {
        return entry(index, label, null);
    }
}
