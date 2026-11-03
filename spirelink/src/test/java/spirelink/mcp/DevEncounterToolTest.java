package spirelink.mcp;

import com.google.gson.JsonArray;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Issue #45: {@code dev_room{type:"monster"}} enters with a null group on purpose so
 * {@code MonsterRoom.onPlayerEntry()} rolls the encounter itself and stores it in
 * {@code room.monsters}; the post-entry pass used to iterate the caller's (still null) group and
 * always threw NullPointerException. Which list that pass walks is pure logic, so it is pinned down
 * here - no live dungeon object graph needed.
 *
 * <p>Issue #73: {@code MonsterHelper.getEncounter()} answers an unknown id with an
 * {@code ApologySlime} placeholder instead of null, so {@code dev_encounter{"NoSuchXyz"}} used to
 * answer {@code ok:true} and start a bogus fight. The plan phase below is pure, so the whole id
 * matrix (known / unknown / empty / null / placeholder) is testable without a game.</p>
 *
 * <p>Issue #64: a rejected call must not rewrite the vanilla monster pool — the plan phase touches
 * no game state, and the apply phase puts {@code monsterList} back.</p>
 */
public class DevEncounterToolTest {

    // ---------------------------------------------------------------- #45 (unchanged)

    @Test
    public void roomListWinsOverTheRequestedGroup() {
        List<String> fromRoom = Arrays.asList("Jaw Worm", "Cultist");
        List<String> requested = Arrays.asList("Gremlin Nob");
        assertSame(fromRoom, DevEncounterTool.firstNonEmpty(fromRoom, requested));
    }

    @Test
    public void requestedGroupIsTheFallbackWhenTheRoomSpawnedNothing() {
        List<String> requested = Arrays.asList("Gremlin Nob");
        assertSame(requested, DevEncounterTool.firstNonEmpty(new ArrayList<String>(), requested));
        assertSame(requested, DevEncounterTool.firstNonEmpty(null, requested));
    }

    @Test
    public void nothingAtAllYieldsAnEmptyListInsteadOfNull() {
        assertTrue(DevEncounterTool.firstNonEmpty(null, null).isEmpty());
        assertTrue(DevEncounterTool.firstNonEmpty(
                new ArrayList<String>(), new ArrayList<String>()).isEmpty());
    }

    /** The exact dev_room{type:"monster"} shape: no requested group, and no game objects around. */
    @Test
    public void nullGroupsAreTolerated() {
        assertTrue(DevEncounterTool.monstersOf(null).isEmpty());
        assertTrue(DevEncounterTool.spawnedMonsters(null, null).isEmpty());
    }

    // ---------------------------------------------------------------- #73 id matrix

    /** Fake encounter source: plain strings, no game class anywhere. */
    private static final class FakeSource implements DevEncounterTool.EncounterSource {

        private final java.util.Map<String, List<String>> known =
                new java.util.LinkedHashMap<String, List<String>>();
        final List<String> queried = new ArrayList<String>();

        FakeSource with(String id, String... monsterNames) {
            known.put(id, Arrays.asList(monsterNames));
            return this;
        }

        @Override
        public Set<String> knownIds() {
            return new LinkedHashSet<String>(known.keySet());
        }

        @Override
        public List<String> monsterNames(String id) {
            queried.add(id);
            return known.get(id);
        }
    }

    private static FakeSource vanillaExordium() {
        return new FakeSource()
                .with("Jaw Worm", "JawWorm")
                .with("Cultist", "Cultist")
                .with(DevEncounterTool.APOLOGY_SLIME_ID, DevEncounterTool.APOLOGY_SLIME_CLASS);
    }

    @Test
    public void knownIdPlansThatEncounter() {
        DevEncounterTool.Plan plan = DevEncounterTool.plan(
                Arrays.asList("Cultist"), vanillaExordium(), false);
        assertTrue(plan.ok());
        assertEquals("Cultist", plan.encounterId);
        assertEquals("Cultist", plan.label);
        assertFalse(plan.random());
    }

    /** The exact QA call from issue #73. */
    @Test
    public void unknownIdIsRejectedInsteadOfSilentlySucceeding() {
        DevEncounterTool.Plan plan = DevEncounterTool.plan(
                Arrays.asList("NoSuchEncounterXyz"), vanillaExordium(), false);
        assertFalse("非法 id 必须报错，不能回 ok:true", plan.ok());
        assertEquals("ENCOUNTER_NOT_FOUND", plan.errorCode);
        assertTrue("要说清是哪个 id: " + plan.errorMessage,
                plan.errorMessage.contains("NoSuchEncounterXyz"));
        assertTrue("要提示怎么列出可用 id: " + plan.errorMessage,
                plan.errorMessage.contains("list"));
    }

