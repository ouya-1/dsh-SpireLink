package spirelink.actions;

/** Result of executing one action on the game thread. */
public final class ActionResult {

    public final boolean ok;
    public final String code;
    public final String message;
    /**
     * True when the action verified its own effect (e.g. the screen really closed). Such an action can
     * answer {@code applied} even if the state signature happens to stay identical.
     */
    public final boolean verified;
    /**
     * C7-05: the rejection is <b>parameter level</b> — the caller named something the payload cannot
     * carry right now (an index out of range, a uuid that is not among the offered cards, a missing
     * target, an option that is not clickable). The planner did advertise the action type, so this is
     * <i>not</i> "planner says X, executor refuses X"; the caller sent a payload the advertisement
     * never contained.
     *
     * <p>Only a {@code false} here may be reported as a planner/executor disagreement (the WARN in
     * {@code ToolCatalog}), which is what turned a normal rejected {@code take_card{index:9}} into a
     * false alarm. See {@link #rejectParam(String, String)}.</p>
     */
    public final boolean paramLevel;

    private ActionResult(boolean ok, String code, String message, boolean verified,
            boolean paramLevel) {
        this.ok = ok;
        this.code = code;
        this.message = message;
        this.verified = verified;
        this.paramLevel = paramLevel;
    }

    public static ActionResult ok() {
        return new ActionResult(true, null, null, false, false);
    }

    public static ActionResult verified() {
        return new ActionResult(true, null, null, true, false);
    }

    /**
     * A business rejection that is about <b>the state of the game</b> or about the action being
     * offered at all — this is the shape a real planner/executor disagreement would have.
     */
    public static ActionResult reject(String code, String message) {
        return new ActionResult(false, code, message, false, false);
    }

    /**
     * A business rejection that is about <b>the payload</b>: index/uuid/slot out of range, a card or
     * option that is not among the offered ones, a required field missing. Advertised and executable
     * for the right payload, so it must never be counted as "advertised but refused" (C7-05).
     */
    public static ActionResult rejectParam(String code, String message) {
        return new ActionResult(false, code, message, false, true);
    }
}
