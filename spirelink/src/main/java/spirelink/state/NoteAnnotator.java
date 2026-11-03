package spirelink.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;

/** Adds the agent's own note to every object it may recognise again (potion/card/monster/relic). */
final class NoteAnnotator {

    /** Issue #127: explicit delivery state, so "you already saw this" never looks like "no note". */
    static final String STATE_NONE = "none";
    static final String STATE_NEW = "new";
    static final String STATE_SEEN = "seen";

    private NoteAnnotator() {
    }

    static void annotate(JsonObject root) {
        if (root == null) {
            return;
        }
        historySeen = false;
        JsonObject player = object(root, "player");
        annotateAll(array(player, "relics"), "relic", "id", "relicId", "relic_id");
        // Issue #125 (Lead follow-up): `root.potions` is {slot_count, slots}, so `array(root, "potions")`
        // never matched and potion notes could not be read at all. Annotate the real container - and
        // `player.potions` too, which the old code did not touch either.
        JsonObject potions = object(root, "potions");
        annotateAll(array(potions, "slots"), "potion", "id", "potion_id");
        annotateAll(array(root, "potions"), "potion", "id", "potion_id");
        annotateAll(array(player, "potions"), "potion", "id", "potion_id");
        JsonObject combat = object(root, "combat");
        if (combat != null) {
            annotateAll(array(combat, "hand"), "card", "id", "cardID", "card_id");
            annotateAll(array(combat, "draw_pile"), "card", "id", "cardID", "card_id");
            annotateAll(array(combat, "discard_pile"), "card", "id", "cardID", "card_id");
            annotateAll(array(combat, "exhaust_pile"), "card", "id", "cardID", "card_id");
            annotateAll(array(combat, "monsters"), "monster", "id", "monster_id", "name");
        }
        // 0.1.9: 选牌界面和牌堆也要带便签 —— "这张牌我评过"最该出现的两个时刻，
        // 以前只在手牌/牌堆里查得到，card_reward.cards 与 deck.cards 一直是裸的。
        annotateAll(array(object(root, "card_reward"), "cards"), "card", "id", "cardID", "card_id");
        annotateAll(array(object(root, "deck"), "cards"), "card", "id", "cardID", "card_id");
        JsonObject event = object(root, "event");
        if (event != null) {
            addNote(event, "event", stringOf(event, "id"));
        }
    }

    private static void annotateAll(JsonArray array, String kind, String... idFields) {
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            addNote(item, kind, resolveKey(kind, item, idFields));
        }
    }

    /**
     * Issue #125 · S2-16-01: one <em>stable</em> key per entity.
     *
     * <p>The old code took the first non-empty id field blindly, so an entity whose {@code id} is missing
     * on one frame (transition frames) and present on the next would be looked up under the display name
     * once and under the id afterwards - two different store keys for one monster. Now: the first
     * candidate that actually has a note wins, so the lookup lands on the same entry on every frame of
     * the fight; with no note anywhere it falls back to the first non-empty candidate (which then
     * answers {@code note:null} consistently).</p>
     */
    private static String resolveKey(String kind, JsonObject item, String[] idFields) {
        String first = null;
        for (String field : idFields) {
            String candidate = stringOf(item, field);
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            if (first == null) {
                first = candidate;
            }
            if (spirelink.util.Notes.get(kind, candidate) != null) {
                return candidate;
            }
        }
        return first;
    }

    /**
     * Adds the note fields for one entity - <b>always the same key set</b>.
     *
     * <p>Issue #125 · S2-16-01 (the new S2): the old implementation returned early when the note had
     * already been pushed this run, so on the second frame of the same fight {@code note},
     * {@code note_history} and {@code note_updated_at} all vanished while the first frame had them. QA
     * read that as "this monster has no note" - the data had not changed, only our delivery policy.</p>
     *
     * <p>Issue #127 (follow-up): the first fix sent the full text on <em>every</em> frame, which burned
     * ~200 tokens per combat frame for text the agent had already read. Now the key set stays constant
     * and {@code note_state} says which of three things this is:</p>
     * <ul>
     *   <li>{@code "none"} - the store has nothing: {@code note:null}, no other note fields;</li>
     *   <li>{@code "new"} - first delivery of this note version: {@code note} carries the text;</li>
     *   <li>{@code "seen"} - already delivered this run: {@code note:null} but the key/history markers
     *       stay, so "you have seen this" is a fact and not a missing field.</li>
     * </ul>
     *
     * <p>A real note always carries the same four fields:</p>
     * <ul>
     *   <li>{@code note_history: true} - it is the agent's own earlier text, not a live reading (C15-02);</li>
     *   <li>{@code note_updated_at} - when it was written;</li>
     *   <li>{@code note_key} - the exact store key, so {@code note_get {kind, id}} reproduces the text
     *       (QA could not reconcile the first frame's text with notes.json; the key makes it checkable);</li>
     *   <li>{@code note_pushed} - {@code true} when an earlier frame of this run already delivered it, so
     *       "you have seen this before" is a visible fact instead of a missing field.</li>
     * </ul>
     */
    private static void addNote(JsonObject item, String kind, String id) {
        if (item == null) {
            return;
        }
        // No store key at all (empty potion slot, anonymous monster): no note fields, on every frame.
        if (id == null || id.isEmpty()) {
            return;
        }
        String note = spirelink.util.Notes.get(kind, id);
        if (note == null) {
            item.add("note", JsonNull.INSTANCE);
            item.addProperty("note_state", STATE_NONE);
            return;
        }
        long updated = spirelink.util.Notes.updatedAt(kind, id);
        // Issue #127: the text is delivered once per note version, not on every frame. #125 had made it
        // unconditional to stop the fields from vanishing, which cost ~200 tokens per combat frame
        // (56 notes, 42 chars on average). "seen" now says the same thing without re-sending the text.
        boolean first = spirelink.util.NotesDedupe.claim(kind + ":" + id + "@" + updated, note);
        item.add("note", first ? new com.google.gson.JsonPrimitive(note) : JsonNull.INSTANCE);
        item.addProperty("note_state", first ? STATE_NEW : STATE_SEEN);
        item.addProperty("note_history", true);
        if (updated > 0L) {
            item.addProperty("note_updated_at", updated);
        }
        item.addProperty("note_key", kind + ":" + id);
        item.addProperty("note_pushed", !first);
        if (first) {
            historySeen = true;
        }
    }

    /** True when this body carried at least one note (the body-level sentence then explains it once). */
    private static boolean historySeen;

    /**
     * Issue #124 · C15-02: one sentence per body, so the marker is understandable without the docs.
     *
     * <p>Reset by {@link #annotate} at the start of every body: it must describe <em>this</em> payload.</p>
     */
    static void explainHistory(JsonObject root) {
        boolean seen = historySeen;
        historySeen = false;
        if (!seen || root == null) {
            return;
        }
        JsonObject hints = object(root, "hints");
        if (hints == null) {
            hints = new JsonObject();
            root.add("hints", hints);
        }
        hints.addProperty("note_history",
                "note / notes 是历史便签（agent 自己早先写的文字，可能含旧血量等数值），不是当前状态；"
                        + "当前数值一律以同回包的字段为准（怪物看 current_hp，玩家看 player.hp 等）。");
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    private static JsonArray array(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonArray()) {
            return null;
        }
        return parent.getAsJsonArray(key);
    }

    private static String stringOf(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || parent.get(key).isJsonNull()) {
            return null;
        }
        try {
            return parent.get(key).getAsString();
        } catch (Throwable t) {
            return null;
        }
    }
}
