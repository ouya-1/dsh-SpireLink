package spirelink.actions;

import org.junit.Test;
import spirelink.state.UiNames;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * #51: after a human opens a pile/deck view and the agent sends {@code dismiss_tip}, the agent must
 * land back on the screen the player really is on — the map, the combat, or a live Neow dialog —
 * instead of the stale {@code AbstractDungeon.previousScreen} (in the reported run the already
 * finished {@code NEOW_UNLOCK} dialog, so {@code get_state} answered {@code ui=neow} one step after
 * the agent had been standing on the map).
 *
 * <p>The decision is a pure function ({@link ActionExecutor#selectCloseTarget(String, String,
 * boolean, boolean)}) so the four contexts can be checked here without a running game: map context,
 * combat context, a real Neow/event dialog, and a view screen as the previous screen.
 */
public class DismissCloseTargetTest {

    /** The four view screens {@code dismiss_tip} closes (#50). */
    private static final String[] VIEW_SCREENS = {
            "MASTER_DECK_VIEW", // 牌组
            "DISCARD_VIEW",     // 弃牌堆
            "EXHAUST_VIEW",     // 消耗堆
            "GAME_DECK_VIEW",   // 抽牌堆
    };

    /** Room phases as the game names them ({@code AbstractRoom.RoomPhase}). */
    private static final String COMBAT = "COMBAT";
    private static final String EVENT = "EVENT";
    private static final String COMPLETE = "COMPLETE";

    private static ActionExecutor.CloseTarget target(String previous, String phase,
                                                     boolean mapUnderneath) {
        return ActionExecutor.selectCloseTarget(previous, phase, mapUnderneath, true);
    }

    // ------------------------------------------------------------------ 地图语境 → map

    /**
     * The bug itself: the player is on the map, vanilla left {@code previousScreen} at {@code NONE}
     * (the deck view was opened from a <i>dismissable</i> map, which only flips {@code screenSwap}),
     * and the room behind is still the finished Neow room. Closing must land on the map.
     */
    @Test
    public void mapContextLandsOnTheMapEvenWithAStaleNonePreviousScreen() {
        assertEquals("#51 地图语境必须回到 map",
                ActionExecutor.CloseTarget.MAP, target("NONE", COMPLETE, true));
        assertEquals("previousScreen 为空时同样要回到地图",
                ActionExecutor.CloseTarget.MAP, target(null, COMPLETE, true));
        assertEquals("非 dismissable 地图时 vanilla 记下的 previousScreen=MAP 也要回到地图",
                ActionExecutor.CloseTarget.MAP, target("MAP", COMPLETE, true));
    }

    /**
     * The root cause quoted in the issue: {@code previousScreen} still points at the {@code NEOW}
     * unlock dialog of a blessing that ended before the player walked onto the map. The map
     * underneath wins, because that is where the player really is.
     */
    @Test
    public void mapContextOverridesAStaleNeowUnlockPreviousScreen() {
        assertEquals("#51 不能退回已结束的 Neow 屏",
                ActionExecutor.CloseTarget.MAP, target("NEOW_UNLOCK", COMPLETE, true));
        assertEquals("已结束的解锁提示同样不能当落点",
                ActionExecutor.CloseTarget.MAP, target("UNLOCK", COMPLETE, true));
    }

    /** The map wins over the room phase too: a finished room is not a reason to skip the map. */
    @Test
    public void mapContextIsIndependentOfTheFinishedRoomPhase() {
        assertEquals(ActionExecutor.CloseTarget.MAP, target("NONE", "INCOMPLETE", true));
        assertEquals(ActionExecutor.CloseTarget.MAP, target("NONE", null, true));
    }

    // ------------------------------------------------------------------ 战斗语境 → combat

    /**
     * Mid-fight there is no screen at all: combat is a room phase, so the close must leave
     * {@code screen=NONE} and {@code ui} comes from the room ({@code combat}).
     */
    @Test
    public void combatContextLandsOnNoScreen() {
        assertEquals("战斗语境必须回到 combat（屏 = NONE）",
                ActionExecutor.CloseTarget.NONE, target("NONE", COMBAT, false));
        assertEquals(ActionExecutor.CloseTarget.NONE, target(null, COMBAT, false));
        assertEquals("上一屏是已结束的对话框时更要清成 NONE",
                ActionExecutor.CloseTarget.NONE, target("NEOW_UNLOCK", COMBAT, false));
    }

