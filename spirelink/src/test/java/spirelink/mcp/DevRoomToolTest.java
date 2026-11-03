package spirelink.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Issue #45: dev_room{type:"monster"} delegates to DevEncounterTool, but the caller of dev_room sees
 * room semantics. The failure text used to be DevEncounterTool's own wording (and, in the build the
 * issue was reported against, "跳转失败: ..."), so no dev_room prefix matched it.
 */
public class DevRoomToolTest {

    private static JsonObject failure(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("error", "NOT_READY");
        json.addProperty("message", message);
        return json;
    }

    @Test
    public void encounterFailureIsRewordedToTheRoomPrefix() {
        JsonObject out = DevRoomTool.alignEncounterFailure(
                failure("进入遭遇失败: java.lang.NullPointerException"));
        assertEquals("进入房间失败: java.lang.NullPointerException",
                out.get("message").getAsString());
        assertEquals("NOT_READY", out.get("error").getAsString());
        assertFalse(out.get("ok").getAsBoolean());
    }

    @Test
    public void legacyJumpFailureQuotedInTheIssueIsRewordedToo() {
        JsonObject out = DevRoomTool.alignEncounterFailure(
                failure("跳转失败: java.lang.NullPointerException"));
        assertEquals("进入房间失败: java.lang.NullPointerException",
                out.get("message").getAsString());
    }

    @Test
    public void alreadyAlignedMessageIsLeftAlone() {
        JsonObject in = failure("进入房间失败: java.lang.IllegalStateException: 房间里没有怪物");
        assertSame(in, DevRoomTool.alignEncounterFailure(in));
        assertEquals("进入房间失败: java.lang.IllegalStateException: 房间里没有怪物",
                in.get("message").getAsString());
    }

    @Test
    public void unrelatedMessagesAreNotRewritten() {
        JsonObject notReady = failure("现在不能切房间: 刚切过房间，等特效结束（还需 1200ms）");
        assertSame(notReady, DevRoomTool.alignEncounterFailure(notReady));
        assertEquals("现在不能切房间: 刚切过房间，等特效结束（还需 1200ms）",
                notReady.get("message").getAsString());
    }

    @Test
    public void successPayloadIsReturnedAsIs() {
        JsonObject ok = new JsonObject();
        ok.addProperty("ok", true);
        ok.addProperty("encounter", "random");
        ok.addProperty("phase", "COMBAT");
        assertSame(ok, DevRoomTool.alignEncounterFailure(ok));
        assertTrue(ok.get("ok").getAsBoolean());
        assertFalse(ok.has("message"));
    }

    @Test
    public void failuresWithoutMessageStayUntouched() {
        JsonObject disabled = new JsonObject();
        disabled.addProperty("ok", false);
        disabled.addProperty("error", "DEV_TOOL_DISABLED");
        assertSame(disabled, DevRoomTool.alignEncounterFailure(disabled));
        assertFalse(disabled.has("message"));
    }

    @Test
    public void nullResultIsPassedThrough() {
        assertNull(DevRoomTool.alignEncounterFailure(null));
    }

    // ---------------------------------------------------------------- #64 plan phase

    /** Fake class-existence oracle: no class loader, no game. */
    private static DevRoomTool.RoomLookup rooms(final String... existing) {
        final java.util.Set<String> names =
                new java.util.LinkedHashSet<String>(java.util.Arrays.asList(existing));
        return new DevRoomTool.RoomLookup() {
            @Override
            public boolean exists(String simpleName) {
                return names.contains(simpleName);
            }
        };
    }

    private static final DevRoomTool.RoomLookup VANILLA = rooms(
            "RestRoom", "ShopRoom", "TreasureRoom", "EventRoom", "MonsterRoom",
            "MonsterRoomElite", "MonsterRoomBoss", "NeowRoom");

    @Test
    public void missingTypeIsBadRequest() {
        for (String type : new String[] {null, "", "   "}) {
            DevRoomTool.RoomPlan plan = DevRoomTool.plan(type, true, VANILLA);
            assertFalse(plan.ok());
            assertEquals("BAD_REQUEST", plan.errorCode);
        }
    }

    @Test
    public void noMapNodeIsNotReady() {
        DevRoomTool.RoomPlan plan = DevRoomTool.plan("rest", false, VANILLA);
        assertFalse(plan.ok());
        assertEquals("NOT_READY", plan.errorCode);
        assertTrue(plan.errorMessage.contains("地图节点"));
    }

