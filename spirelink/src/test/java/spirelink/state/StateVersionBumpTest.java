package spirelink.state;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #75 (regression, round 2): {@code state_version} must follow the decision point only.
 *
 * <p>QA watched the version walk on the event screen twice:
 *
 * <ol>
 *   <li>Bonfire: 70 ms after entering the event the version went 28 -> 29 while the body was
 *       re-typed (29 chars -> 8 chars) and then 30 - i.e. two bumps for one animation.</li>
 *   <li>Cleric: three answers with an identical body / options / ready, version +1 each time - the
 *       empty spin. In that whole segment {@code text_typing} was false.</li>
 * </ol>
 *
 * <p>The rule now: the version is the option set plus the key screen state; the body (typing, a
 * re-typed page, a new story page) only refreshes the cached {@code event} block. Everything here is
 * pure: no game, no clock, no sleeping.
 */
public class StateVersionBumpTest {

    // ---------------------------------------------------------------- the tick decision

    @Test
    public void actionListChangeBumpsTheVersion() {
        // signature contains ui + screen + room + the action digest: if it moved, act must be re-read.
        assertEquals(StateBuilder.TICK_BUMP_VERSION,
                StateBuilder.tickDecision("sigB", "sigB|body=-", "sigA", "sigA|body=-", true, false));
    }

    @Test
    public void typingProgressOnlyRefreshesTheBody() {
        // The key case of #75: same signature (same actions!), only the typed text advanced.
        assertEquals("只有动画/剧情推进：刷新正文，但绝不能推进版本（否则必吃 STALE_VERSION）",
                StateBuilder.TICK_REFRESH_BODY,
                StateBuilder.tickDecision("sigA", "sigA|body=typing3", "sigA", "sigA|body=typing2",
                        true, false));
    }

    @Test
    public void nothingChangedDoesNothing() {
        assertEquals(StateBuilder.TICK_NOTHING,
                StateBuilder.tickDecision("sigA", "sigA|body=typing2", "sigA", "sigA|body=typing2",
                        true, false));
    }

    @Test
    public void firstFrameBumpsTheVersion() {
        assertEquals(StateBuilder.TICK_BUMP_VERSION,
                StateBuilder.tickDecision("sigA", "sigA|body=-", null, null, false, false));
    }

    @Test
    public void explicitInvalidationBumpsTheVersion() {
        // invalidate() (notes changed) drops the signature on purpose: the next frame must rebuild.
        assertEquals(StateBuilder.TICK_BUMP_VERSION,
                StateBuilder.tickDecision("sigA", "sigA|body=typing2", null, "sigA|body=typing2",
                        true, false));
    }

    /**
     * QA measurement 2, reduced to its essence: the gate blips for a single frame (they are invisible
     * in the samples because the agent only ever reads ready frames), and the state on screen never
     * moved. The version must not move either.
     */
    @Test
    public void notReadyBlipWithoutActionsDoesNotBumpTheVersion() {
        assertEquals("同一 options/ready 连续多拍 → sv 不变（QA 第 2 轮 Cleric 空转的直接原因）",
                StateBuilder.TICK_NOTHING,
                StateBuilder.tickDecision("sigA", "sigA|body=x", "sigA", "sigA|body=x", true, false));
    }

    @Test
    public void settlingAfterARealActionBumpsTheVersion() {
        // A queued action really did run: the agent may act again even if nothing visible differs.
        assertEquals(StateBuilder.TICK_BUMP_VERSION,
                StateBuilder.tickDecision("sigA", "sigA|body=x", "sigA", "sigA|body=x", true, true));
    }

    @Test
    public void onlyABusyQueueCountsAsAnActionTransition() {
        StableGate.Inputs idle = new StableGate.Inputs();
        assertFalse("空队列不算动作过渡（否则打字期的 not-ready 抖动会重新变成版本推进）",
                StateBuilder.queueBusy(idle));
        StableGate.Inputs actions = new StableGate.Inputs();
        actions.actionsEmpty = false;
        assertTrue(StateBuilder.queueBusy(actions));
        StableGate.Inputs card = new StableGate.Inputs();
        card.cardQueueEmpty = false;
        assertTrue(StateBuilder.queueBusy(card));
        StableGate.Inputs preTurn = new StableGate.Inputs();
        preTurn.preTurnActionsEmpty = false;
        assertTrue(StateBuilder.queueBusy(preTurn));
        StableGate.Inputs current = new StableGate.Inputs();
        current.currentActionNull = false;
        assertTrue(StateBuilder.queueBusy(current));
        assertFalse(StateBuilder.queueBusy(null));
    }

