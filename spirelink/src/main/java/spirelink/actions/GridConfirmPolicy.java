package spirelink.actions;

/**
 * C7-03: <b>when does a card-select grid accept {@code confirm_grid}?</b> One predicate, asked by the
 * planner (what gets advertised) and by the executor (what gets executed), so the two can never
 * drift apart again.
 *
 * <p>The report: on a purge grid nothing is selected yet, so {@code confirm_grid} is not advertised
 * — and hard-sending it answered {@code NOT_READY} ("还没选牌，先 choose_grid_cards").
 * {@code NOT_READY} means "the game is busy, poll and retry"; "this action is not on offer in this
 * ui" is a legality problem, and every unadvertised action must answer
 * {@code ILLEGAL_ACTION}.</p>
 *
 * <p>Vanilla facts the predicate mirrors ({@code javap -c GridCardSelectScreen}): the confirm button
 * only exists as a hitbox while the screen shows it ({@code confirmScreenUp} for upgrade / transform
 * / purge picks, {@code isJustForConfirming} for the "just confirm" flavour), a multi-select grid
 * accepts the confirm once {@code numCards} cards are selected, and a grid that already holds
 * selections may always be committed.</p>
 */
public final class GridConfirmPolicy {

    private GridConfirmPolicy() {
    }

    /**
     * @param confirmButtonUsable  the screen's confirm button is on screen and not disabled right now
     *                             ({@code ActionPlanner.gridConfirmAvailable})
     * @param wanted               {@code numCards} the grid asks for, {@code <= 0} when unknown
     * @param selected             cards currently in {@code selectedCards}
     * @param justForConfirming    {@code GridCardSelectScreen.isJustForConfirming}
     * @param upgradePreviewPending the upgrade/transform preview card is up (the pick is made)
     * @return true when {@code confirm_grid} may be advertised and executed
     */
    public static boolean confirmAvailable(boolean confirmButtonUsable, int wanted, int selected,
            boolean justForConfirming, boolean upgradePreviewPending) {
        // Same terms, same order as the planner has advertised with since #96/#83; the button flag is
        // the one the executor was missing (it answered NOT_READY where the planner offered nothing,
        // and refused grids whose confirm button was live before any card was picked).
        return confirmButtonUsable
                || (wanted > 0 && selected >= wanted)
                || justForConfirming
                || selected > 0
                || upgradePreviewPending;
    }

    /**
     * Rejection for {@code confirm_grid} when nothing can be confirmed right now: it is not offered,
     * so the code is {@code ILLEGAL_ACTION}, never {@code NOT_READY} (C7-03).
     */
    public static String nothingToConfirmMessage() {
        return "现在没有可确认的选牌（legal_actions 里没有 confirm_grid）：先 choose_grid_cards 选牌"
                + "；未广告的动作一律 ILLEGAL_ACTION";
    }
}