    @Test
    public void unknownRoomTypeIsRoomNotFound() {
        DevRoomTool.RoomPlan plan = DevRoomTool.plan("nonsense", true, VANILLA);
        assertFalse(plan.ok());
        assertEquals("ROOM_NOT_FOUND", plan.errorCode);
        assertTrue("要把试过的类名说出来: " + plan.errorMessage,
                plan.errorMessage.contains("NonsenseRoom"));
    }

    @Test
    public void plainRoomsMapToTheirClassNames() {
        DevRoomTool.RoomPlan rest = DevRoomTool.plan("rest", true, VANILLA);
        assertTrue(rest.ok());
        assertEquals(DevRoomTool.RoomPlan.KIND_ROOM, rest.kind);
        assertEquals("RestRoom", rest.simpleName);

        assertEquals("TreasureRoom", DevRoomTool.plan("treasure", true, VANILLA).simpleName);
        assertEquals("EventRoom", DevRoomTool.plan("event", true, VANILLA).simpleName);
        assertEquals("NeowRoom", DevRoomTool.plan("neow", true, VANILLA).simpleName);
    }

    /** Vanilla has no EliteRoom / BossRoom; the aliases must keep pointing at the right classes. */
    @Test
    public void eliteAndBossAliasesAreMapped() {
        DevRoomTool.RoomPlan elite = DevRoomTool.plan("elite", true, VANILLA);
        assertTrue(elite.ok());
        assertEquals("MonsterRoomElite", elite.simpleName);

        DevRoomTool.RoomPlan boss = DevRoomTool.plan("boss", true, VANILLA);
        assertTrue(boss.ok());
        assertEquals("MonsterRoomBoss", boss.simpleName);
    }

    @Test
    public void monsterAndFightDelegateToTheEncounterTool() {
        DevRoomTool.RoomPlan monster = DevRoomTool.plan("monster", true, VANILLA);
        assertTrue(monster.ok());
        assertTrue(monster.isEncounter());
        assertNull("monster 分支没有自己造房间", monster.simpleName);

        assertTrue(DevRoomTool.plan("fight", true, VANILLA).isEncounter());
        // …and it needs a map node just like every other type (#45 kept this contract).
        assertEquals("NOT_READY", DevRoomTool.plan("monster", false, VANILLA).errorCode);
    }

    @Test
    public void victoryAndBossRewardAreSpecialKinds() {
        assertEquals(DevRoomTool.RoomPlan.KIND_VICTORY,
                DevRoomTool.plan("victory", true, VANILLA).kind);
        assertEquals(DevRoomTool.RoomPlan.KIND_VICTORY,
                DevRoomTool.plan("true_victory", true, VANILLA).kind);
        assertEquals(DevRoomTool.RoomPlan.KIND_VICTORY,
                DevRoomTool.plan("winscreen", true, VANILLA).kind);
        assertEquals(DevRoomTool.RoomPlan.KIND_BOSS_REWARD,
                DevRoomTool.plan("boss_reward", true, VANILLA).kind);
        assertEquals(DevRoomTool.RoomPlan.KIND_BOSS_REWARD,
                DevRoomTool.plan("boss_relic", true, VANILLA).kind);
        assertEquals("NOT_READY", DevRoomTool.plan("victory", false, VANILLA).errorCode);
        assertEquals("NOT_READY", DevRoomTool.plan("boss_reward", false, VANILLA).errorCode);
    }

    /**
     * Issue #64: the plan phase is pure — it decides everything a rejection needs without reading
     * or writing a single {@code AbstractDungeon} field. Passing {@code hasMapNode=false} proves the
     * caller owns that read, so a rejected call cannot have touched the room/nextRoom/pools.
     */
    @Test
    public void planCarriesEveryRejectionWithoutTouchingTheDungeon() {
        DevRoomTool.RoomPlan plan = DevRoomTool.plan("shop", false, VANILLA);
        assertFalse(plan.ok());
        assertEquals("NOT_READY", plan.errorCode);
        assertFalse("被拒的计划没有可应用的目标", plan.isEncounter());
        assertNull(plan.kind);
        assertNull(plan.simpleName);
        assertNull(plan.type);
    }

    @Test
    public void typeEchoIsPreservedForTheSuccessPayload() {
        DevRoomTool.RoomPlan plan = DevRoomTool.plan("REST", true, VANILLA);
        assertTrue(plan.ok());
        assertEquals("REST", plan.type);
        assertEquals("RestRoom", plan.simpleName);
    }

