package spirelink.state;

import com.megacrit.cardcrawl.rooms.AbstractRoom;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #42: the frame right after the act boss dies — {@code room_phase=COMPLETE} while still
 * inside {@code MonsterRoomBoss} — used to report {@code ui="idle"}, which told the agent nothing
 * had happened. It must name itself {@code boss_cleared} (and {@code meta.room_cleared=true}).
 *
 * <p>The ui value itself is derived from live game state, so the locked-down part is the pure
 * predicate every caller shares; that is also what keeps "only add, never change" honest: a plain
 * monster room, an elite room and the {@code TreasureRoomBoss} reward screen must stay untouched.
 */
public class BossClearedTest {

    @Test
    public void bossRoomAfterTheFightIsCleared() {
        assertEquals("boss_cleared", StateBuilder.UI_BOSS_CLEARED);
        assertTrue(StateBuilder.bossRoomCleared(
                "MonsterRoomBoss", AbstractRoom.RoomPhase.COMPLETE));
    }

    @Test
    public void bossRoomThatIsStillFightingIsNotCleared() {
        assertFalse(StateBuilder.bossRoomCleared(
                "MonsterRoomBoss", AbstractRoom.RoomPhase.COMBAT));
        assertFalse(StateBuilder.bossRoomCleared(
                "MonsterRoomBoss", AbstractRoom.RoomPhase.INCOMPLETE));
        assertFalse("phase 还没定下来时不能抢先报 boss_cleared",
                StateBuilder.bossRoomCleared("MonsterRoomBoss", null));
    }

    @Test
    public void plainMonsterRoomKeepsTheOldPath() {
        assertFalse("普通怪房 COMPLETE 仍走原逻辑（奖励屏自己给 ui）",
                StateBuilder.bossRoomCleared("MonsterRoom", AbstractRoom.RoomPhase.COMPLETE));
        assertFalse(StateBuilder.bossRoomCleared(
                "MonsterRoomElite", AbstractRoom.RoomPhase.COMPLETE));
    }

    @Test
    public void treasureRoomBossKeepsItsOwnLogic() {
        assertFalse("TreasureRoomBoss 的奖励屏另有既有逻辑，不许改名",
                StateBuilder.bossRoomCleared("TreasureRoomBoss", AbstractRoom.RoomPhase.COMPLETE));
        assertFalse(StateBuilder.bossRoomCleared(
                "TreasureRoom", AbstractRoom.RoomPhase.COMPLETE));
    }

    @Test
    public void unknownRoomIsNeverBossCleared() {
        assertFalse(StateBuilder.bossRoomCleared(null, AbstractRoom.RoomPhase.COMPLETE));
        assertFalse(StateBuilder.bossRoomCleared("", AbstractRoom.RoomPhase.COMPLETE));
        assertFalse(StateBuilder.bossRoomCleared(null, null));
    }
}