    // ------------------------------------------------------------------ 真 Neow 事件 → neow

    /**
     * A live Neow blessing is a room-level dialog, not a screen ({@code state-neow.json} has
     * {@code screen_type=NONE} + {@code room_phase=EVENT} + {@code ui=neow}). Closing the view must
     * therefore leave no screen behind, and the room keeps answering {@code ui=neow}.
     */
    @Test
    public void liveNeowContextLandsOnNoScreen() {
        assertEquals("真 Neow 事件中：屏应为 NONE，ui 由房间给 neow",
                ActionExecutor.CloseTarget.NONE, target("NONE", EVENT, false));
        assertEquals(ActionExecutor.CloseTarget.NONE, target(null, EVENT, false));
        assertEquals("#51 不能靠 previousScreen 把已结束的对话框重新翻出来",
                ActionExecutor.CloseTarget.NONE, target("NEOW_UNLOCK", EVENT, false));
    }

    /**
     * Pins the contract the {@code NONE} target relies on: {@code UiNames} gives no ui for
     * {@code screen=NONE}, so with no screen up the room decides — exactly how {@code ui=neow}
     * keeps working during a real blessing and {@code ui=combat} during a fight.
     */
    @Test
    public void noScreenMeansTheRoomDecidesTheUi() {
        assertNull("screen=NONE 不解释成任何界面，ui 由房间决定", UiNames.ofScreen("NONE"));
        assertNotNull("地图仍是自己的屏", UiNames.ofScreen("MAP"));
    }

    // ------------------------------------------------------------------ 视图屏本身

    /**
     * A view can only have another view behind it (the human clicked two pile buttons in a row).
     * Closing must never reopen a view — with the map underneath it is the map, otherwise the room.
     */
    @Test
    public void aViewScreenIsNeverARestoreTarget() {
        for (String view : VIEW_SCREENS) {
            assertEquals(view + " 不应该被恢复成视图屏（地图在上面）",
                    ActionExecutor.CloseTarget.MAP, target(view, COMPLETE, true));
            assertEquals(view + " 不应该被恢复成视图屏（房间是语境）",
                    ActionExecutor.CloseTarget.NONE, target(view, COMPLETE, false));
            assertEquals(view + " 在战斗里更不能恢复",
                    ActionExecutor.CloseTarget.NONE, target(view, COMBAT, false));
        }
    }

    /** The views still own the {@code tutorial_tip} slot (#50); a reward screen keeps its own ui. */
    @Test
    public void viewScreensShareTheTutorialTipSlot() {
        for (String view : VIEW_SCREENS) {
            assertEquals(view, "tutorial_tip", UiNames.ofScreen(view));
        }
        // A real decision screen is not a view: closing a view on top of it must restore it.
        assertEquals(ActionExecutor.CloseTarget.RESTORE, target("CARD_REWARD", COMPLETE, false));
        assertEquals(ActionExecutor.CloseTarget.RESTORE, target("GRID", COMBAT, false));
    }

    // ------------------------------------------------------------------ 不回归其它路径

    /**
     * Every screen vanilla records when it opens a view on top of it stays restorable: the agent must
     * still come back to the reward it was picking from (that path already worked before #51).
     */
    @Test
    public void realDecisionScreensAreStillRestored() {
        String[] restorable = {
                "COMBAT_REWARD", "CARD_REWARD", "BOSS_REWARD", "GRID", "HAND_SELECT", "SHOP",
                "CHOOSE_ONE", "TRANSFORM", "SETTINGS", "INPUT_SETTINGS", "DEATH", "VICTORY",
        };
        for (String screen : restorable) {
            assertEquals(screen + " 应当交回 vanilla 恢复",
                    ActionExecutor.CloseTarget.RESTORE, target(screen, COMPLETE, false));
        }
    }

