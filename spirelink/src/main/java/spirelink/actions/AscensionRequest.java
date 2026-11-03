package spirelink.actions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * {@code choose_ascension} payload: {@code {"level": <int>}} (issue #77).
 *
 * <p>{@code level=0} means "ascension off". Pure parsing only — the per-character cap
 * ({@code ActionPlanner.ascensionCap}) is applied by the executor because it needs the selected
 * character.</p>
 *
 * <p>Issue #77: the old code read the field with {@code JsonPrimitive.getAsInt()}, which happily
 * coerces the <em>string</em> {@code "1"} into the integer 1. The request was accepted (and then
 * answered {@code queued}, blocking the next action for a second). The type is now strict: only a
 * JSON number is a level, everything else is {@code BAD_REQUEST}.</p>
 *
 * <p>QA round 6 (#100, the ascension half): the level was only range-checked, so {@code level:3} was
 * applied even though the game — and {@code legal_actions} — only offer the vanilla ladder
 * {@code 0/1/5/10/15/20}. An accepted level that is never advertised is exactly the drift this batch
 * is removing, so the ladder is enforced here, in the same pure parser both the planner and the
 * executor use. {@code level:0} is "ascension off" and stays legal.</p>
 */
public final class AscensionRequest {

    /** Top of the vanilla ascension ladder; a per-character cap may be lower. */
    public static final int MAX_LEVEL = 20;

    /**
     * The only levels vanilla's character-select arrows step through — the same six entries
     * {@code legal_actions} advertises. Anything else is not a level the game can actually show.
     */
    public static final int[] LADDER = {0, 1, 5, 10, 15, 20};

    /** Which payload key the level came from, for the error message. */
    public final String key;
    public final int level;

    private AscensionRequest(String key, int level) {
        this.key = key;
        this.level = level;
    }

    public static AscensionRequest parse(JsonObject payload) {
        if (payload == null) {
            throw BadRequestException.badRequest("缺少 payload");
        }
        String key;
        JsonElement raw;
        if (payload.has("level")) {
            key = "level";
            raw = payload.get("level");
        } else {
            key = "ascension";
            raw = payload.get("ascension");
        }
        if (raw == null || raw.isJsonNull()) {
            throw BadRequestException.badRequest("缺少 level（0 表示关闭升天）");
        }
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
            throw BadRequestException.badRequest("payload." + key + " 必须是整数（收到 "
                    + raw + " 不是 JSON 数字；字符串 \"1\" 这种请写成 1）");
        }
        java.math.BigDecimal decimal;
        try {
            decimal = raw.getAsBigDecimal();
        } catch (RuntimeException e) {
            throw BadRequestException.badRequest("payload." + key + " 必须是整数（收到 " + raw + "）");
        }
        if (decimal.stripTrailingZeros().scale() > 0) {
            // 1.5 / 1.25 are numbers but not levels; Gson's getAsInt() would have truncated them.
            throw BadRequestException.badRequest("payload." + key + " 必须是整数（收到 " + raw + "）");
        }
        int value;
        try {
            value = decimal.intValueExact();
        } catch (ArithmeticException e) {
            throw BadRequestException.badRequest("payload." + key + " 超出范围（收到 " + raw + "）");
        }
        if (value < 0) {
            throw BadRequestException.badRequest("payload." + key + " 不能为负（0 = 关闭升天）");
        }
        if (value > MAX_LEVEL) {
            // Same code the executor used before #77 for level=99: the ladder simply ends at 20.
            throw new BadRequestException(ErrorCodes.ILLEGAL_ACTION,
                    "升天等级最高 " + MAX_LEVEL + "（要的是 " + value + "）");
        }
        if (!isLadderLevel(value)) {
            // ILLEGAL_ACTION, not BAD_REQUEST: 3 is a well-formed number, it is simply not an action
            // the planner ever offers (same code the over-cap case has always used).
            throw new BadRequestException(ErrorCodes.ILLEGAL_ACTION,
                    "升天等级只能是 " + ladderText() + "（收到 " + value
                            + "；0 = 关闭升天）—— 原版的档位梯子只有这几档");
        }
        return new AscensionRequest(key, value);
    }

    /** True when {@code level} is one of the six vanilla ladder steps. */
    public static boolean isLadderLevel(int level) {
        for (int step : LADDER) {
            if (step == level) {
                return true;
            }
        }
        return false;
    }

    /** "0/1/5/10/15/20", for error messages and the planner's own label choice. */
    public static String ladderText() {
        StringBuilder sb = new StringBuilder();
        for (int step : LADDER) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(step);
        }
        return sb.toString();
    }
}