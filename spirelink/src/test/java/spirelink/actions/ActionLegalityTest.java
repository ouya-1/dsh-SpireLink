package spirelink.actions;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The action legality matrix: which action types may run in which ui.
 *
 * <p>Covers issues <b>#70</b> (main_menu + proceed used to NPE and surface as INTERNAL) and
 * <b>#57</b> (hand_select + proceed used to close the card-select screen behind the effect's back),
 * plus the hand_select exit rules from <b>#74</b>.</p>
 *
 * <p>Everything here is pure: no game object is constructed, so the matrix can be pinned down
 * without a live dungeon.</p>
 */
public class ActionLegalityTest {

    // ---------------------------------------------------------------- #70 main_menu

    /**
     * Issue #70: {@code act{"type":"proceed"}} on the main menu reached
     * {@code AbstractDungeon.getCurrRoom()} with no {@code currMapNode} and threw an NPE that the
     * harness reported as {@code INTERNAL}. It must be a business rejection instead.
     */
    @Test
    public void proceedIsRejectedOnTheMainMenu() {
        assertFalse(ActionLegality.allows("main_menu", ActionTypes.PROCEED));
        String reason = ActionLegality.check("main_menu", ActionTypes.PROCEED);
        assertNotNull("必须给一句人话说明为什么不行", reason);
        assertTrue("理由里要点出 ui: " + reason, reason.contains("main_menu"));
        assertTrue("理由里要点出动作: " + reason, reason.contains(ActionTypes.PROCEED));
        assertEquals(ErrorCodes.ILLEGAL_ACTION,
                ActionLegality.violationCode("main_menu", ActionTypes.PROCEED));
    }

    @Test
    public void mainMenuKeepsEveryActionItAdvertises() {
        assertTrue(ActionLegality.allows("main_menu", ActionTypes.OPEN_MODE_SELECT));
        assertTrue(ActionLegality.allows("main_menu", ActionTypes.CONTINUE_RUN));
        assertTrue(ActionLegality.allows("main_menu", ActionTypes.ABANDON_RUN));
        assertTrue(ActionLegality.allows("main_menu", ActionTypes.CONFIRM_ABANDON));
        assertTrue(ActionLegality.allows("main_menu", ActionTypes.CANCEL_ABANDON));
    }

    @Test
    public void mainMenuDoesNotTakeInRunActions() {
        // Anything that needs a room/dungeon must not even be attempted from the menu.
        for (String type : new String[] {
                ActionTypes.PROCEED, ActionTypes.PLAY_CARD, ActionTypes.END_TURN,
                ActionTypes.CHOOSE_MAP_NODE, ActionTypes.TAKE_REWARD, ActionTypes.REST,
                ActionTypes.BUY, ActionTypes.CHOOSE_OPTION, ActionTypes.CHOOSE_HAND_CARDS,
                ActionTypes.OPEN_CHEST, ActionTypes.EMBARK, ActionTypes.CHOOSE_MODE }) {
            assertFalse("main_menu 不该放行 " + type, ActionLegality.allows("main_menu", type));
        }
    }

    // ---------------------------------------------------------------- #57 hand_select

    /**
     * Issue #57: with the card-select screen up, {@code proceed} fell through to the generic
     * "close the current screen" branch and silently tore the screen down.
     */
    @Test
    public void proceedIsRejectedWhileTheHandSelectScreenIsOpen() {
        assertFalse(ActionLegality.allows("hand_select", ActionTypes.PROCEED));
        String reason = ActionLegality.check("hand_select", ActionTypes.PROCEED);
        assertNotNull(reason);
        assertTrue("理由里要列出手牌选择能做什么: " + reason,
                reason.contains(ActionTypes.CONFIRM_HAND_SELECT));
        // Design §8.1 names HAND_SELECT_OPEN for exactly this situation.
        assertEquals(ErrorCodes.HAND_SELECT_OPEN,
                ActionLegality.violationCode("hand_select", ActionTypes.PROCEED));
    }

    @Test
    public void handSelectAllowsExactlyTheThreeHandActions() {
        assertEquals(new LinkedHashSet<String>(java.util.Arrays.asList(
                        ActionTypes.CHOOSE_HAND_CARDS,
                        ActionTypes.CONFIRM_HAND_SELECT,
                        ActionTypes.CANCEL_HAND_SELECT)),
                ActionLegality.allowedFor("hand_select"));
    }

