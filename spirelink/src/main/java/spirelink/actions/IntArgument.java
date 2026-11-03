package spirelink.actions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;

/**
 * One strict reader for JSON-integer payload fields (#110 / #111 / C8-01 / C8-02).
 *
 * <p>Both reports have the same root cause: {@code ActionExecutor.num()} answers
 * {@code JsonPrimitive.getAsInt()}, which (a) reads the string {@code "3"} as 3 and (b) <b>truncates
 * a fractional number</b>. So {@code take_card{"index":1.5}} quietly took card 1 (#111), and a
 * malformed {@code choose_map_node} field fell back to {@code -1} — the sentinel the map branch reads
 * as "the Boss entry" — which answered a room-phase NOT_READY sentence instead of a payload error
 * (#110).</p>
 *
 * <p>What "strict" means here, on purpose:</p>
 * <ul>
 *   <li>a JSON string is <b>never</b> an integer ({@code "0"}, {@code "3"} are refused, not parsed);</li>
 *   <li>a fractional number is refused instead of truncated ({@code 1.5});</li>
 *   <li>a written-out integral number is fine ({@code 1.0} loses nothing, so it is not a type
 *       error — the same rule {@code take_reward} has had since #101);</li>
 *   <li>a value that does not fit in an {@code int} is refused instead of wrapping;</li>
 *   <li>"absent" stays distinguishable from "sent wrong": {@link #optional} returns {@code null},
 *       so a caller with an alternative (the {@code uuid} of {@code take_card}) can offer it.</li>
 * </ul>
 *
 * <p>Pure: Gson only, no game classes, so the whole matrix is unit-testable.</p>
 */
public final class IntArgument {

    private IntArgument() {
    }

    /** True when the payload carries the key at all (an explicit {@code null} counts as present). */
    public static boolean has(JsonObject payload, String key) {
        return payload != null && payload.has(key);
    }

    /**
     * The field as a strict integer, or {@code null} when the key is absent or explicitly
     * {@code null} ("not sent" is not a type error).
     *
     * @throws BadRequestException when the field is present but not an integral JSON number
     */
    public static Integer optional(JsonObject payload, String key) {
        if (!has(payload, key)) {
            return null;
        }
        JsonElement raw = payload.get(key);
        if (raw == null || raw.isJsonNull()) {
            return null;
        }
        return Integer.valueOf(parse(key, raw));
    }

    /**
     * The field as a strict integer that must be there.
     *
     * @param missingMessage the whole sentence to answer when the key is absent/null; each action
     *                       spells out the payload it wants, so this class carries no wording of its
     *                       own for that case
     * @throws BadRequestException when it is missing, or present but not an integral JSON number
     */
    public static int required(JsonObject payload, String key, String missingMessage) {
        Integer value = optional(payload, key);
        if (value == null) {
            throw BadRequestException.badRequest(missingMessage);
        }
        return value.intValue();
    }

    /**
     * C8-01: same as {@link #optional}, but a negative value is a payload error too — every index
     * addressed action starts at 0, and a negative one used to fall into a "missing" fallback.
     *
     * @throws BadRequestException when present but ill-typed or negative
     */
    public static Integer optionalNonNegative(JsonObject payload, String key) {
        Integer value = optional(payload, key);
        if (value != null && value.intValue() < 0) {
            throw BadRequestException.badRequest(negativeMessage(key, value.intValue()));
        }
        return value;
    }

    /**
     * The field as a strict non-negative integer that must be there.
     *
     * @throws BadRequestException when missing, ill-typed, or negative
     */
    public static int requiredNonNegative(JsonObject payload, String key, String missingMessage) {
        Integer value = optionalNonNegative(payload, key);
        if (value == null) {
            throw BadRequestException.badRequest(missingMessage);
        }
        return value.intValue();
    }

    /** {@code "payload.index 不能为负（0 起）"} — the suffix {@code take_reward} always had. */
    public static String negativeMessage(String key, int value) {
        return "payload." + key + "=" + value + " 不能为负（0 起）";
    }

    /**
     * Strict integer conversion of one already-present JSON value.
     *
     * @throws BadRequestException for strings, booleans, objects, arrays, fractions and values outside
     *                             the {@code int} range
     */
    public static int parse(String key, JsonElement raw) {
        if (raw == null || raw.isJsonNull()) {
            throw BadRequestException.badRequest("payload." + key + " 不能为 null（要整数）");
        }
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
            // Gson's getAsInt() used to read the string "0" as 0 and accept it.
            throw BadRequestException.badRequest("payload." + key + " 必须是整数（收到 " + raw
                    + " 不是 JSON 数字；字符串 \"0\" 这种请写成 0）");
        }
        BigDecimal decimal;
        try {
            decimal = raw.getAsBigDecimal();
        } catch (RuntimeException e) {
            throw BadRequestException.badRequest("payload." + key + " 必须是整数（收到 " + raw + "）");
        }
        if (decimal.stripTrailingZeros().scale() > 0) {
            // #111: 1.5 used to become 1 through getAsInt(). Refuse it instead of silently taking a
            // different item than the caller named. A written 1.0 is integral (scale 0 after
            // stripTrailingZeros), so it stays accepted.
            throw BadRequestException.badRequest("payload." + key + " 必须是整数（收到 " + raw
                    + " 有小数部分，会被截断成别的序号）");
        }
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException e) {
            throw BadRequestException.badRequest("payload." + key + " 超出范围（收到 " + raw + "）");
        }
    }
}
