package spirelink.mcp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #109 (S1): {@code dev_room{type:"victory"}} killed the render thread while a monster was
 * still in its death animation.
 *
 * <p>QA hit it twice — a boss ({@code Hexaghost}) and a plain {@code Cultist}, one second to several
 * seconds after a dev kill — with {@code monster_list:[{hp:0, dying:true}]}. The stack was always
 * {@code VictoryScreen.update -> MonsterGroup.update -> AbstractMonster.updateDeathAnimation:1163},
 * and line 1163 is {@code AbstractDungeon.getMonsters().areMonstersDead()}: the screen was ticking
 * the group of the room we had just left while {@code currMapNode.room} was already a
 * {@code VictoryRoom} whose {@code monsters} is null.</p>
 *
 * <p>Two facts make this testable without a game, and they are the two the fix rests on:</p>
 * <ul>
 *   <li>the <b>precondition</b> is "is anything in the group still dying" — {@link DevRoomTool.MonsterFacts}
 *       carries it as plain numbers, so a dying boss and a dying Cultist are the same row;</li>
 *   <li>the <b>cure</b> is that {@code VictoryScreen} gets no monster group at all
 *       ({@link DevRoomTool#groupForVictoryScreen}), because {@code deathTimer < 0} — true for every
 *       finished corpse, too — is what makes that vanilla tail reach {@code getMonsters()}.</li>
 * </ul>
 */
public class VictoryDyingMonstersTest {

    // ---------------------------------------------------------------- the precondition

    /** The exact QA row: one monster, hp 0, dying=true, and nothing else on the field. */
    private static DevRoomTool.MonsterFacts oneDyingCultist() {
        return DevRoomTool.MonsterFacts.of(1, 1, 0, 0);
    }

    /** The boss row: Hexaghost dead and still animating. */
    private static DevRoomTool.MonsterFacts dyingBoss() {
        return DevRoomTool.MonsterFacts.of(1, 1, 0, 0);
    }

    @Test
    public void aDyingMonsterAsksForASettlePassNoMatterWhatItIs() {
        // Boss and trash must not differ: the criterion is "still dying", not "is a boss" (#109).
        assertEquals(DevRoomTool.VictoryMonsters.SETTLE_THEN_MOUNT_WITHOUT_GROUP,
                DevRoomTool.planVictoryMonsters(dyingBoss()));
        assertEquals(DevRoomTool.VictoryMonsters.SETTLE_THEN_MOUNT_WITHOUT_GROUP,
                DevRoomTool.planVictoryMonsters(oneDyingCultist()));
        // One dying monster among live ones is the same danger.
        assertEquals(DevRoomTool.VictoryMonsters.SETTLE_THEN_MOUNT_WITHOUT_GROUP,
                DevRoomTool.planVictoryMonsters(DevRoomTool.MonsterFacts.of(3, 1, 1, 1)));
    }

    @Test
    public void aFinishedOrLiveGroupNeedsNoSettlePass() {
        assertEquals(DevRoomTool.VictoryMonsters.MOUNT_WITHOUT_GROUP,
                DevRoomTool.planVictoryMonsters(DevRoomTool.MonsterFacts.of(1, 0, 1, 0)));
        assertEquals(DevRoomTool.VictoryMonsters.MOUNT_WITHOUT_GROUP,
                DevRoomTool.planVictoryMonsters(DevRoomTool.MonsterFacts.of(2, 0, 0, 2)));
        assertEquals(DevRoomTool.VictoryMonsters.MOUNT_WITHOUT_GROUP,
                DevRoomTool.planVictoryMonsters(DevRoomTool.MonsterFacts.of(0, 0, 0, 0)));
        assertEquals(DevRoomTool.VictoryMonsters.MOUNT_WITHOUT_GROUP,
                DevRoomTool.planVictoryMonsters(null));
    }

    @Test
    public void theFactsDescribeThemselvesForTheLog() {
        DevRoomTool.MonsterFacts facts = DevRoomTool.MonsterFacts.of(2, 1, 1, 0);
        assertTrue(facts.deathAnimationsRunning());
        assertFalse(facts.empty());
        String line = facts.describe();
        assertTrue(line, line.contains("monsters=2"));
        assertTrue(line, line.contains("dying=1"));
        assertTrue(line, line.contains("dead=1"));
    }

    @Test
    public void negativeCountsAreClampedInsteadOfLeakingIntoTheDecision() {
        DevRoomTool.MonsterFacts facts = DevRoomTool.MonsterFacts.of(-5, -1, -1, -1);
        assertEquals(0, facts.total);
        assertEquals(0, facts.dying);
        assertTrue(facts.empty());
        assertFalse(facts.deathAnimationsRunning());
    }

    // ---------------------------------------------------------------- the cure

    /**
     * The invariant the crash needs: whatever the room looks like, the screen gets {@code null}.
     *
     * <p>Handing back even a "clean" group would be unsafe: {@code AbstractMonster.updateDeathAnimation()}
     * enters its {@code deathTimer < 0} tail for every corpse that already finished, and that tail is
     * the very line that dereferences {@code AbstractDungeon.getMonsters()}.</p>
     */
    @Test
    public void theVictoryScreenNeverGetsAMonsterGroup() {
        for (DevRoomTool.MonsterFacts facts : new DevRoomTool.MonsterFacts[] {
                oneDyingCultist(), dyingBoss(), DevRoomTool.MonsterFacts.of(1, 0, 1, 0),
                DevRoomTool.MonsterFacts.of(2, 0, 0, 2), DevRoomTool.MonsterFacts.of(0, 0, 0, 0), null}) {
            assertNull("VictoryScreen 不能拿到怪物组（#109）: "
                            + (facts == null ? "null" : facts.describe()),
                    DevRoomTool.groupForVictoryScreen(facts));
        }
    }

    // ---------------------------------------------------------------- the undo token

    @Test
    public void anEmptySettleIsANoOp() {
        DevRoomTool.DeathSettle empty = DevRoomTool.DeathSettle.EMPTY;
        assertNotNull(empty);
        assertEquals(0, empty.count());
        assertEquals("没有收尾就没有可回滚的", 0, empty.revert());
    }

    @Test
    public void theSettlePassIsBounded() {
        assertTrue("一次收尾必须有上限，别在怪物列表上打转",
                DevRoomTool.MAX_SETTLE_MONSTERS > 0
                        && DevRoomTool.MAX_SETTLE_MONSTERS <= 256);
    }
}
