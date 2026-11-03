package spirelink.actions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * {@code take_reward} payload: {@code {"index": 0}} (issue #101).
 *
 * <p>QA #101: a missing or out-of-range {@code index} (they sent 42) was silently ignored — the
 * executor fell back to {@code firstTakeableReward()} and claimed the <b>first</b> item, which may
 * be a completely different reward (in their run, a relic). An action that quietly claims something
 * the caller did not name is worse than a rejection: the agent cannot tell it happened.</p>
 *
 * <p>So the index is now mandatory, strictly a JSON integer, and the range is checked against the
 * live reward list with {@link #rangeText}/{@link #listText} in the message, so a caller that got it
 * wrong can retry with a value that exists. {@code slot} stays accepted as an alias (harnesses name
 * this payload either way), but one of the two must be present.</p>
 *
 * <p>Pure: no game classes, so the whole matrix is unit-testable.</p>
 */
public final class TakeRewardRequest {

    /** Which payload key the index came from, for the error message. */
    public final String key;
    public final int index;

    private TakeRewardRequest(String key, int index) {
        this.key = key;
        this.index = index;
    }

    public static TakeRewardRequest parse(JsonObject payload) {
        if (payload == null) {
            throw BadRequestException.badRequest("缺少 payload（要写 {\"index\": <0 起的奖励序号>}）");
        }
        String key;
        JsonElement raw;
        if (payload.has("index")) {
            key = "index";
            raw = payload.get("index");
        } else if (payload.has("slot")) {
            key = "slot";
            raw = payload.get("slot");
        } else {
            throw BadRequestException.badRequest(
                    "缺少 index（payload 要写 {\"index\": <0 起的奖励序号>}，序号见 legal_actions 的"
                            + " take_reward payload）");
        }
        if (raw == null || raw.isJsonNull()) {
            throw BadRequestException.badRequest("payload." + key + " 不能为 null（要 0 起的整数）");
        }
        // C8-02 / #111: the numeric strictness is {@link IntArgument}'s, shared with every other
        // index-addressed action, so "1.5 is silently truncated to 1" cannot come back in one action
        // while the siblings refuse it.
        int value = IntArgument.parse(key, raw);
        if (value < 0) {
            throw BadRequestException.badRequest(IntArgument.negativeMessage(key, value));
        }
        return new TakeRewardRequest(key, value);
    }

    /** True when {@code index} addresses an entry of a reward list of {@code size} items. */
    public static boolean inRange(int index, int size) {
        return index >= 0 && index < size;
    }

    /** "合法范围 0..2（共 3 条）" — the sentence every rejection carries (issue #101 / C8-02). */
    public static String rangeText(int size) {
        return IndexListText.range(size, "条", "这一屏没有奖励（合法范围为空）");
    }

    /** {@code size} for {@link #rangeText}, or {@code 0} when the list is unreadable. */
    public static int sizeOf(List<?> rewards) {
        return rewards == null ? 0 : rewards.size();
    }

    /**
     * "0=金币(25 金), 1=遗物(小盒子)" — so the agent can pick a real index without another round
     * trip. Entries are already formatted by the caller (this class never touches the game).
     */
    public static String listText(List<String> entries) {
        return IndexListText.list(entries, "（这一屏没有可领取的奖励）");
    }
}
