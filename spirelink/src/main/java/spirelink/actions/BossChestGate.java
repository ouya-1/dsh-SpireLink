package spirelink.actions;

import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.rewards.chests.BossChest;
import com.megacrit.cardcrawl.rooms.TreasureRoomBoss;

/**
 * The single source of truth for "may I leave the act-boss treasure room yet?".
 *
 * <p>Both {@link ActionPlanner} (what to offer) and {@link ActionExecutor} (what to accept) must use
 * this, otherwise the planner offers a {@code proceed} the executor rejects - which is exactly how a
 * zero-click run got stuck on {@code ui=treasure} with no executable action at all.
 */
public final class BossChestGate {

    private static Object lastRoom;
    private static boolean skipped;

    private BossChestGate() {
    }

    /** The current room when it is the act-boss treasure room, else null. */
    public static TreasureRoomBoss bossRoom() {
        try {
            if (AbstractDungeon.currMapNode == null) {
                return null;
            }
            return AbstractDungeon.getCurrRoom() instanceof TreasureRoomBoss
                    ? (TreasureRoomBoss) AbstractDungeon.getCurrRoom() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Creates the BossChest when the room somehow has none (a null chest NPEs the room's update). */
    public static void ensureChest() {
        TreasureRoomBoss room = bossRoom();
        if (room == null || room.chest != null) {
            return;
        }
        try {
            room.chest = new BossChest();
            spirelink.util.Diag.warn("treasure: Boss 房里没有宝箱，已补一个 BossChest");
        } catch (Throwable t) {
            spirelink.util.Diag.warn("treasure: 补 BossChest 失败: " + t);
        }
    }

    /** True when the boss relic pick is finished (taken through vanilla, our fallback, or skipped). */
    public static boolean relicClaimed() {
        TreasureRoomBoss room = bossRoom();
        if (room == null) {
            return true;
        }
        if (room == lastRoom && skipped) {
            return true;
        }
        Object chose = spirelink.util.FieldKit.fieldOrNull(room, "choseRelic");
        return Boolean.TRUE.equals(chose);
    }

    /**
     * Called by {@code skip_relic}: vanilla's noPick() closes the screen without touching choseRelic,
     * so write the flag itself - both the planner and the executor read it.
     */
    public static void markSkipped() {
        TreasureRoomBoss room = bossRoom();
        lastRoom = room;
        skipped = true;
        if (room != null) {
            spirelink.util.FieldKit.setFieldQuiet(room, "choseRelic", Boolean.TRUE);
        }
    }

    /**
     * True only while the boss chest still has to be opened.
     *
     * <p>Deliberately NOT just "relic not claimed": once the chest has been opened the relic screen is
     * (or was) up, so opening it again would loop forever (open_chest -> boss_reward -> open_chest).
     * The claim itself is guarded by {@link #relicClaimed()} inside {@code claim_relic}/{@code skip_relic}.
     */
    public static boolean mustOpenChest() {
        TreasureRoomBoss room = bossRoom();
        if (room == null) {
            return false;
        }
        if (relicClaimed()) {
            return false;
        }
        if (room.chest == null) {
            ensureChest();
            if (room.chest == null) {
                return false;
            }
        }
        return !room.chest.isOpen;
    }

    /** One-line description used by the planner/executor logs. */
    public static String describe() {
        TreasureRoomBoss room = bossRoom();
        if (room == null) {
            return "room=- chest=- relic=- mustOpen=false";
        }
        Object chest = room.chest;
        String chestState = chest == null ? "null"
                : (((com.megacrit.cardcrawl.rewards.chests.AbstractChest) chest).isOpen
                        ? "open" : "closed");
        Object chose = spirelink.util.FieldKit.fieldOrNull(room, "choseRelic");
        return "room=" + room.getClass().getSimpleName() + " chest=" + chestState
                + " relic=" + (Boolean.TRUE.equals(chose) ? "claimed" : "-")
                + " mustOpen=" + mustOpenChest();
    }
}
