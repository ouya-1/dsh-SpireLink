package spirelink.actions;

import com.google.gson.JsonObject;

/** One entry of {@code legal_actions.actions} (design 8). */
public final class ActionSpec {

    public final String id;
    public final String type;
    public final String label;
    public final JsonObject payload;

    public ActionSpec(String id, String type, String label, JsonObject payload) {
        this.id = id;
        this.type = type;
        this.label = label;
        this.payload = payload == null ? new JsonObject() : payload;
    }

    /**
     * Stable one-line digest of an action list, used by the state signature so that any change in
     * the legal actions (new option, selected character, reachable node) bumps {@code state_version}.
     */
    public static String digest(java.util.List<ActionSpec> specs) {
        StringBuilder sb = new StringBuilder();
        if (specs == null) {
            return sb.toString();
        }
        for (ActionSpec spec : specs) {
            // Labels are part of the digest: an event whose option text changes ("付钱" -> "继续") is a
            // different decision point even when the payload is identical, and in-flight release and
            // state_version both key off this.
            sb.append(spec.type).append('(').append(spec.payload).append('|')
                    .append(spec.label).append("),");
        }
        return sb.toString();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("type", type);
        json.addProperty("label", label);
        json.add("payload", payload);
        return json;
    }
}
