package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.map.MapEdge;
import com.megacrit.cardcrawl.map.MapRoomNode;

import java.lang.reflect.Field;
import java.util.List;

/** Test-only map inspector: dumps the real edge/parent data so "no path" can be diagnosed. */
public final class DevMapTool {

    private static final int ROWS = 3;

    private DevMapTool() {
    }

    public static JsonObject dump() {
        JsonObject out = new JsonObject();
        if (!spirelink.util.TestMode.enabled()) {
            out.addProperty("ok", false);
            out.addProperty("error", "DEV_TOOL_DISABLED");
            return out;
        }
        if (AbstractDungeon.map == null) {
            out.addProperty("ok", false);
            out.addProperty("error", "NOT_READY");
            out.addProperty("message", "还没有地图");
            return out;
        }
        out.addProperty("ok", true);
        out.addProperty("rows", AbstractDungeon.map.size());
        out.addProperty("firstRoomChosen", AbstractDungeon.firstRoomChosen);
        MapRoomNode curr = AbstractDungeon.getCurrMapNode();
        JsonObject currJson = new JsonObject();
        currJson.addProperty("x", curr == null ? -1 : curr.x);
        currJson.addProperty("y", curr == null ? -1 : curr.y);
        currJson.addProperty("isNull", curr == null);
        currJson.addProperty("edges", curr == null ? -1 : edges(curr).size());
        out.add("curr", currJson);
        JsonArray nodes = new JsonArray();
        for (int y = 0; y < Math.min(ROWS, AbstractDungeon.map.size()); y++) {
            List<MapRoomNode> row = AbstractDungeon.map.get(y);
            if (row == null) {
                continue;
            }
            for (MapRoomNode node : row) {
                if (node == null) {
                    continue;
                }
                JsonObject json = new JsonObject();
                json.addProperty("x", node.x);
                json.addProperty("y", node.y);
                json.addProperty("taken", node.taken);
                json.addProperty("room", node.room != null);
                if (node.room != null) {
                    json.addProperty("roomType", node.room.getClass().getSimpleName());
                }
                JsonArray edges = new JsonArray();
                for (MapEdge edge : edges(node)) {
                    JsonObject e = new JsonObject();
                    e.addProperty("srcX", intField(edge, "srcX"));
                    e.addProperty("srcY", intField(edge, "srcY"));
                    e.addProperty("dstX", intField(edge, "dstX"));
                    e.addProperty("dstY", intField(edge, "dstY"));
                    e.addProperty("taken", boolField(edge, "isTaken"));
                    edges.add(e);
                }
                json.add("edges", edges);
                JsonArray parents = new JsonArray();
                Object rawParents = rawField(node, "parents");
                if (rawParents instanceof List) {
                    for (Object parent : (List<?>) rawParents) {
                        if (parent instanceof MapEdge) {
                            JsonObject p = new JsonObject();
                            p.addProperty("srcX", intField((MapEdge) parent, "srcX"));
                            p.addProperty("srcY", intField((MapEdge) parent, "srcY"));
                            parents.add(p);
                        }
                    }
                }
                json.add("parents", parents);
                nodes.add(json);
            }
        }
        out.add("nodes", nodes);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<MapEdge> edges(MapRoomNode node) {
        Object raw = rawField(node, "edges");
        return raw instanceof List ? (List<MapEdge>) raw : java.util.Collections.<MapEdge>emptyList();
    }

    private static Object rawField(Object target, String name) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    private static int intField(MapEdge edge, String name) {
        Object value = rawField(edge, name);
        return value instanceof Integer ? ((Integer) value).intValue() : -1;
    }

    private static boolean boolField(MapEdge edge, String name) {
        Object value = rawField(edge, name);
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }
}