    /**
     * Mid-fight only the card-selection screens can really be underneath: rewards only appear once the
     * room phase is COMPLETE, so a reward screen recorded in {@code previousScreen} during combat is a
     * leftover and must not be restored — that would fake a decision the player does not have.
     */
    @Test
    public void combatOnlyRestoresTheScreensThatCanBeOpenDuringAFight() {
        String[] live = {"GRID", "HAND_SELECT", "CHOOSE_ONE"};
        for (String screen : live) {
            assertEquals(screen + " 在战斗里是真实决策",
                    ActionExecutor.CloseTarget.RESTORE, target(screen, COMBAT, false));
        }
        String[] leftovers = {"COMBAT_REWARD", "CARD_REWARD", "BOSS_REWARD", "SHOP", "SETTINGS"};
        for (String screen : leftovers) {
            assertEquals(screen + " 在战斗里只可能是残留，不能恢复",
                    ActionExecutor.CloseTarget.NONE, target(screen, COMBAT, false));
        }
    }

    /** Nothing open means nothing to close: the target must not invent a screen. */
    @Test
    public void nothingOpenMeansNoTarget() {
        assertSame(ActionExecutor.CloseTarget.NONE,
                ActionExecutor.selectCloseTarget("MAP", COMPLETE, true, false));
        assertSame(ActionExecutor.CloseTarget.NONE,
                ActionExecutor.selectCloseTarget(null, EVENT, false, false));
    }

    // ------------------------------------------- 关屏之后：落点没人认领时按「玩家在哪」兜底

    private static ActionExecutor.CloseTarget fallback(String roomType, String phase) {
        return ActionExecutor.selectFallbackTarget(roomType, phase, false);
    }

    /**
     * The real-machine failure of round 1, reproduced as the two decisions the executor makes.
     *
     * <p>Log on the new jar: {@code previous=null target=NONE closed=true screen=NONE}. Vanilla had
     * closed the map when the human opened the view (dismissable map → TopPanel calls
     * {@code closeCurrentScreen()} first), so {@code mapUnderneath} is false, {@code previousScreen} is
     * null, and the close lands on {@code screen=NONE}; {@code deriveUi()} then answers from the room
     * behind — a finished NeowRoom — and the agent read {@code ui=neow}.
     *
     * <p>So the pre-close target is NONE (nothing to restore) and the post-close fallback must say
     * MAP, which is what makes {@code get_state} answer {@code ui=map} again.
     */
    @Test
    public void theReportedFailureLandsOnTheMapAfterTheClose() {
        assertEquals("关屏前：previousScreen=null + 地图确实不在下面 → 没有可恢复的界面",
                ActionExecutor.CloseTarget.NONE,
                ActionExecutor.selectCloseTarget(null, COMPLETE, false, true));
        assertEquals("关屏后：房间是已完成的 NeowRoom → 地图才是玩家该在的地方",
                ActionExecutor.CloseTarget.MAP, fallback("NeowRoom", COMPLETE));
    }

    /** Same conditions but mid-fight: screen=NONE is the combat HUD, the map must never appear. */
    @Test
    public void theCombatCaseOfTheSameShapeStaysOnNoScreen() {
        assertEquals(ActionExecutor.CloseTarget.NONE,
                ActionExecutor.selectCloseTarget(null, COMBAT, false, true));
        assertEquals("战斗里绝不能开地图",
                ActionExecutor.CloseTarget.NONE, fallback("MonsterRoom", COMBAT));
        assertEquals(ActionExecutor.CloseTarget.NONE, fallback("MonsterRoomElite", COMBAT));
        assertEquals("Boss 战时也不能开",
                ActionExecutor.CloseTarget.NONE, fallback("MonsterRoomBoss", COMBAT));
    }

    /** A live Neow/event dialog is still the room's: the fallback must not steal it for the map. */
    @Test
    public void theLiveNeowCaseOfTheSameShapeKeepsTheDialog() {
        assertEquals(ActionExecutor.CloseTarget.NONE,
                ActionExecutor.selectCloseTarget(null, EVENT, false, true));
        assertEquals("Neow 事件进行中：ui 由房间给 neow，不能开地图",
                ActionExecutor.CloseTarget.NONE, fallback("NeowRoom", EVENT));
        assertEquals(ActionExecutor.CloseTarget.NONE, fallback("EventRoom", EVENT));
    }

