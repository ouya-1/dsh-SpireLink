package spirelink.actions;

/**
 * One-line, bounded candidate labels for rejection messages (#113).
 *
 * <p>Event option texts and card names come straight from the game: multi-line, padded with layout
 * noise, sometimes hundreds of characters. A rejection has to stay readable in one line, so every
 * candidate is flattened and cut exactly like the labels the planner puts in {@code legal_actions}
 * ({@code ActionPlanner.clean}) — one rule, so the candidate a caller sees and the one the planner
 * advertised read the same.</p>
 *
 * <p>Pure: no game classes, so the shape is unit tested.</p>
 */
public final class LabelText {

    /** Longest label kept before it is cut; the same bound the planner uses for action labels. */
    public static final int MAX_LENGTH = 40;

    private LabelText() {
    }

    /**
     * "香蕉 回复 333 生命" — whitespace collapsed, square brackets dropped (the planner does the same
     * for its own labels), trimmed, and cut at {@link #MAX_LENGTH} with an ellipsis.
     *
     * @param text raw game text, may be {@code null} or multi-line
     * @return a single-line label; never {@code null}
     */
    public static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace("[", "").replace("]", "").replaceAll("\\s+", " ").trim();
        if (flat.length() > MAX_LENGTH) {
            flat = flat.substring(0, MAX_LENGTH - 1) + "…";
        }
        return flat;
    }
}