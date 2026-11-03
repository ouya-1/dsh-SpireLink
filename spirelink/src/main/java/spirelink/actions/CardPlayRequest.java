package spirelink.actions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * {@code play_card} payload: {@code {"uuid": "<AbstractCard.uuid>", "target_index": 0}}.
 *
 * <p>Pure parsing/validation only (no game classes) so it is unit-tested with {@code mvn test};
 * the executor turns it into a {@code CardQueueItem}.
 */
public final class CardPlayRequest {

    public final String cardUuid;
    /** 0-based index into the alive monsters, or {@code null} when no target was supplied. */
    public final Integer targetIndex;

    private CardPlayRequest(String cardUuid, Integer targetIndex) {
        this.cardUuid = cardUuid;
        this.targetIndex = targetIndex;
    }

    public static CardPlayRequest parse(JsonObject payload) {
        if (payload == null) {
            throw BadRequestException.badRequest("缺少 payload");
        }
        String uuid = string(payload, "uuid");
        if (uuid == null || uuid.isEmpty()) {
            throw BadRequestException.badRequest("payload.uuid 必填（卡牌的 uuid）");
        }
        Integer target = null;
        JsonElement raw = payload.get("target_index");
        if (raw != null && !raw.isJsonNull()) {
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
                throw BadRequestException.badRequest("payload.target_index 必须是整数");
            }
            int value = raw.getAsInt();
            if (value < 0) {
                throw BadRequestException.badRequest("payload.target_index 不能为负");
            }
            target = value;
        }
        return new CardPlayRequest(uuid, target);
    }

    public boolean hasTarget() {
        return targetIndex != null;
    }

    private static String string(JsonObject payload, String key) {
        JsonElement element = payload.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString().trim();
    }
}
