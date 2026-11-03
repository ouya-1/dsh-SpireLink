package spirelink.patches;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The vanilla monster-pool landmine (issues #64 / #73): {@code Exordium.generateExclusions()} starts
 * with {@code monsterList.get(monsterList.size() - 1)} and has no empty check, so seeding the pool
 * before {@code getMonsterForRoomCreation()} / {@code nextRoomTransition(SaveFile)} runs is the only
 * thing standing between "pool emptied by whatever" and a frozen run.
 *
 * <p>The seeding logic is pure, so the whole contract is pinned down without a game:
 * empty pool -> seeded and non-empty; healthy pool -> untouched.</p>
 */
public class MonsterPoolGuardPatchTest {

    private static List<String> pool(String... ids) {
        return new ArrayList<String>(Arrays.asList(ids));
    }

    @Test
    public void emptyPoolNeedsSeed() {
        assertTrue(MonsterPoolGuardPatch.needsSeed(new ArrayList<String>()));
    }

    @Test
    public void healthyPoolIsLeftAlone() {
        assertFalse(MonsterPoolGuardPatch.needsSeed(pool("Cultist")));
        assertFalse(MonsterPoolGuardPatch.needsSeed(pool("Cultist", "Jaw Worm", "Looter")));
    }

    @Test
    public void nullPoolIsNotSeededHere() {
        // ensureSeeded() creates the list first; the pure helper must not pretend it can fill null.
        assertFalse(MonsterPoolGuardPatch.needsSeed(null));
        assertEquals(0, MonsterPoolGuardPatch.seedIfEmpty(null, MonsterPoolGuardPatch.ACT1_POOL, 0));
    }

    /** The whole point: an empty pool becomes usable, so vanilla's get(0)/get(size-1) cannot throw. */
    @Test
    public void emptyPoolBecomesNonEmpty() {
        List<String> list = new ArrayList<String>();
        int added = MonsterPoolGuardPatch.seedIfEmpty(list, MonsterPoolGuardPatch.ACT1_POOL, 0);
        assertEquals(MonsterPoolGuardPatch.ACT1_POOL.length, added);
        assertEquals(added, list.size());
        assertFalse(list.isEmpty());
        for (String id : list) {
            assertTrue("补种的 id 不能为 null/空: " + id, id != null && !id.isEmpty());
        }
    }

    /** A healthy pool must not be appended to — that would reroll the act's encounters. */
    @Test
    public void healthyPoolIsNotAppendedTo() {
        List<String> list = pool("Looter", "Cultist");
        int added = MonsterPoolGuardPatch.seedIfEmpty(list, MonsterPoolGuardPatch.ACT1_POOL, 0);
        assertEquals(0, added);
        assertEquals(Arrays.asList("Looter", "Cultist"), list);
    }

    @Test
    public void offsetRotatesTheOrder() {
        List<String> first = new ArrayList<String>();
        List<String> second = new ArrayList<String>();
        MonsterPoolGuardPatch.seedIfEmpty(first, MonsterPoolGuardPatch.ACT1_POOL, 0);
        MonsterPoolGuardPatch.seedIfEmpty(second, MonsterPoolGuardPatch.ACT1_POOL, 1);
        assertEquals(first.size(), second.size());
        assertEquals("偏移 1 必须真的换了个顺序", first.get(1), second.get(0));
    }

    @Test
    public void outOfRangeOffsetWraps() {
        List<String> list = new ArrayList<String>();
        int added = MonsterPoolGuardPatch.seedIfEmpty(list,
                MonsterPoolGuardPatch.ACT1_POOL, MonsterPoolGuardPatch.ACT1_POOL.length + 2);
        assertEquals(MonsterPoolGuardPatch.ACT1_POOL.length, added);
        assertEquals(MonsterPoolGuardPatch.ACT1_POOL[2], list.get(0));
    }

    @Test
    public void emptyCandidateListAddsNothing() {
        List<String> list = new ArrayList<String>();
        assertEquals(0, MonsterPoolGuardPatch.seedIfEmpty(list, new String[0], 0));
        assertTrue(list.isEmpty());
    }

    // ---------------------------------------------------------------- per act

    @Test
    public void act1UsesTheExordiumWeakPool() {
        assertEquals(Arrays.asList("Cultist", "Jaw Worm", "2 Louse", "Small Slimes"),
                Arrays.asList(MonsterPoolGuardPatch.poolFor(1)));
        // act 0 / unknown falls back to act 1 rather than to nothing.
        assertEquals(Arrays.asList(MonsterPoolGuardPatch.ACT1_POOL),
                Arrays.asList(MonsterPoolGuardPatch.poolFor(0)));
    }

    @Test
    public void act2UsesTheCityWeakPool() {
        assertEquals(Arrays.asList("Chosen", "Shell Parasite", "Spheric Guardian", "3 Byrds"),
                Arrays.asList(MonsterPoolGuardPatch.poolFor(2)));
    }

    @Test
    public void act3UsesTheBeyondWeakPool() {
        assertEquals(Arrays.asList("Orb Walker", "3 Darklings", "Spire Growth", "Transient"),
                Arrays.asList(MonsterPoolGuardPatch.poolFor(3)));
        // Act 4 (TheEnding) reuses the act-3 ids: any encounter MonsterHelper knows is safe there.
        assertEquals(Arrays.asList(MonsterPoolGuardPatch.ACT3_POOL),
                Arrays.asList(MonsterPoolGuardPatch.poolFor(4)));
    }

    /**
     * Every seeded id must be one {@code MonsterHelper.getEncounter} understands.
     *
     * <p>Guarding this matters more than it looks: an id it does **not** know does not fail loudly,
     * it silently becomes the {@code ApologySlime} placeholder (issue #73). The expected literals are
     * spelled out here on purpose so a typo in the patch shows up as a red test.</p>
     */
    @Test
    public void everySeededIdIsARealVanillaEncounter() {
        java.util.Set<String> known = new java.util.LinkedHashSet<String>(Arrays.asList(
                "Cultist", "Jaw Worm", "2 Louse", "Small Slimes", "Large Slime", "Lots of Slimes",
                "Blue Slaver", "Red Slaver", "Looter", "Gremlin Gang", "Exordium Thugs",
                "Exordium Wildlife", "3 Louse", "2 Fungi Beasts",
                "Chosen", "Shell Parasite", "Spheric Guardian", "3 Byrds", "2 Thieves",
                "Snake Plant", "Snecko", "Centurion and Healer",
                "Orb Walker", "3 Darklings", "Spire Growth", "Transient", "Maw",
                "2 Orb Walkers", "Spire Shield and Spear", "Writhing Mass"));
        for (int act = 1; act <= 4; act++) {
            for (String id : MonsterPoolGuardPatch.poolFor(act)) {
                assertTrue("act " + act + " 补种用了一个原版不认识的 id: " + id + "（会变成 ApologySlime）",
                        known.contains(id));
            }
        }
    }

    @Test
    public void poolsHaveNoDuplicates() {
        for (int act = 1; act <= 4; act++) {
            String[] ids = MonsterPoolGuardPatch.poolFor(act);
            assertEquals("act " + act + " 补种池有重复",
                    ids.length, new java.util.LinkedHashSet<String>(Arrays.asList(ids)).size());
        }
    }
}
