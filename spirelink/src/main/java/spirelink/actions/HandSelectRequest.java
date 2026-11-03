package spirelink.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** {@code choose_hand_cards} payload: {@code {"uuids": ["...", "..."]}} (1..N cards to select). */
public final class HandSelectRequest {

    public final List<String> cardUuids;

    private HandSelectRequest(List<String> cardUuids) {
        this.cardUuids = cardUuids;
    }

    public static HandSelectRequest parse(JsonObject payload) {
        if (payload == null) {
            throw BadRequestException.badRequest("缺少 payload");
        }
        JsonElement raw = payload.get("uuids");
        if (raw == null || raw.isJsonNull() || !raw.isJsonArray()) {
            throw BadRequestException.badRequest("payload.uuids 必须是数组");
        }
        JsonArray array = raw.getAsJsonArray();
        if (array.size() == 0) {
            throw BadRequestException.badRequest("payload.uuids 不能为空");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isString()) {
                throw BadRequestException.badRequest("payload.uuids 只能包含字符串");
            }
            String uuid = element.getAsString().trim();
            if (uuid.isEmpty()) {
                throw BadRequestException.badRequest("payload.uuids 含空字符串");
            }
            if (!unique.add(uuid)) {
                throw BadRequestException.badRequest("payload.uuids 有重复项: " + uuid);
            }
        }
        return new HandSelectRequest(new ArrayList<>(unique));
    }

    public int count() {
        return cardUuids.size();
    }
}