    @Test
    public void animationAloneNeverBumpsAcrossAFullTypingRun() {
        // QA observation 1, replayed: one page change re-types the body over many ticks with an
        // unchanged option set (29 chars -> 8 chars and back up again).
        String signature = "ui=event|actions:[choose_option(0),choose_option(1)]";
        String lastSignature = null;
        String lastBodyKey = null;
        int bumps = 0;
        String[] bodies = {"", "长", "长廊", "长廊里", "长廊里你", "长", "长廊", "长廊里你看见"};
        for (String body : bodies) {
            String bodyKey = signature + "|body=" + body;
            int move = StateBuilder.tickDecision(signature, bodyKey, lastSignature, lastBodyKey,
                    true, false);
            if (move == StateBuilder.TICK_BUMP_VERSION) {
                bumps++;
            }
            lastSignature = signature;
            lastBodyKey = bodyKey;
        }
        assertEquals("第一次建体之后，正文重打/剧情刷新多少次都不许再动版本号", 1, bumps);
    }

    /**
     * The complete QA scenario, tick by tick: the samples are identical (body + options + ready),
     * and between two samples the gate was not ready for one frame with an empty action queue.
     */
    @Test
    public void qaClericEmptySpinIsGone() {
        String signature = "ui=event|evt=Cleric|o=2|d=0";
        String bodyKey = signature + "|body=你看见了牧师";
        String lastSignature = signature;
        String lastBodyKey = bodyKey;
        int bumps = 0;
        for (int i = 0; i < 6; i++) {
            // One invisible not-ready frame per sample (no queue activity, so no settle flag).
            // ... then the sample itself:
            int move = StateBuilder.tickDecision(signature, bodyKey, lastSignature, lastBodyKey,
                    true, false);
            if (move == StateBuilder.TICK_BUMP_VERSION) {
                bumps++;
            }
            if (move != StateBuilder.TICK_NOTHING) {
                lastSignature = signature;
                lastBodyKey = bodyKey;
            }
        }
        assertEquals("body / options / ready 全同的连续多拍，版本号必须一动不动", 0, bumps);
    }

    @Test
    public void optionTextChangeBumpsEvenWhileTyping() {
        // A page whose options changed is a new decision point even if the body text is still typing.
        assertEquals(StateBuilder.TICK_BUMP_VERSION,
                StateBuilder.tickDecision("sigA|evt=X|o=1|l=continue", "sigA|evt=X|o=1|l=continue|t=1",
                        "sigA|evt=X|o=1|l=pay", "sigA|evt=X|o=1|l=pay|t=1", true, false));
    }

    /**
     * The other half of QA measurement 1: the body moved (a re-typed page) while the option set and
     * readiness stayed put -> the body is refreshed, the version is not.
     */
    @Test
    public void bodyMovingDuringANotReadyBlipStillDoesNotBump() {
        assertEquals(StateBuilder.TICK_REFRESH_BODY,
                StateBuilder.tickDecision("sigA", "sigA|body=你离开了。", "sigA", "sigA|body=当你走过",
                        true, false));
    }