    @Test
    public void nothingElseRunsWhileTheHandSelectScreenIsOpen() {
        for (String type : new String[] {
                ActionTypes.PROCEED, ActionTypes.PLAY_CARD, ActionTypes.END_TURN,
                ActionTypes.USE_POTION, ActionTypes.DISCARD_POTION, ActionTypes.CHOOSE_MAP_NODE,
                ActionTypes.CANCEL_GRID, ActionTypes.TAKE_REWARD }) {
            assertFalse("hand_select 下不该放行 " + type, ActionLegality.allows("hand_select", type));
            assertEquals(type + " 在 hand_select 下应当是 HAND_SELECT_OPEN",
                    ErrorCodes.HAND_SELECT_OPEN,
                    ActionLegality.violationCode("hand_select", type));
        }
    }

    @Test
    public void gridKeepsItsOwnThreeActions() {
        // Same reasoning as hand_select: a mandatory card pick must not be closable by proceed.
        assertFalse(ActionLegality.allows("grid", ActionTypes.PROCEED));
        assertTrue(ActionLegality.allows("grid", ActionTypes.CHOOSE_GRID_CARDS));
        assertTrue(ActionLegality.allows("grid", ActionTypes.CONFIRM_GRID));
        assertTrue(ActionLegality.allows("grid", ActionTypes.CANCEL_GRID));
        assertEquals(ErrorCodes.ILLEGAL_ACTION,
                ActionLegality.violationCode("grid", ActionTypes.PROCEED));
    }

    // ---------------------------------------------------------------- #83 transform

    /** Issue #83: the transform picker is a grid, so it must offer the grid actions. */
    @Test
    public void transformTakesTheGridActions() {
        assertTrue(ActionLegality.allows("transform", ActionTypes.CHOOSE_GRID_CARDS));
        assertTrue(ActionLegality.allows("transform", ActionTypes.CONFIRM_GRID));
        assertTrue(ActionLegality.allows("transform", ActionTypes.CANCEL_GRID));
        assertEquals(new LinkedHashSet<String>(java.util.Arrays.asList(
                        ActionTypes.CHOOSE_GRID_CARDS,
                        ActionTypes.CONFIRM_GRID,
                        ActionTypes.CANCEL_GRID,
                        ActionTypes.PROCEED)),
                ActionLegality.allowedFor("transform"));
    }

    /** …but proceed stays, so a transform screen can never become a dead end. */
    @Test
    public void transformKeepsProceedAsTheLastResortExit() {
        assertTrue(ActionLegality.allows("transform", ActionTypes.PROCEED));
    }

    @Test
    public void transformTakesNothingElse() {
        for (String type : new String[] {
                ActionTypes.PLAY_CARD, ActionTypes.END_TURN, ActionTypes.CHOOSE_MAP_NODE,
                ActionTypes.CHOOSE_HAND_CARDS, ActionTypes.TAKE_REWARD }) {
            assertFalse("transform 下不该放行 " + type, ActionLegality.allows("transform", type));
        }
    }

    // ---------------------------------------------------------------- other uis keep working

    @Test
    public void otherScreensStillTakeProceed() {
        for (String ui : new String[] { "map", "idle", "other", "death", "victory", "view",
                "treasure", "rest", "event", "combat_reward", "boss_reward",
                "boss_cleared", "credits", "shop", "shop_room" }) {
            assertTrue(ui + " 必须还能 proceed（不回归）", ActionLegality.allows(ui, ActionTypes.PROCEED));
        }
    }

    /**
     * C7-01: {@code card_reward} is the one reward screen with no {@code proceed}.
     *
     * <p>The planner never advertised one — skipping the pick <i>is</i> {@code skip_card} — yet the
     * matrix let {@code proceed} through and {@code ActionExecutor#proceed()} forwarded it to
     * {@code skipCard()}: an action the agent could run while {@code legal_actions} never listed it.
     * The screen keeps exactly the two actions the planner offers, so advertised ⟺ executable
     * (未广告动作一律 ILLEGAL, C7-03).</p>
     */
    @Test
    public void cardRewardTakesOnlyTheTwoAdvertisedActions() {
        assertEquals(new LinkedHashSet<String>(java.util.Arrays.asList(
                        ActionTypes.TAKE_CARD, ActionTypes.SKIP_CARD)),
                ActionLegality.allowedFor("card_reward"));
        assertFalse("card_reward 不该再放行 proceed（C7-01）",
                ActionLegality.allows("card_reward", ActionTypes.PROCEED));
        String reason = ActionLegality.check("card_reward", ActionTypes.PROCEED);
        assertNotNull(reason);
        assertTrue("拒绝里要给出真能用的动作: " + reason, reason.contains(ActionTypes.SKIP_CARD));
        assertEquals(ErrorCodes.ILLEGAL_ACTION,
                ActionLegality.violationCode("card_reward", ActionTypes.PROCEED));
    }

