package spirelink.util;

import java.util.HashSet;
import java.util.Set;

/**
 * Per-run dedupe for notes that {@code get_state} returns automatically.
 *
 * <p>A long run used to receive the same 50 notes in every single frame (recent_notes, briefing
 * general_notes, entity {@code note} fields), which is pure context waste: the agent already has
 * them. Within one run each note version is delivered once; explicit queries (note_get / notes_list)
 * bypass this entirely, and {@code get_state{force_notes:true}} resets the window for debugging.
 */
public final class NotesDedupe {

    private static final Set<String> DELIVERED = new HashSet<String>();
    private static String runId = "";
    private static int omitted;
    private static boolean force;

    private NotesDedupe() {
    }

    /** Called once per state build: a new run clears the window. */
    public static synchronized void observeRun(String currentRunId) {
        String id = currentRunId == null ? "" : currentRunId;
        if (!id.equals(runId)) {
            runId = id;
            DELIVERED.clear();
            omitted = 0;
        }
    }

    /** {@code force_notes:true}: return everything once, then go back to deduping. */
    public static synchronized void setForce(boolean value) {
        force = value;
        if (value) {
            DELIVERED.clear();
            omitted = 0;
        }
    }

    /** True when this note version should be included in the automatic payload. */
    public static synchronized boolean claim(String key, String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        if (force) {
            // Still remember it: a forced dump must not re-enable the repeats afterwards.
            DELIVERED.add(key);
            return true;
        }
        if (DELIVERED.add(key)) {
            return true;
        }
        omitted++;
        return false;
    }

    /** How many note occurrences were suppressed in this run so far. */
    public static synchronized int omitted() {
        return omitted;
    }

    /** True when this key was already delivered in the current run. */
    public static synchronized boolean delivered(String key) {
        return DELIVERED.contains(key);
    }

    public static synchronized void resetForTests() {
        DELIVERED.clear();
        runId = "";
        omitted = 0;
        force = false;
    }
}