    /** Vanilla's ApologySlime placeholder must never be mounted for some other id. */
    @Test
    public void theApologySlimePlaceholderIsTreatedAsUnknown() {
        FakeSource source = new FakeSource().with("Cultist", DevEncounterTool.APOLOGY_SLIME_CLASS);
        DevEncounterTool.Plan plan = DevEncounterTool.plan(Arrays.asList("Cultist"), source, false);
        assertFalse("原版对不认识的 id 会回 ApologySlime 占位组，不能当成成功", plan.ok());
        assertEquals("ENCOUNTER_NOT_FOUND", plan.errorCode);
    }

    /** …but "Apology Slime" itself is a legal dev encounter (it is the console's sample fight). */
    @Test
    public void theApologySlimeEncounterItselfIsStillAllowed() {
        DevEncounterTool.Plan plan = DevEncounterTool.plan(
                Arrays.asList(DevEncounterTool.APOLOGY_SLIME_ID), vanillaExordium(), false);
        assertTrue(plan.ok());
        assertEquals(DevEncounterTool.APOLOGY_SLIME_ID, plan.encounterId);
    }

    @Test
    public void extraIdsAreTriedInOrder() {
        DevEncounterTool.Plan plan = DevEncounterTool.plan(
                Arrays.asList("NoSuchEncounterXyz", "Jaw Worm"), vanillaExordium(), false);
        assertTrue(plan.ok());
        assertEquals("Jaw Worm", plan.encounterId);
    }

    @Test
    public void allUnknownIdsAreListedInTheError() {
        DevEncounterTool.Plan plan = DevEncounterTool.plan(
                Arrays.asList("Aaa", "Bbb"), vanillaExordium(), false);
        assertFalse(plan.ok());
        assertTrue(plan.errorMessage.contains("Aaa"));
        assertTrue(plan.errorMessage.contains("Bbb"));
    }

    @Test
    public void emptyOrNullIdsAreBadRequest() {
        for (List<String> ids : Arrays.asList(null, new ArrayList<String>(),
                Arrays.asList("", "   ", null))) {
            DevEncounterTool.Plan plan = DevEncounterTool.plan(ids, vanillaExordium(), false);
            assertFalse(plan.ok());
            assertEquals("BAD_REQUEST", plan.errorCode);
        }
    }

    /** dev_room{type:"monster"}: no id at all means "let vanilla roll it", not an error. */
    @Test
    public void randomModeAcceptsAnEmptyIdList() {
        DevEncounterTool.Plan plan = DevEncounterTool.plan(null, null, true);
        assertTrue(plan.ok());
        assertNull(plan.encounterId);
        assertTrue(plan.random());
        assertEquals("random", plan.label);
    }

    /** A rejected plan never even asks the game for a group (that is the "zero side effects"). */
    @Test
    public void rejectedPlanDoesNotQueryTheEncounterList() {
        FakeSource source = vanillaExordium();
        DevEncounterTool.Plan plan = DevEncounterTool.plan(
                Arrays.asList("NoSuchEncounterXyz"), source, false);
        assertFalse(plan.ok());
        assertTrue("不认识的 id 不该去构造遭遇: " + source.queried, source.queried.isEmpty());
    }

    // ---------------------------------------------------------------- #64 pool hygiene

    /** {@code group == null} (the "let vanilla roll" path) must not touch the pool at all. */
    @Test
    public void randomEncountersDoNotRewriteTheMonsterPool() {
        assertNull("group=null 时不能动 monsterList", DevEncounterTool.applyMonsterList(null));
        DevEncounterTool.restoreMonsterList(null); // must be a no-op, not a throw
    }

    @Test
    public void nullNamesAreNotAPlaceholder() {
        assertFalse(DevEncounterTool.isPlaceholder("Cultist", null));
        assertFalse(DevEncounterTool.isPlaceholder("Cultist", new ArrayList<String>()));
    }

    @Test
    public void mixedGroupsAreNeverPlaceholders() {
        assertFalse(DevEncounterTool.isPlaceholder("Whatever",
                Arrays.asList(DevEncounterTool.APOLOGY_SLIME_CLASS, "Cultist")));
    }

    // ---------------------------------------------------------------- #108 · C7-12 several ids

    /**
     * Issue #108 · C7-12: QA sent {@code dev_encounter{ids:[Jaw Worm, Cultist]}} and only Jaw Worm
     * showed up, with nothing in the answer saying why (2 Louse, a single encounter with two
     * monsters, looked "multi-id works"). A vanilla encounter is one indivisible {@code MonsterGroup}
     * with hard-coded monster positions, so the tool mounts the first usable id — and now says so.
     */
    @Test
    public void aSecondUsableIdIsReportedInsteadOfSilentlyDropped() {
        DevEncounterTool.Plan plan = DevEncounterTool.plan(
                Arrays.asList("Jaw Worm", "Cultist"), vanillaExordium(), false);

        assertTrue(plan.ok());
        assertEquals("Jaw Worm", plan.encounterId);
        assertEquals(Arrays.asList("Jaw Worm", "Cultist"), plan.requested);
        assertEquals(Arrays.asList("Cultist"), plan.ignored);
        assertTrue(plan.unknown.isEmpty());
        assertTrue("有没上场的 id 就要说", plan.hasUnusedIds());
    }