    /**
     * #116a: a potion reward cannot be taken while the belt is full, and the instruction the state's
     * {@code blocked_reason} and the {@code POTION_SLOT_FULL} rejection both give ("先丢掉一瓶才能拿")
     * has to be an action that really runs here. It was not: {@code discard_potion} was not in this row
     * at all, so the documented recovery path did not exist.
     *
     * <p>{@code take_reward} and {@code proceed} stay — the screen keeps its exit, and a discard is an
     * <i>extra</i> way to make the skipped reward claimable (未广告动作只多不少，见类 javadoc).</p>
     */
    @Test
    public void combatRewardAcceptsTheDiscardItsBlockedReasonAsksFor() {
        assertTrue("奖励屏要能丢药水（#116a）",
                ActionLegality.allows("combat_reward", ActionTypes.DISCARD_POTION));
        assertTrue(ActionLegality.allows("combat_reward", ActionTypes.TAKE_REWARD));
        assertTrue("出口不能被挤掉", ActionLegality.allows("combat_reward", ActionTypes.PROCEED));
        // 丢药水不是"任何界面都能干"的万能动作：仍然只在这两种 ui 上放行。
        assertTrue(ActionLegality.allows("combat", ActionTypes.DISCARD_POTION));
        assertFalse(ActionLegality.allows("card_reward", ActionTypes.DISCARD_POTION));
        assertFalse(ActionLegality.allows("map", ActionTypes.DISCARD_POTION));
    }

    @Test
    public void combatTakesOnlyCombatActions() {
        assertTrue(ActionLegality.allows("combat", ActionTypes.PLAY_CARD));
        assertTrue(ActionLegality.allows("combat", ActionTypes.END_TURN));
        assertTrue(ActionLegality.allows("combat", ActionTypes.USE_POTION));
        assertTrue(ActionLegality.allows("combat", ActionTypes.DISCARD_POTION));
        assertFalse("战斗中 proceed 没有对象（原版也没有这个按钮）",
                ActionLegality.allows("combat", ActionTypes.PROCEED));
        assertFalse(ActionLegality.allows("combat", ActionTypes.CHOOSE_MAP_NODE));
    }

    // ---------------------------------------------------------------- fail-open / internal

    @Test
    public void unmodelledUisAreNotPoliced() {
        assertNull(ActionLegality.allowedFor("unknown:SOMETHING_NEW"));
        assertTrue(ActionLegality.allows("unknown:SOMETHING_NEW", ActionTypes.PROCEED));
        assertNull(ActionLegality.check("unknown:SOMETHING_NEW", ActionTypes.PROCEED));
        assertNull(ActionLegality.allowedFor(null));
        assertTrue(ActionLegality.allows(null, ActionTypes.PROCEED));
    }

    @Test
    public void internalAndUniversalActionsAreNeverBlocked() {
        for (String type : new String[] {
                ActionTypes.VERIFY_CLAIM_RELIC, ActionTypes.VERIFY_RUN_OVER,
                ActionTypes.VERIFY_ENCOUNTER, ActionTypes.ABANDON_MID_RUN }) {
            for (String ui : new String[] { "main_menu", "hand_select", "combat", "grid", "map" }) {
                assertTrue(type + " 必须永远放行（" + ui + "）", ActionLegality.allows(ui, type));
            }
        }
    }

    @Test
    public void nullTypeIsLeftToTheExecutor() {
        // executeInner answers "缺少动作类型"; the gate must not shadow that with a different code.
        assertTrue(ActionLegality.allows("hand_select", null));
        assertNull(ActionLegality.check("hand_select", null));
    }

    // ---------------------------------------------------------------- uiOf