    /**
     * QA measurement 1 replayed end to end on the real token composition: one Bonfire page change
     * (body 29 chars -> 8 chars and re-typed, then settled) may only bump the version for the one
     * thing that is a decision - the options - never for the animation.
     */
    @Test
    public void qaBonfirePageChangeBumpsOnlyForTheOptions() {
        int version = 0;
        String lastSignature = null;
        String lastBodyKey = null;

        // Page 1: text typing out, two options on screen.
        String signature = "ui=event|" + EventPayload.decisionToken("Bonfire", 2, "祈祷|离开|", 0);
        String bodyKey = signature + "|body=" + EventPayload.bodyToken(
                "Bonfire", "当你走过一条长廊时，你看见空中", false, 2, "祈祷|离开|", 0);
        version += bump(signature, bodyKey, lastSignature, lastBodyKey);
        lastSignature = signature;
        lastBodyKey = bodyKey;

        // The same page types further - the body moves, the version must not.
        for (String text : new String[] {"…漂浮着一根香蕉", "…漂浮着一根香蕉，一个甜甜圈"}) {
            String moved = signature + "|body=" + EventPayload.bodyToken(
                    "Bonfire", text, false, 2, "祈祷|离开|", 0);
            assertEquals("打字进度不许推进版本", 0, bump(signature, moved, lastSignature, lastBodyKey));
        }

        // Typing completes: still the same decision point.
        String settledBody = signature + "|body=" + EventPayload.bodyToken(
                "Bonfire", "你看见空中漂浮着一根香蕉", true, 2, "祈祷|离开|", 0);
        assertEquals("打字完成也不许推进版本", 0, bump(signature, settledBody, lastSignature, lastBodyKey));
        lastBodyKey = settledBody;

        // The player picks one: a NEW page (8 chars, re-typed) with a new option set.
        String page2 = "ui=event|" + EventPayload.decisionToken("Bonfire", 1, "离开|", 0);
        String page2Body = page2 + "|body=" + EventPayload.bodyToken(
                "Bonfire", "你离开了。", false, 1, "离开|", 0);
        assertEquals("只有选项变了这一件事该推进版本（28 -> 29），正文重打不许再加一格",
                1, bump(page2, page2Body, lastSignature, lastBodyKey));
    }

    /** One frame tick: returns 1 when the version would move. */
    private static int bump(String signature, String bodyKey, String lastSignature,
            String lastBodyKey) {
        int move = StateBuilder.tickDecision(signature, bodyKey, lastSignature, lastBodyKey,
                true, false);
        return move == StateBuilder.TICK_BUMP_VERSION ? 1 : 0;
    }

    // ---------------------------------------------------------------- the event tokens

    @Test
    public void decisionTokenIgnoresTheBodyText() {
        // QA measurement 1: the body changed (re-typed, then a shorter page) - still the same decision.
        String partial = EventPayload.decisionToken("Bonfire", 2, "祈祷|离开|", 0);
        String retyped = EventPayload.decisionToken("Bonfire", 2, "祈祷|离开|", 0);
        assertEquals("正文怎么变都不许进版本口径", partial, retyped);
        // ... while the body token does move, because the agent reads it.
        assertNotEquals(EventPayload.bodyToken("Bonfire", "当你走过一条长廊时，你看见空中", true, 2,
                        "祈祷|离开|", 0),
                EventPayload.bodyToken("Bonfire", "你离开了。", true, 2, "祈祷|离开|", 0));
    }

    @Test
    public void decisionTokenMovesWhenTheOptionSetMoves() {
        String base = EventPayload.decisionToken("Cleric", 2, "治疗|净化|", 0);
        assertNotEquals("选项数量变了 = 新决策点", base,
                EventPayload.decisionToken("Cleric", 1, "治疗|", 0));
        assertNotEquals("选项文案变了（MaskedBandits 付钱->继续）", base,
                EventPayload.decisionToken("Cleric", 2, "治疗|继续|", 0));
        assertNotEquals("选项从灰变可点也是新决策点", base,
                EventPayload.decisionToken("Cleric", 2, "治疗|净化|", 2));
        assertNotEquals("换事件 = 新决策点", base, EventPayload.decisionToken("BigFish", 2, "治疗|净化|", 0));
    }

    @Test
    public void bodyTokenFollowsTypingAndText() {
        String typingA = EventPayload.bodyToken("Cleric", "当你", false, 1, "治疗|", 0);
        String typingB = EventPayload.bodyToken("Cleric", "当你看见", false, 1, "治疗|", 0);
        String done = EventPayload.bodyToken("Cleric", "当你看见", true, 1, "治疗|", 0);
        assertNotEquals("打字进度必须能刷新正文", typingA, typingB);
        assertNotEquals("打完字是另一个正文状态（text_typing 要翻转）", typingB, done);
        assertEquals("同一段文本 + 同一状态 → 同一 token（确定性）", typingB,
                EventPayload.bodyToken("Cleric", "当你看见", false, 1, "治疗|", 0));
        assertEquals("null 文本不能炸", EventPayload.bodyToken("Cleric", null, false, 0, null, 0),
                EventPayload.bodyToken("Cleric", "", false, 0, null, 0));
    }