    @Test
    public void theNoticeNamesWhatWasUsedAndWhatWasNot() {
        String notice = DevEncounterTool.idsNotice(DevEncounterTool.plan(
                Arrays.asList("Jaw Worm", "Cultist"), vanillaExordium(), false));

        assertTrue("要说清一次只开一个: " + notice, notice.contains("一次只开一个"));
        assertTrue("要点名用了哪个: " + notice, notice.contains("Jaw Worm"));
        assertTrue("要点名没用的那个: " + notice, notice.contains("Cultist"));
        assertTrue("要告诉 agent 怎么打第二个: " + notice, notice.contains("再单独调用一次"));
    }

    /** 一个 id：没有多余的话，回包也就不会多一个 notice。 */
    @Test
    public void aSingleIdNeedsNoNotice() {
        DevEncounterTool.Plan plan = DevEncounterTool.plan(
                Arrays.asList("Cultist"), vanillaExordium(), false);

        assertTrue(plan.ok());
        assertFalse(plan.hasUnusedIds());
        assertNull(DevEncounterTool.idsNotice(plan));
    }

    /** 前面的 id 不认识时仍然回退到后面那个，但「不认识」这件事必须写出来。 */
    @Test
    public void anUnknownIdBeforeTheUsedOneIsReportedToo() {
        DevEncounterTool.Plan plan = DevEncounterTool.plan(
                Arrays.asList("NoSuchEncounterXyz", "Jaw Worm", "Cultist"), vanillaExordium(), false);

        assertTrue(plan.ok());
        assertEquals("Jaw Worm", plan.encounterId);
        assertEquals(Arrays.asList("NoSuchEncounterXyz"), plan.unknown);
        assertEquals(Arrays.asList("Cultist"), plan.ignored);
        String notice = DevEncounterTool.idsNotice(plan);
        assertTrue(notice, notice.contains("不认识"));
        assertTrue(notice, notice.contains("NoSuchEncounterXyz"));
    }

    /** 一个都建不出来 = 拒绝；拒绝的文案还是那份「找不到这些遭遇」清单，不是 notice。 */
    @Test
    public void aRejectedPlanHasNoNotice() {
        DevEncounterTool.Plan plan = DevEncounterTool.plan(
                Arrays.asList("Aaa", "Bbb"), vanillaExordium(), false);

        assertFalse(plan.ok());
        assertNull(DevEncounterTool.idsNotice(plan));
    }

    @Test
    public void randomModeHasNoNotice() {
        assertNull(DevEncounterTool.idsNotice(DevEncounterTool.plan(null, null, true)));
        assertNull(DevEncounterTool.idsNotice(null));
    }

    /** 回包里的两个数组：永远存在（空数组也算），顺序跟请求一致。 */
    @Test
    public void idArraysAreNeverNullAndKeepTheirOrder() {
        JsonArray none = DevEncounterTool.stringArray(null);
        assertEquals(0, none.size());

        JsonArray ids = DevEncounterTool.stringArray(Arrays.asList("A", null, "B"));
        assertEquals("null 元素不该变成 JSON null: " + ids, 2, ids.size());
        assertEquals("A", ids.get(0).getAsString());
        assertEquals("B", ids.get(1).getAsString());
    }

    // ---------------------------------------------------------------- #112 · C8-09 unknown after the used one

    /**
     * The exact QA call from the issue: {@code ids:[Jaw Worm, NoSuchEncounterXyz, Cultist]}.
     *
     * <p>Jaw Worm is mounted; the unknown id used to be reported under {@code ignored_ids} ("that one
     * exists, ask for it again"), which is the opposite of the truth — vanilla has no such encounter.
     * "Unknown" is a property of the id, so position must not matter.</p>
     */
    @Test
    public void anUnknownIdAfterTheUsedOneIsUnknownNotIgnored() {
        DevEncounterTool.Plan plan = DevEncounterTool.plan(
                Arrays.asList("Jaw Worm", "NoSuchEncounterXyz", "Cultist"), vanillaExordium(), false);

        assertTrue(plan.ok());
        assertEquals("Jaw Worm", plan.encounterId);
        assertEquals("不认识的 id 不管排第几都要进 unknown_ids: " + plan.unknown,
                Arrays.asList("NoSuchEncounterXyz"), plan.unknown);
        assertEquals("ignored_ids 只收认识但没用的: " + plan.ignored,
                Arrays.asList("Cultist"), plan.ignored);
        assertTrue(plan.hasUnusedIds());
    }