    @Test
    public void nullLookupIsARoomNotFoundInsteadOfAnNpe() {
        DevRoomTool.RoomPlan plan = DevRoomTool.plan("rest", true, null);
        assertFalse(plan.ok());
        assertEquals("ROOM_NOT_FOUND", plan.errorCode);
    }

    // ---------------------------------------------------------------- #108 · C7-08 victory mount

    /**
     * Issue #108 · C7-08: {@code dev_room{type:"victory"}} used to install only the
     * {@code VictoryScreen} and leave the previous room mounted. Vanilla shows that screen over a
     * {@code VictoryRoom}; with a cleared boss still in {@code currMapNode.room},
     * {@code act proceed} ran the boss transition (run stays alive at {@code ui=map}) instead of the
     * run-over branch (main menu) — the same call, two outcomes.
     *
     * <p>The mount itself needs a live dungeon, but the property that makes proceed deterministic is
     * pure: {@link DevRoomTool#proceedReachesRunOver} mirrors the branch order of
     * {@code ActionExecutor.proceed()} and is asserted below for both the old and the new state.</p>
     */
    @Test
    public void aMountedVictoryRoomSendsProceedToTheMainMenu() {
        // 修复后的状态：vanilla 的 VictoryRoom（构造器给的 phase 是 EVENT）在胜利屏下面。
        assertTrue(DevRoomTool.proceedReachesRunOver(
                DevRoomTool.VICTORY_ROOM, DevRoomTool.VICTORY_ROOM_PHASE, "VICTORY"));
        // 真机里那个「applied 之后仍停在本局 ui=map」的局面：房型还是打完的 Boss 房。
        assertFalse("Boss 房 phase=COMPLETE 会先走 Boss 过场分支",
                DevRoomTool.proceedReachesRunOver("MonsterRoomBoss", "COMPLETE", "VICTORY"));
        assertFalse(DevRoomTool.proceedReachesRunOver("MonsterRoom", "COMPLETE", "VICTORY"));
        assertFalse(DevRoomTool.proceedReachesRunOver("MonsterRoomElite", "COMPLETE", "VICTORY"));
        // 一个 COMPLETE 的 VictoryRoom 同样会被那条分支吃掉，所以挂载的 phase 不能是 COMPLETE。
        assertFalse(DevRoomTool.proceedReachesRunOver(
                DevRoomTool.VICTORY_ROOM, "COMPLETE", "VICTORY"));
    }

    @Test
    public void theVictoryMountNamesRealVanillaTypes() {
        assertEquals("VictoryRoom", DevRoomTool.VICTORY_ROOM);
        assertEquals("EVENT", DevRoomTool.VICTORY_ROOM_PHASE);
        try {
            Class<?> room = Class.forName("com.megacrit.cardcrawl.rooms." + DevRoomTool.VICTORY_ROOM,
                    false, DevRoomToolTest.class.getClassLoader());
            assertTrue("dev_room victory 挂的必须是 AbstractRoom: " + room,
                    com.megacrit.cardcrawl.rooms.AbstractRoom.class.isAssignableFrom(room));
        } catch (ClassNotFoundException e) {
            throw new AssertionError("vanilla 没有 " + DevRoomTool.VICTORY_ROOM + " 这个房间类", e);
        }
    }

    /** 只有 run-over 屏 / VictoryRoom 才能走到结算分支，别的界面各有各的分支在前面。 */
    @Test
    public void screensWithTheirOwnBranchNeverReachTheRunOverBranch() {
        for (String screen : new String[] {"MAP", "COMBAT_REWARD", "CARD_REWARD", "BOSS_REWARD"}) {
            assertFalse(screen, DevRoomTool.proceedReachesRunOver(
                    DevRoomTool.VICTORY_ROOM, DevRoomTool.VICTORY_ROOM_PHASE, screen));
        }
        assertTrue(DevRoomTool.proceedReachesRunOver(null, null, "DEATH"));
        assertTrue(DevRoomTool.proceedReachesRunOver(null, null, "CREDITS"));
        assertFalse("普通界面 + 普通房间：proceed 不是回主菜单",
                DevRoomTool.proceedReachesRunOver("ShopRoom", "COMPLETE", "NONE"));
        // 真机上没有房间（主菜单）时 proceed 在更前面就被拒了，这条镜像只回答「会不会走到结算分支」。
        assertFalse(DevRoomTool.proceedReachesRunOver("ShopRoom", "COMPLETE", null));
    }
}