    /** Finished rooms whose next step really is the map. */
    @Test
    public void finishedRoomsFallBackToTheMap() {
        String[] leadsToMap = {
                "NeowRoom", "MonsterRoom", "MonsterRoomElite", "EventRoom", "RestRoom", "EmptyRoom",
        };
        for (String type : leadsToMap) {
            assertEquals(type + " 完成后下一步就是地图",
                    ActionExecutor.CloseTarget.MAP, fallback(type, COMPLETE));
        }
    }

    /**
     * Rooms where the map would walk past something: the act boss (proceed leads to the treasure room
     * and its relic), both treasure rooms (the chest), victory, and the shop ({@code ui=shop_room} is
     * a real {@code screen=NONE} + {@code phase=COMPLETE} state in which the merchant still sells).
     */
    @Test
    public void roomsWithSomethingLeftNeverJumpToTheMap() {
        String[] keep = {
                "MonsterRoomBoss", "TreasureRoomBoss", "TreasureRoom", "VictoryRoom", "TrueVictoryRoom",
                "ShopRoom",
        };
        for (String type : keep) {
            assertEquals(type + " 还有没做完的事，不能开地图",
                    ActionExecutor.CloseTarget.NONE, fallback(type, COMPLETE));
        }
        // An unclaimed boss relic blocks the map even if the room class is unexpected.
        assertEquals(ActionExecutor.CloseTarget.NONE,
                ActionExecutor.selectFallbackTarget("MonsterRoom", COMPLETE, true));
    }

    /** Unknown phase / no run: never invent a map. */
    @Test
    public void unknownContextFallsBackToNoScreen() {
        assertEquals(ActionExecutor.CloseTarget.NONE, fallback("NeowRoom", "INCOMPLETE"));
        assertEquals(ActionExecutor.CloseTarget.NONE, fallback("EventRoom", "EVENT"));
        assertEquals(ActionExecutor.CloseTarget.NONE, fallback(null, COMPLETE));
        assertEquals(ActionExecutor.CloseTarget.NONE, fallback("", COMPLETE));
        assertEquals(ActionExecutor.CloseTarget.NONE, fallback("NeowRoom", null));
    }

    /**
     * The two decisions the executor makes, in order, for the three scenarios the lead verifies on
     * the real machine: the second one only runs when the close actually landed on {@code screen=NONE}.
     */
    private static ActionExecutor.CloseTarget finalTarget(String previousScreen, String roomType,
                                                          String phase, boolean mapUnderneath) {
        ActionExecutor.CloseTarget target =
                ActionExecutor.selectCloseTarget(previousScreen, phase, mapUnderneath, true);
        if (target == ActionExecutor.CloseTarget.MAP || target == ActionExecutor.CloseTarget.RESTORE) {
            return target;
        }
        return ActionExecutor.selectFallbackTarget(roomType, phase, false);
    }

    /** 验收三条：地图语境 → MAP、战斗语境 → NONE（ui=combat）、真 Neow → NONE（ui=neow）。 */
    @Test
    public void theThreeAcceptanceContextsEndWhereTheyShould() {
        assertEquals("地图点牌组 → dismiss_tip → ui=map",
                ActionExecutor.CloseTarget.MAP, finalTarget(null, "NeowRoom", COMPLETE, false));
        assertEquals("战斗中点牌组 → dismiss_tip → ui=combat",
                ActionExecutor.CloseTarget.NONE, finalTarget(null, "MonsterRoom", COMBAT, false));
        assertEquals("Neow 事件中点牌组 → dismiss_tip → ui=neow",
                ActionExecutor.CloseTarget.NONE, finalTarget(null, "NeowRoom", EVENT, false));
    }

    /** The map target still means "ui=map" and NONE still means "the room decides the ui". */
    @Test
    public void theTwoFallbackOutcomesMatchTheUiContract() {
        assertEquals("map", UiNames.ofScreen("MAP"));
        assertNull("NONE 没有 ui，房间说了算", UiNames.ofScreen("NONE"));
    }
}