    /** 未知 id 在头、在尾、两头都有：三种位置归到同一个 unknown 清单，且保持请求顺序。 */
    @Test
    public void unknownIdsFromBothEndsKeepTheirRequestOrder() {
        DevEncounterTool.Plan plan = DevEncounterTool.plan(
                Arrays.asList("Aaa", "Cultist", "Bbb"), vanillaExordium(), false);
        assertTrue(plan.ok());
        assertEquals("Cultist", plan.encounterId);
        assertEquals(Arrays.asList("Aaa", "Bbb"), plan.unknown);
        assertTrue("三个 id 里没有一个「认识但没用」的: " + plan.ignored, plan.ignored.isEmpty());

        DevEncounterTool.Plan trailing = DevEncounterTool.plan(
                Arrays.asList("Cultist", "Aaa", "Bbb"), vanillaExordium(), false);
        assertEquals("Cultist", trailing.encounterId);
        assertEquals(Arrays.asList("Aaa", "Bbb"), trailing.unknown);
        assertTrue(trailing.ignored.isEmpty());
    }

    /** notice 要把「不认识」和「没上场」分开说，不能把拼错的 id 说成「未使用」。 */
    @Test
    public void theNoticeSeparatesUnknownFromUnused() {
        String notice = DevEncounterTool.idsNotice(DevEncounterTool.plan(
                Arrays.asList("Jaw Worm", "NoSuchEncounterXyz", "Cultist"), vanillaExordium(), false));

        assertTrue("要点名拼错的 id: " + notice, notice.contains("NoSuchEncounterXyz"));
        assertTrue("要说「不认识」: " + notice, notice.contains("不认识"));
        assertTrue("认识的那个也要点名: " + notice, notice.contains("Cultist"));
        assertTrue("两个清单都在: " + notice, notice.contains("未使用"));

        // 只有未知 id 时，别说「未使用」。
        String unknownOnly = DevEncounterTool.idsNotice(DevEncounterTool.plan(
                Arrays.asList("Cultist", "NoSuchEncounterXyz"), vanillaExordium(), false));
        assertTrue(unknownOnly, unknownOnly.contains("不认识"));
        assertFalse("没有「认识但没用」的就不该出现未使用: " + unknownOnly,
                unknownOnly.contains("未使用"));
    }

    /** 尾部只按名字归类，绝不为了归类去构造遭遇（构造 = 真建了一组怪物）。 */
    @Test
    public void theTailIsClassifiedByNameWithoutBuildingIt() {
        FakeSource source = vanillaExordium();
        DevEncounterTool.plan(Arrays.asList("Jaw Worm", "NoSuchEncounterXyz", "Cultist"), source, false);

        assertEquals("只该构造将要上场的那个: " + source.queried,
                Arrays.asList("Jaw Worm"), source.queried);
    }

    /** 两个清单是互补的：每个请求里的 id 恰好落进其中一个，且都保持请求顺序。 */
    @Test
    public void everyRequestedIdLandsInExactlyOneList() {
        List<String> ids = Arrays.asList("Aaa", "Jaw Worm", "Bbb", "Cultist", "Ccc");
        Set<String> known = new LinkedHashSet<String>(
                Arrays.asList("Jaw Worm", "Cultist", DevEncounterTool.APOLOGY_SLIME_ID));

        List<String> unknown = DevEncounterTool.unknownIds(ids, known);
        List<String> ignored = DevEncounterTool.ignoredIds(ids, known);

        assertEquals(Arrays.asList("Aaa", "Bbb", "Ccc"), unknown);
        assertEquals(Arrays.asList("Jaw Worm", "Cultist"), ignored);
        assertEquals("一个 id 只能归到一个清单里", ids.size(), unknown.size() + ignored.size());

        // 不做去重：requested 里写了几次就报几次，跟 requested 本身的口径一致。
        assertEquals(Arrays.asList("Aaa", "Aaa"),
                DevEncounterTool.unknownIds(Arrays.asList("Aaa", "Aaa"), known));
    }

    /** 没有 known 表（source 为空）时不能崩：全当不认识，ignored 为空。 */
    @Test
    public void aMissingKnownTableMakesEverythingUnknown() {
        List<String> ids = Arrays.asList("Aaa", null, "Bbb");
        assertEquals(Arrays.asList("Aaa", "Bbb"), DevEncounterTool.unknownIds(ids, null));
        assertTrue(DevEncounterTool.ignoredIds(ids, null).isEmpty());
        assertTrue(DevEncounterTool.unknownIds(null, null).isEmpty());
        assertTrue(DevEncounterTool.ignoredIds(null, null).isEmpty());
    }
}