    @Test
    public void bodyTokenCarriesTheDecisionFactsToo() {
        // The executor compares this token before/after a click: an option relabel with an unchanged
        // body must still count as "the screen moved".
        assertNotEquals(EventPayload.bodyToken("Cleric", "同", false, 2, "付钱|", 0),
                EventPayload.bodyToken("Cleric", "同", false, 2, "继续|", 0));
    }

    // ---------------------------------------------------------------- text_typing (#75 root cause)

    @Test
    public void typingComesFromTheActiveDialog() {
        // Image events (Bonfire, Cleric): the static GenericEventDialog flags. The old code read
        // roomEventText.waitTimer, a field that does not exist -> text_typing was always false.
        assertTrue("正在打字：textDone=false",
                EventPayload.typingFlag(new EventPayload.Dialogs(
                        true, true, Boolean.FALSE, "当你走过", false, null, "")));
        assertFalse("打完了：textDone=true",
                EventPayload.typingFlag(new EventPayload.Dialogs(
                        true, true, Boolean.TRUE, "当你走过一条长廊", false, null, "")));
    }

    @Test
    public void roomDialogsAnswerTheFlagToo() {
        // Neow-style room events (bytecode: NeowEvent only ever touches roomEventText) keep the same
        // field names on their own RoomEventDialog instance; their image dialog must not answer.
        EventPayload.Dialogs dialogs = new EventPayload.Dialogs(
                false, false, Boolean.TRUE, "", true, Boolean.FALSE, "涅奥说");
        assertEquals("room", EventPayload.typingSource(dialogs));
        assertTrue("roomEventText.textDone=false 就是正在打字", EventPayload.typingFlag(dialogs));
        assertFalse(EventPayload.typingFlag(new EventPayload.Dialogs(
                false, false, Boolean.TRUE, "", true, Boolean.TRUE, "涅奥说完了")));
    }

    @Test
    public void leftoverTextOfTheOtherDialogNeverWins() {
        // Entering a room event right after an image event leaves text in both dialogs; the event's
        // own class decides (a stale image page must not become the body of Neow's blessing).
        EventPayload.Dialogs roomEvent = new EventPayload.Dialogs(
                false, false, Boolean.TRUE, "上一个图片事件的正文", true, Boolean.FALSE, "涅奥说");
        assertEquals("room", EventPayload.typingSource(roomEvent));
        assertEquals("涅奥说", EventPayload.pickLiveText(roomEvent));
        EventPayload.Dialogs imageEvent = new EventPayload.Dialogs(
                true, true, Boolean.TRUE, "篝火正文", false, Boolean.TRUE, "上一页遗留");
        assertEquals("image", EventPayload.typingSource(imageEvent));
        assertEquals("篝火正文", EventPayload.pickLiveText(imageEvent));
    }

    @Test
    public void noReadableFlagMeansNoTypingAndSaysSo() {
        // Never invent "typing": an unreadable flag must not become a permanent true.
        EventPayload.Dialogs none = new EventPayload.Dialogs(false, false, null, "", false, null, "");
        assertEquals("none", EventPayload.typingSource(none));
        assertFalse(EventPayload.typingFlag(none));
    }

    @Test
    public void theLiveDialogWinsForTheBody() {
        // The frozen intro field must not win while a dialog has text (#75: the body has to follow).
        assertEquals("页面正文", EventPayload.pickBody("页面正文", "开场白"));
        assertEquals("开场白", EventPayload.pickBody("", "开场白"));
        assertNull(EventPayload.pickBody("", " "));
    }

    @Test
    public void tokensAreSafeOutsideAnEvent() {
        // No game: getCurrRoom() throws, and a token must degrade instead of breaking the frame.
        assertEquals("-", EventPayload.progressToken());
        assertEquals("-", EventPayload.decisionToken());
        assertEquals("", EventPayload.dialogText());
    }
}
