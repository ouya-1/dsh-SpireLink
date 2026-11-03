package spirelink.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the short Chinese sentence returned as {@code log} on {@code outcome=applied}
 * (design 5 / §13). Pure JSON diffing, so it is unit-tested without a game process.
 */
public final class ActionLog {

    private ActionLog() {
    }

    public static String describe(String type, JsonObject payload, JsonObject pre, JsonObject post) {
        String verb = verb(type);
        if (verb == null) {
            return null;
        }
        if (pre == null || post == null) {
            return verb;
        }
        List<String> parts = new ArrayList<>();
        addDelta(parts, "能量", pre, post, "energy");
        addDelta(parts, "手牌", pre, post, "hand");
        addDelta(parts, "格挡", pre, post, "block");
        addDelta(parts, "HP", pre, post, "hp");
        addDelta(parts, "回合", pre, post, "round");
        addMonsterDeltas(parts, pre, post);

        String target = targetLabel(payload, pre, post);
        StringBuilder sb = new StringBuilder(verb);
        if (target != null && actionHasTarget(type)) {
            sb.append(" → ").append(target);
        }
        if (parts.isEmpty()) {
            return sb.toString();
        }
        sb.append('：');
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append('，');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String verb(String type) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case ActionTypes.PLAY_CARD:
                return "出牌";
            case ActionTypes.END_TURN:
                return "结束回合";
            case ActionTypes.USE_POTION:
                return "使用药水";
            case ActionTypes.DISCARD_POTION:
                return "丢弃药水";
            case ActionTypes.CHOOSE_HAND_CARDS:
                return "选牌";
            case ActionTypes.CONFIRM_HAND_SELECT:
                return "确认选牌";
            case ActionTypes.CANCEL_HAND_SELECT:
                return "取消选牌";
            case ActionTypes.TAKE_REWARD:
                return "领取奖励";
            case ActionTypes.PROCEED:
                return "继续";
            default:
                return null;
        }
    }

    private static boolean actionHasTarget(String type) {
        return ActionTypes.PLAY_CARD.equals(type) || ActionTypes.USE_POTION.equals(type);
    }

    private static void addDelta(List<String> parts, String label, JsonObject pre, JsonObject post,
                                 String key) {
        Integer before = intOf(pre, key);
        Integer after = intOf(post, key);
        if (before == null || after == null || before.equals(after)) {
            return;
        }
        parts.add(label + " " + before + "→" + after);
    }

    private static void addMonsterDeltas(List<String> parts, JsonObject pre, JsonObject post) {
        Map<Integer, JsonObject> before = monsters(pre);
        for (Map.Entry<Integer, JsonObject> entry : monsters(post).entrySet()) {
            JsonObject old = before.get(entry.getKey());
            if (old == null) {
                continue;
            }
            Integer oldHp = intOf(old, "hp");
            Integer newHp = intOf(entry.getValue(), "hp");
            if (oldHp == null || newHp == null || oldHp.equals(newHp)) {
                continue;
            }
            String name = stringOf(entry.getValue(), "name");
            String label = "第" + (entry.getKey() + 1) + "只" + (name == null ? "" : "(" + name + ")");
            if (newHp <= 0 && oldHp > 0) {
                parts.add(label + " 击杀");
            } else {
                parts.add(label + " " + oldHp + "→" + newHp);
            }
        }
    }

    private static String targetLabel(JsonObject payload, JsonObject pre, JsonObject post) {
        Integer index = payload == null ? null : intOf(payload, "target_index");
        if (index == null) {
            return null;
        }
        JsonObject monster = monsters(post).get(index);
        if (monster == null) {
            monster = monsters(pre).get(index);
        }
        if (monster == null) {
            return "第" + (index + 1) + "只";
        }
        String name = stringOf(monster, "name");
        return "第" + (index + 1) + "只" + (name == null ? "" : "(" + name + ")");
    }

    private static Map<Integer, JsonObject> monsters(JsonObject json) {
        Map<Integer, JsonObject> out = new LinkedHashMap<>();
        if (json == null || !json.has("monsters") || !json.get("monsters").isJsonArray()) {
            return out;
        }
        JsonArray array = json.getAsJsonArray("monsters");
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject monster = element.getAsJsonObject();
            Integer index = intOf(monster, "index");
            if (index != null) {
                out.put(index, monster);
            }
        }
        return out;
    }

    private static Integer intOf(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        try {
            return Integer.valueOf(json.get(key).getAsInt());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stringOf(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
