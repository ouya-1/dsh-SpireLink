package spirelink.actions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** {@code use_potion} / {@code discard_potion} payload: {@code {"slot": 0, "target_index": 1}}. */
public final class PotionRequest {

    public final int slot;
    public final Integer targetIndex;

    private PotionRequest(int slot, Integer targetIndex) {
        this.slot = slot;
        this.targetIndex = targetIndex;
    }

    public static PotionRequest parse(JsonObject payload) {
        if (payload == null) {
            throw BadRequestException.badRequest("缺少 payload");
        }
        JsonElement rawSlot = payload.get("slot");
        if (rawSlot == null || rawSlot.isJsonNull() || !rawSlot.isJsonPrimitive()
                || !rawSlot.getAsJsonPrimitive().isNumber()) {
            throw BadRequestException.badRequest("payload.slot 必填（0 起的药水槽位）");
        }
        int slot = rawSlot.getAsInt();
        if (slot < 0) {
            throw BadRequestException.badRequest("payload.slot 不能为负");
        }
        Integer target = null;
        // Both names are accepted: some callers say "target", the planner emits "target_index".
        JsonElement rawTarget = payload.has("target_index")
                ? payload.get("target_index") : payload.get("target");
        if (rawTarget != null && !rawTarget.isJsonNull()) {
            if (!rawTarget.isJsonPrimitive() || !rawTarget.getAsJsonPrimitive().isNumber()) {
                throw BadRequestException.badRequest("payload.target_index 必须是整数");
            }
            int value = rawTarget.getAsInt();
            if (value < 0) {
                throw BadRequestException.badRequest("payload.target_index 不能为负");
            }
            target = value;
        }
        return new PotionRequest(slot, target);
    }

    public boolean hasTarget() {
        return targetIndex != null;
    }
}
