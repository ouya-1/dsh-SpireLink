package spirelink.mcp;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B2-15 (QA round 2): dev fights started with the wrong hand and empty piles.
 *
 * <ul>
 *   <li>{@code dev_encounter} answered {@code readiness{hand:5, draw:5}} but the real state a moment
 *       later was {@code hand=10}, piles 0/0/0 — the tool drew the opening hand by hand
 *       <em>and</em> vanilla's room start block drew another {@code gameHandSize};</li>
 *   <li>{@code dev_room{type:"boss"}} mounted a fight with {@code hand=0}, {@code energy=0} and
 *       empty piles: the generic room path never ran {@code preBattlePrep()} at all, so there was no
 *       deck, no energy and nothing to draw.</li>
 * </ul>
 *
 * <p>Both are pinned here through the pure plan ({@link DevEncounterTool#planCombatStart}) and the
 * pure readiness payload ({@link DevEncounterTool#readinessJson}), so the numbers in the reply are
 * the numbers the game has.</p>
 */
public class DevCombatStartTest {

    @Test
    public void theCombatStartPlanIsTheVanillaSequence() {
        assertEquals(Arrays.asList(
                DevEncounterTool.CombatStartStep.PRE_BATTLE_PREP,
                DevEncounterTool.CombatStartStep.ENTER_COMBAT,
                DevEncounterTool.CombatStartStep.QUEUE_TURN_ONE,
                DevEncounterTool.CombatStartStep.SETTLE_ACTION_QUEUE),
                DevEncounterTool.planCombatStart(true, true));
    }

    /**
     * B2-15's regression guard: the opening hand must come from vanilla's queued
     * {@code DrawCardAction} only. If anyone ever adds a "draw it myself" step to this enum, this
     * test fails — that step is exactly what produced {@code hand=10}.
     */
    @Test
    public void theCombatStartPlanHasNoManualDrawStep() {
        assertEquals("起手抽牌只能由原版队列完成（B2-15 的 hand=10 就是手工多抽了一次）",
                Arrays.asList(
                        DevEncounterTool.CombatStartStep.PRE_BATTLE_PREP,
                        DevEncounterTool.CombatStartStep.ENTER_COMBAT,
                        DevEncounterTool.CombatStartStep.QUEUE_TURN_ONE,
                        DevEncounterTool.CombatStartStep.SETTLE_ACTION_QUEUE),
                Arrays.asList(DevEncounterTool.CombatStartStep.values()));
        for (DevEncounterTool.CombatStartStep step : DevEncounterTool.CombatStartStep.values()) {
            assertFalse("不允许出现手工抽牌步骤: " + step, step.name().contains("DRAW"));
        }
    }

    @Test
    public void theFirstStepIsThePrepAndTheLastOneIsTheSettle() {
        List<DevEncounterTool.CombatStartStep> steps = DevEncounterTool.planCombatStart(true, true);
        assertEquals(DevEncounterTool.CombatStartStep.PRE_BATTLE_PREP, steps.get(0));
        assertEquals(DevEncounterTool.CombatStartStep.SETTLE_ACTION_QUEUE,
                steps.get(steps.size() - 1));
    }

    @Test
    public void withoutAPlayerOrMonstersNothingIsPlanned() {
        assertTrue(DevEncounterTool.planCombatStart(false, true).isEmpty());
        assertTrue(DevEncounterTool.planCombatStart(true, false).isEmpty());
        assertTrue(DevEncounterTool.planCombatStart(false, false).isEmpty());
    }

    @Test
    public void theSettleBudgetIsBounded() {
        assertTrue(DevEncounterTool.MAX_SETTLE_FRAMES > 0);
        // 60 fps 下原版的 1 秒停顿约 60 帧；预算必须远大于它，但不能无限。
        assertTrue(DevEncounterTool.MAX_SETTLE_FRAMES >= 120);
    }

    // ------------------------------------------------------------ readiness = the live numbers

    @Test
    public void readinessReportsExactlyTheNumbersItWasGiven() {
        JsonObject readiness = DevEncounterTool.readinessJson(5, 5, 0, 0, 3, 3);
        assertTrue(readiness.get("ready").getAsBoolean());
        assertEquals(5, readiness.get("hand").getAsInt());
        assertEquals(5, readiness.get("draw").getAsInt());
        assertEquals(0, readiness.get("discard").getAsInt());
        assertEquals(0, readiness.get("exhaust").getAsInt());
        assertEquals(3, readiness.get("energy").getAsInt());
        assertEquals(3, readiness.get("energy_max").getAsInt());
    }

    /** B2-15 实测 A：回包写 hand=5，真实状态却是 hand=10 —— 现在回包只说实数。 */
    @Test
    public void readinessNeverClaimsAMadeUpHand() {
        JsonObject ten = DevEncounterTool.readinessJson(10, 0, 0, 0, 3, 3);
        assertEquals(10, ten.get("hand").getAsInt());
        assertEquals(0, ten.get("draw").getAsInt());
        JsonObject zero = DevEncounterTool.readinessJson(0, 0, 0, 0, 0, 0);
        assertFalse("hand=0 且 energy_max=0 不能算 ready", zero.get("ready").getAsBoolean());
    }

    @Test
    public void aFightIsReadyOnlyWithACardInHandAndAnEnergyPool() {
        assertTrue(DevEncounterTool.combatReady(5, 3));
        assertTrue("抽牌堆 0 张是合法的（弃牌堆会洗回去），不该因此判不可打",
                DevEncounterTool.combatReady(5, 3));
        assertFalse(DevEncounterTool.combatReady(0, 3));
        assertFalse(DevEncounterTool.combatReady(5, 0));
        assertFalse(DevEncounterTool.combatReady(-1, -1));
    }

    @Test
    public void theSettleCheckRefusesUnreadableArguments() {
        // 防御性：拿不到玩家/管理器时不能声称「已经稳定」。
        assertFalse(DevEncounterTool.playerTurnSettled(null, null));
        assertFalse(DevEncounterTool.playerTurnSettled(new Object(), new Object()));
    }
}
