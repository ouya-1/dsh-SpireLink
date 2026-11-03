package spirelink.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The slim map node: only fields that cannot be derived, and it has to stay small. */
public class MapNodeSizeTest {

    @Test
    public void nodeKeepsOnlyNonDerivableFields() {
        JsonArray edges = new JsonArray();
        edges.add(link(1, 1));
        JsonObject node = StateBuilder.mapNodeJson(2, 0, "M", "monster", false, edges);
        assertEquals(2, node.get("x").getAsInt());
        assertEquals("M", node.get("symbol").getAsString());
        assertEquals("monster", node.get("room").getAsString());
        assertEquals(5, node.entrySet().size());
        assertTrue(!node.has("row") && !node.has("column") && !node.has("room_zh")
                && !node.has("edge_count") && !node.has("edges_in") && !node.has("taken"));
    }

    @Test
    public void wholeActFitsInABudget() {
        // A full act has ~105 node slots with ~1.5 outgoing edges each.
        JsonArray rows = new JsonArray();
        int nodes = 0;
        for (int y = 0; y < 15; y++) {
            JsonArray row = new JsonArray();
            for (int x = 0; x < 7; x++) {
                JsonArray edges = new JsonArray();
                edges.add(link(x, y + 1));
                edges.add(link(Math.max(0, x - 1), y + 1));
                row.add(StateBuilder.mapNodeJson(x, y, "M", "monster", y == 0, edges));
                nodes++;
            }
            rows.add(row);
        }
        int bytes = rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue("map 太大: " + bytes, bytes < 11000);
        assertTrue(nodes > 100);
    }

    private static JsonArray link(int x, int y) {
        JsonArray link = new JsonArray();
        link.add(x);
        link.add(y);
        return link;
    }
}