    @Test
    public void uiOfFollowsTheStateBuilderPriority() {
        // Tutorial tip owns everything, even on the main menu.
        assertEquals("tutorial_tip", ActionLegality.uiOf(
                new ActionLegality.Facts(true, false, "MAIN_MENU", "FTUE", null, null, false)));
        // Main-menu family.
        assertEquals("main_menu", ActionLegality.uiOf(ActionLegality.Facts.menu("MAIN_MENU")));
        assertEquals("main_menu", ActionLegality.uiOf(ActionLegality.Facts.menu("NONE")));
        assertEquals("mode_select", ActionLegality.uiOf(ActionLegality.Facts.menu("PANEL_MENU")));
        assertEquals("char_select", ActionLegality.uiOf(ActionLegality.Facts.menu("CHAR_SELECT")));
        // A screen wins over the room.
        assertEquals("hand_select", ActionLegality.uiOf(
                ActionLegality.Facts.screen("HAND_SELECT", "MonsterRoom", "COMBAT")));
        assertEquals("map", ActionLegality.uiOf(ActionLegality.Facts.screen("MAP", "MapRoom", "NONE")));
        assertEquals("combat_reward", ActionLegality.uiOf(
                ActionLegality.Facts.screen("COMBAT_REWARD", "MonsterRoom", "COMPLETE")));
        // Rooms.
        assertEquals("combat", ActionLegality.uiOf(ActionLegality.Facts.room("MonsterRoom", "COMBAT")));
        assertEquals("rest", ActionLegality.uiOf(ActionLegality.Facts.room("RestRoom", "NONE")));
        assertEquals("event", ActionLegality.uiOf(ActionLegality.Facts.room("EventRoom", "NONE")));
        assertEquals("treasure", ActionLegality.uiOf(ActionLegality.Facts.room("TreasureRoom", "NONE")));
        assertEquals("boss_cleared", ActionLegality.uiOf(
                ActionLegality.Facts.room("MonsterRoomBoss", "COMPLETE")));
        assertEquals("idle", ActionLegality.uiOf(ActionLegality.Facts.room("MonsterRoom", "COMPLETE")));
        assertEquals("other", ActionLegality.uiOf(
                new ActionLegality.Facts(false, true, null, "NONE", null, null, false)));
        assertEquals("other", ActionLegality.uiOf(null));
    }

    @Test
    public void shopSplitsOnWhetherTheMerchantScreenIsUp() {
        assertEquals("shop", ActionLegality.uiOf(
                ActionLegality.Facts.screen("SHOP", "ShopRoom", "NONE")));
        assertEquals("shop_room", ActionLegality.uiOf(
                ActionLegality.Facts.screen("NONE", "ShopRoom", "NONE")));
    }

    // ---------------------------------------------------------------- drift guards

