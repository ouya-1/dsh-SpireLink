package spirelink.actions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure "how do I finish this card-select screen" plan, including vanilla's campfire restore
 * (issue #90).
 *
 * <h3>What vanilla does</h3>
 * <p>{@code CancelButton.update()} (the grid's cancel button) closes an upgrade/transform/purge grid
 * with:</p>
 * <pre>
 *   AbstractDungeon.closeCurrentScreen();                              // bytecode 318
 *   if (AbstractDungeon.getCurrRoom() instanceof RestRoom) {           // bytecode 321-327
 *       ((RestRoom) AbstractDungeon.getCurrRoom()).campfireUI.reopen(); // bytecode 330-341
 *   }
 *   return;                                                           // bytecode 344
 * </pre>
 * <p>and {@code CampfireUI.reopen()} is exactly three assignments:</p>
 * <pre>
 *   CampfireUI.hidden = false;
 *   this.hideStuffTimer = 0.5f;
 *   this.somethingSelected = false;
 * </pre>
 *
 * <h3>Why the mod has to do it too</h3>
 * <p>{@code cancel_grid} used to call only {@code closeCurrentScreen()}. In a rest room that leaves
 * {@code hidden}/{@code somethingSelected} on {@code true}, so the campfire buttons never come back
 * and the agent is left with nothing but {@code proceed} — plus #87's {@code ClassCastException} is
 * amplified by the same stuck flag.</p>
 *
 * <h3>Scope guard</h3>
 * <p>Nothing here is applied for a plain confirm: vanilla only restores the campfire on the cancel
 * branch, and the smith/upgrade confirm flow is driven by vanilla's own effect. Every non-rest room
 * (event, shop, combat, boss, ...) gets exactly the three close steps it always had.</p>
 */
public final class GridClosePlan {

    /** One ordered step of finishing the grid. */
    public enum Step {
        /** {@code AbstractDungeon.closeCurrentScreen()} — what vanilla always does first. */
        CLOSE_SCREEN,
        /** {@code GridCardSelectScreen.hide()} — only reached when the screen did not close. */
        HIDE_GRID,
        /** {@code isScreenUp = false} + {@code screen = NONE} — last resort. */
        RESET_SCREEN,
        /** {@code restRoom.campfireUI.reopen()} — vanilla's campfire restore, cancel only. */
        REOPEN_CAMPFIRE,
    }

    // Grid flavours, in the order vanilla tests them (forUpgrade, forTransform, forPurge).
    public static final String KIND_UPGRADE = "upgrade";
    public static final String KIND_TRANSFORM = "transform";
    public static final String KIND_PURGE = "purge";
    /** A grid that is none of the three: events, Neow, boss relic picks, mod-opened grids. */
    public static final String KIND_NORMAL = "normal";

    /** {@code com.megacrit.cardcrawl.rooms.RestRoom}, as a plain string so this stays game-free. */
    public static final String REST_ROOM = "com.megacrit.cardcrawl.rooms.RestRoom";

    private GridClosePlan() {
    }

    /** Pure: which vanilla grid flavour this is. */
    public static String gridKind(boolean forUpgrade, boolean forTransform, boolean forPurge) {
        if (forUpgrade && !forTransform) {
            return KIND_UPGRADE;
        }
        if (forTransform) {
            return KIND_TRANSFORM;
        }
        if (forPurge) {
            return KIND_PURGE;
        }
        return KIND_NORMAL;
    }

    /**
     * Pure: does this grid flavour get vanilla's campfire restore?
     *
     * <p>Exactly the {@code if (forUpgrade || forTransform || forPurge)} branch of
     * {@code CancelButton}; every other grid closes without touching the campfire.</p>
     */
    public static boolean isCampfireGridKind(String gridKind) {
        return KIND_UPGRADE.equals(gridKind)
                || KIND_TRANSFORM.equals(gridKind)
                || KIND_PURGE.equals(gridKind);
    }

    /** Pure: rest room + a campfire-flavoured grid. */
    public static boolean isCampfireContext(String roomClass, String gridKind) {
        return REST_ROOM.equals(roomClass) && isCampfireGridKind(gridKind);
    }

    /**
     * Pure: the ordered steps for finishing the grid.
     *
     * <p>The three close steps are always present — the executor stops at the first one that works,
     * exactly like the old hand-written fallback chain. {@link Step#REOPEN_CAMPFIRE} is appended only
     * for a cancel in a campfire context, so it always runs after the screen is actually gone (the
     * vanilla order).</p>
     *
     * @param roomClass {@code AbstractDungeon.getCurrRoom().getClass().getName()}, null when unknown
     * @param gridKind  one of the {@code KIND_*} values, null when unknown
     * @param cancel    true only for {@code cancel_grid}
     */
    public static List<Step> steps(String roomClass, String gridKind, boolean cancel) {
        List<Step> out = new ArrayList<Step>(4);
        out.add(Step.CLOSE_SCREEN);
        out.add(Step.HIDE_GRID);
        out.add(Step.RESET_SCREEN);
        if (cancel && isCampfireContext(roomClass, gridKind)) {
            out.add(Step.REOPEN_CAMPFIRE);
        }
        return Collections.unmodifiableList(out);
    }

    /** True when the plan ends with the campfire restore (convenience for callers/diagnostics). */
    public static boolean restoresCampfire(List<Step> steps) {
        return steps != null && !steps.isEmpty()
                && steps.get(steps.size() - 1) == Step.REOPEN_CAMPFIRE;
    }
}
