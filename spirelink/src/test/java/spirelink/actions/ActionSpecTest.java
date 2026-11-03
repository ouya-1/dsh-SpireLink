package spirelink.actions;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** Action list digest feeds the state signature (a change here must bump state_version). */
public class ActionSpecTest {

    private static ActionSpec spec(String type, String key, String value) {
        JsonObject payload = new JsonObject();
        if (key != null) {
            payload.addProperty(key, value);
        }
        return new ActionSpec("a1", type, "label", payload);
    }

    @Test
    public void digestChangesWithActions() {
        List<ActionSpec> a = new ArrayList<>();
        a.add(spec("open_mode_select", null, null));
        List<ActionSpec> b = new ArrayList<>();
        b.add(spec("choose_mode", "mode", "standard"));
        assertNotEquals(ActionSpec.digest(a), ActionSpec.digest(b));
    }

    @Test
    public void digestChangesWithPayload() {
        List<ActionSpec> a = new ArrayList<>();
        a.add(spec("choose_mode", "mode", "standard"));
        List<ActionSpec> b = new ArrayList<>();
        b.add(spec("choose_mode", "mode", "daily"));
        assertNotEquals(ActionSpec.digest(a), ActionSpec.digest(b));
    }

    @Test
    public void digestIsStableForSameInput() {
        List<ActionSpec> a = new ArrayList<>();
        a.add(spec("choose_map_node", "x", "1"));
        assertEquals(ActionSpec.digest(a), ActionSpec.digest(a));
        assertEquals("", ActionSpec.digest(null));
        assertEquals("", ActionSpec.digest(new ArrayList<>()));
    }
}