    /**
     * No typo can hide in the table: every value must be a real {@code ActionTypes} constant.
     *
     * <p>Without this a misspelled entry silently rejects a perfectly good action, which is exactly
     * the kind of bug the matrix is supposed to prevent.</p>
     */
    @Test
    public void everyMatrixEntryIsADeclaredActionType() {
        Set<String> declared = new LinkedHashSet<String>();
        for (Field field : ActionTypes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class
                    || !Modifier.isPublic(field.getModifiers())) {
                continue;
            }
            try {
                declared.add((String) field.get(null));
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        List<String> unknown = new ArrayList<String>();
        for (String ui : ActionLegality.modelledUis()) {
            for (String type : ActionLegality.allowedFor(ui)) {
                if (!declared.contains(type)) {
                    unknown.add(ui + ":" + type);
                }
            }
        }
        assertTrue("矩阵里出现了 ActionTypes 没有的类型: " + unknown, unknown.isEmpty());
    }

    /** Every ui the planner knows how to plan must have a matrix row (no silently unpoliced ui). */
    @Test
    public void everyPlannerUiIsModelled() {
        for (String ui : new String[] { "main_menu", "mode_select", "char_select", "tutorial_tip",
                "neow", "map", "combat", "hand_select", "combat_reward", "card_reward",
                "boss_reward", "rest", "shop", "shop_room", "event", "grid", "treasure",
                "boss_cleared", "death", "victory", "view", "idle", "other" }) {
            assertNotNull(ui + " 没有建模", ActionLegality.allowedFor(ui));
        }
    }

    /**
     * Whatever {@code ActionPlanner} advertises in a ui, the matrix must accept: an advertised
     * action the executor refuses breaks design I4 ("每个 ui 都有可执行的出口").
     */
    @Test
    public void advertisedActionsAreAlwaysAllowed() {
        assertAdvertised("main_menu", ActionTypes.OPEN_MODE_SELECT, ActionTypes.CONTINUE_RUN,
                ActionTypes.ABANDON_RUN, ActionTypes.CONFIRM_ABANDON, ActionTypes.CANCEL_ABANDON);
        assertAdvertised("mode_select", ActionTypes.CHOOSE_MODE, ActionTypes.BACK);
        assertAdvertised("char_select", ActionTypes.CHOOSE_CHARACTER, ActionTypes.CHOOSE_ASCENSION,
                ActionTypes.EMBARK, ActionTypes.BACK);
        assertAdvertised("tutorial_tip", ActionTypes.DISMISS_TIP);
        assertAdvertised("neow", ActionTypes.CHOOSE_NEOW_REWARD, ActionTypes.PROCEED);
        assertAdvertised("map", ActionTypes.CHOOSE_MAP_NODE, ActionTypes.PROCEED,
                ActionTypes.ABANDON_MID_RUN);
        assertAdvertised("combat", ActionTypes.PLAY_CARD, ActionTypes.END_TURN,
                ActionTypes.USE_POTION, ActionTypes.DISCARD_POTION);
        assertAdvertised("hand_select", ActionTypes.CHOOSE_HAND_CARDS,
                ActionTypes.CONFIRM_HAND_SELECT, ActionTypes.CANCEL_HAND_SELECT);
        assertAdvertised("combat_reward", ActionTypes.TAKE_REWARD, ActionTypes.DISCARD_POTION,
                ActionTypes.PROCEED);
        assertAdvertised("card_reward", ActionTypes.TAKE_CARD, ActionTypes.SKIP_CARD);
        assertAdvertised("boss_reward", ActionTypes.CLAIM_RELIC, ActionTypes.SKIP_RELIC);
        assertAdvertised("rest", ActionTypes.REST, ActionTypes.SMITH, ActionTypes.TOKE,
                ActionTypes.LIFT, ActionTypes.PROCEED);
        assertAdvertised("shop", ActionTypes.OPEN_SHOP, ActionTypes.BUY, ActionTypes.PURGE_CARD,
                ActionTypes.LEAVE);
        assertAdvertised("event", ActionTypes.CHOOSE_OPTION, ActionTypes.EVENT_CONTINUE,
                ActionTypes.CHOOSE_MATCH_CARD, ActionTypes.PROCEED);
        assertAdvertised("grid", ActionTypes.CHOOSE_GRID_CARDS, ActionTypes.CONFIRM_GRID,
                ActionTypes.CANCEL_GRID);
        assertAdvertised("treasure", ActionTypes.OPEN_CHEST, ActionTypes.PROCEED);
        assertAdvertised("boss_cleared", ActionTypes.PROCEED);
        assertAdvertised("death", ActionTypes.PROCEED);
        assertAdvertised("victory", ActionTypes.PROCEED);
        assertAdvertised("view", ActionTypes.PROCEED);
    }

    private static void assertAdvertised(String ui, String... types) {
        for (String type : types) {
            assertTrue(ui + " 上 planner 会发 " + type + "，矩阵却不放行",
                    ActionLegality.allows(ui, type));
        }
    }

    // ---------------------------------------------------------------- #91 main menu

    /** The "可用: …" segment of a rejection message. */
    private static String availableList(String reason) {
        String marker = "可用: ";
        int at = reason.indexOf(marker);
        if (at < 0) {
            return "";
        }
        int end = reason.indexOf("）", at);
        return end < 0 ? reason.substring(at + marker.length())
                : reason.substring(at + marker.length(), end);
    }

    @Test
    public void mainMenuNoLongerOffersBack() {
        assertFalse("主菜单没有上一级可回（#91）",
                ActionLegality.allows("main_menu", ActionTypes.BACK));
        assertFalse(ActionLegality.allowedFor("main_menu").contains(ActionTypes.BACK));
        // back stays legal where it really exists
        assertTrue(ActionLegality.allows("mode_select", ActionTypes.BACK));
        assertTrue(ActionLegality.allows("char_select", ActionTypes.BACK));
    }

    /** The "可用" list must be the planner's set, not the static matrix (#91). */
    @Test
    public void rejectionListsWhatThePlannerAdvertises() {
        java.util.List<String> advertised = java.util.Arrays.asList(
                ActionTypes.OPEN_MODE_SELECT, ActionTypes.CONTINUE_RUN);
        String reason = ActionLegality.check("main_menu", ActionTypes.BACK, advertised);
        assertNotNull(reason);
        assertTrue("可用清单要来自 planner: " + reason,
                reason.contains("可用: open_mode_select continue_run"));
        assertEquals("可用清单必须逐字等于 planner 广告的集合",
                "open_mode_select continue_run", availableList(reason));
        // abandon_run is in the matrix but not advertised right now -> it must not be printed
        assertFalse("没被广告的动作不能出现在可用清单里: " + reason,
                availableList(reason).contains(ActionTypes.ABANDON_RUN));
    }

    @Test
    public void rejectionFallsBackToTheMatrixWithoutAPlannerList() {
        String reason = ActionLegality.check("main_menu", ActionTypes.BACK, null);
        assertNotNull(reason);
        assertTrue(reason.contains(ActionTypes.OPEN_MODE_SELECT));
        assertFalse("back 必须已经从矩阵和清单里消失: " + reason,
                availableList(reason).contains(ActionTypes.BACK));
    }

    /** #91: the tail sentence has to talk about the action that was actually rejected. */
    @Test
    public void tailSentenceFollowsTheRejectedAction() {
        String back = ActionLegality.check("main_menu", ActionTypes.BACK, null);
        assertNotNull(back);
        assertFalse("back 的说明不能讲 proceed: " + back, back.contains("可以 proceed"));
        assertTrue("back 要说明主菜单没有上一级: " + back, back.contains("上一级"));

        String proceed = ActionLegality.check("main_menu", ActionTypes.PROCEED, null);
        assertNotNull(proceed);
        assertTrue("proceed 才是那句老话: " + proceed, proceed.contains("可以 proceed"));

        String handProceed = ActionLegality.check("hand_select", ActionTypes.PROCEED, null);
        assertNotNull(handProceed);
        assertTrue(handProceed.contains("吞掉"));
    }

    // ---------------------------------------------------------------- #92 treasure

    @Test
    public void treasureProceedIsBlockedWhileTheChestIsUntouched() {
        assertNotNull("宝箱还没开就 proceed 会丢奖励: ",
                ActionLegality.treasureRule("treasure", ActionTypes.PROCEED, true));
        assertTrue(ActionLegality.treasureRule("treasure", ActionTypes.PROCEED, true)
                .contains(ActionTypes.OPEN_CHEST));
        assertNull("开箱之后 proceed 必须放行",
                ActionLegality.treasureRule("treasure", ActionTypes.PROCEED, false));
        assertNull(ActionLegality.treasureRule("treasure", ActionTypes.OPEN_CHEST, true));
        assertNull("只对 treasure 生效",
                ActionLegality.treasureRule("rest", ActionTypes.PROCEED, true));
        assertNull(ActionLegality.treasureRule("map", ActionTypes.PROCEED, true));
    }

    /** The rule is state-dependent, so the matrix itself must keep both actions. */
    @Test
    public void treasureMatrixKeepsOpenChestAndProceed() {
        assertTrue(ActionLegality.allows("treasure", ActionTypes.OPEN_CHEST));
        assertTrue(ActionLegality.allows("treasure", ActionTypes.PROCEED));
    }

    // ---------------------------------------------------------------- B2-12

    @Test
    public void unknownTypeGetsItsOwnCode() {
        assertEquals("UNKNOWN_ACTION_TYPE",
                ActionLegality.unknownTypeCode("no_such_action"));
        assertEquals("大小写也认作未知", "UNKNOWN_ACTION_TYPE",
                ActionLegality.unknownTypeCode("PROCEED"));
        assertNull(ActionLegality.unknownTypeCode(ActionTypes.PROCEED));
        assertNull(ActionLegality.unknownTypeCode(null));
    }

    // ---------------------------------------------------------------- #98 message nail

    /**
     * The invariant QA hit: a rejection may only list actions that would actually be accepted.
     *
     * <p>During a deck peek the ui reads as {@code tutorial_tip} while {@code legal_actions} still
     * reported {@code grid}, so the message offered {@code dismiss_tip} and then refused it. Whatever
     * the runtime does, the *list itself* must never contain an action the same matrix would reject.
     * </p>
     */
    @Test
    public void everyActionListedAsAvailableWouldActuallyBeAccepted() {
        for (String ui : ActionLegality.modelledUis()) {
            String reason = ActionLegality.check(ui, "__not_an_action__", null);
            assertNotNull("每个已建模的 ui 都应该拒绝一个胡编的类型: " + ui, reason);
            String listed = availableList(reason);
            assertFalse("可用清单不能是空的: " + ui, listed.isEmpty());
            for (String type : listed.split(" ")) {
                if (type.isEmpty()) {
                    continue;
                }
                assertNull("ui=" + ui + " 的可用清单里列了会被拒的动作: " + type,
                        ActionLegality.check(ui, type));
            }
        }
    }
}