package spirelink.mcp;

import org.junit.Test;
import spirelink.actions.ActionResult;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Issue #108 · C7-05: {@code planner/executor 不一致} used to fire for every rejected payload.
 *
 * <p>QA saw the WARN pile up for {@code take_reward{index:1.5}}, {@code play_card{uuid:"x"}} and a
 * missing {@code target} — the planner had advertised those action <em>types</em> correctly, the
 * caller simply sent a payload that was never on offer. The real signal (an advertised action the
 * executor refuses to run at all) was buried under them.</p>
 *
 * <p>{@code ActionResult.paramLevel} is the marker that separates the two (C7-05, actions/ side), so
 * the question is pure and tested here without a game: advertised + not parameter-level is the only
 * combination that may warn.</p>
 */
public class PlannerExecutorMismatchTest {

    @Test
    public void aParameterLevelRejectionIsNeverADisagreement() {
        // The exact QA rows: payload-level, advertised type, no story to tell.
        assertFalse(ToolCatalog.plannerExecutorMismatch(true,
                ActionResult.rejectParam("BAD_REQUEST", "奖励序号越界: 9（合法 0..3）")));
        assertFalse(ToolCatalog.plannerExecutorMismatch(true,
                ActionResult.rejectParam("CARD_NOT_IN_HAND", "手牌里没有这张牌: x")));
        assertFalse(ToolCatalog.plannerExecutorMismatch(true,
                ActionResult.rejectParam("TARGET_REQUIRED", "这张牌需要目标")));
    }

    @Test
    public void aStateLevelRejectionOfAnAdvertisedActionStillWarns() {
        // Advertised, refused for a reason that is not about the payload: this IS the bug signal.
        assertTrue(ToolCatalog.plannerExecutorMismatch(true,
                ActionResult.reject("ILLEGAL_ACTION", "现在不能这样做")));
    }

    /**
     * The deliberately non-parameter rejections stay loud (fix7-actions' list): the planner has only
     * an approximate predicate for these (energy / playability / potion usability / slot full), so an
     * executor refusal of an advertised action really is planner-vs-executor drift and must be seen.
     */
    @Test
    public void approximatePredicateRejectionsStayLoudOnPurpose() {
        for (String code : new String[]{"NOT_ENOUGH_ENERGY", "CARD_NOT_PLAYABLE",
                "POTION_UNUSABLE", "POTION_UNDISCARDABLE", "POTION_SLOT_FULL"}) {
            assertTrue(code + " 是 planner 近似判据，漂移了正该报",
                    ToolCatalog.plannerExecutorMismatch(true, ActionResult.reject(code, "…")));
        }
    }

    @Test
    public void anActionThatIsNoLongerAdvertisedIsNotADisagreement() {
        // The other half of the old condition: it is fine for the planner to stop offering it.
        assertFalse(ToolCatalog.plannerExecutorMismatch(false,
                ActionResult.reject("ILLEGAL_ACTION", "现在不能这样做")));
        assertFalse(ToolCatalog.plannerExecutorMismatch(false,
                ActionResult.rejectParam("BAD_REQUEST", "缺 index")));
    }

    @Test
    public void aMissingResultCannotBeJudged() {
        assertFalse(ToolCatalog.plannerExecutorMismatch(true, null));
        assertFalse(ToolCatalog.plannerExecutorMismatch(false, null));
    }

    @Test
    public void successfulResultsAreNotRejections() {
        assertFalse(ToolCatalog.plannerExecutorMismatch(true, ActionResult.ok()));
        assertFalse(ToolCatalog.plannerExecutorMismatch(true, ActionResult.verified()));
    }
}
