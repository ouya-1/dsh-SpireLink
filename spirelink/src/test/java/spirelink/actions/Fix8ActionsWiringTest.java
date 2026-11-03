package spirelink.actions;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Source-level wiring for #110 / #111 / C8-01 / C8-02 / #113.
 *
 * <p>Each rule has a pure core tested properly elsewhere ({@code IntArgumentTest},
 * {@code MapNodeRequestTest}, {@code IndexListTextTest}, {@code MapExitPolicyTest},
 * {@code LabelTextTest}). What a headless
 * test cannot reach is the call site inside {@code ActionExecutor}, because it reads
 * {@code AbstractDungeon} (whose static initialiser needs the game). The bugs these issues report
 * are exactly call sites drifting away from their pure predicate — a payload read with the coercing
 * {@code num()}, a room-phase sentence in front of the type check — so they are pinned here, the same
 * way {@code GateWiringTest} and {@code RoundSevenActionsWiringTest} do it.</p>
 */
public class Fix8ActionsWiringTest {

    private static String read(String relative) throws IOException {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
            dir = dir.getParentFile();
        }
        throw new IOException("找不到 " + relative);
    }

    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        if (at < 0) {
            fail("找不到方法: " + signature);
        }
        int cut = source.length();
        for (String next : new String[] {"\n    private static ", "\n    static ", "\n    public static "}) {
            int candidate = source.indexOf(next, at + signature.length());
            if (candidate > at && candidate < cut) {
                cut = candidate;
            }
        }
        return source.substring(at, cut);
    }

    /** The text of one block, from {@code from} to the first {@code }} at method-body indentation. */
    private static String blockAt(String source, int from) {
        int end = source.indexOf("\n        }", from);
        return end < 0 ? source.substring(from) : source.substring(from, end);
    }

    private static String executor() throws IOException {
        return read("src/main/java/spirelink/actions/ActionExecutor.java");
    }

    // ---------------------------------------------------------------- #110 choose_map_node

    /**
     * #110: the payload is validated first. It used to be read with {@code num()}, whose fallback
     * {@code -1} is the Boss sentinel, so a string x reached the room-phase branch and answered
     * {@code NOT_READY "…先 proceed 离开"} — an action that map did not advertise.
     */
    @Test
    public void mapPayloadIsValidatedBeforeAnyRoomPhaseSentence() throws IOException {
        String map = methodBody(executor(), "private static ActionResult chooseMapNode(JsonObject payload)");
        int parse = map.indexOf("MapNodeRequest.parse(payload)");
        int phase = map.indexOf("room.phase");
        assertTrue("payload 要先过 MapNodeRequest（#110）", parse >= 0);
        assertTrue("payload 类型校验必须排在房间阶段判定之前", phase < 0 || parse < phase);
        assertFalse("不能再用会回退成 -1 的 num() 读 x/y", map.contains("num(payload, \"x\""));
        assertFalse("坏 payload 不能答成「缺少 x/y」", map.contains("缺少 x/y"));
        assertTrue("payload 错误要是参数级（否则会打假的「planner/executor 不一致」）",
                map.contains("rejectParam("));
        assertTrue("房间阶段提示要由 MapExitPolicy 给（只提能执行的动作）",
                map.contains("MapExitPolicy.executableHint("));
        assertFalse("提示里不许再写死「先 proceed 离开」", executor().contains("先 proceed"));
    }

    /** A node that is not among the advertised ones lists the nodes that are (same shape as C8-01). */
    @Test
    public void anUnreachableNodeListsTheNodesThatCanBeNamed() throws IOException {
        String map = methodBody(executor(), "private static ActionResult chooseMapNode(JsonObject payload)");
        int reject = map.indexOf("现在不可达");
        assertTrue("不可达要说明并列出候选", reject >= 0);
        assertTrue("候选来自 reachableSummary()", map.contains("reachableSummary()"));
        int code = map.lastIndexOf("CODE_ILLEGAL", reject);
        assertTrue("错误码不能变：docs 里 x/y 走不通就是 ILLEGAL_ACTION", code > 0);
    }

    // ---------------------------------------------------------------- #111 take_card

    /**
     * #111: {@code take_card{"index":1.5}} was accepted and took card 1 (silent truncation) while the
     * same value on {@code take_reward} was a strict BAD_REQUEST.
     */
    @Test
    public void takeCardReadsItsIndexStrictly() throws IOException {
        String take = methodBody(executor(), "private static ActionResult takeCard(JsonObject payload)");
        assertTrue("take_card 的 index 要走 IntArgument（拒绝 1.5，而不是截断成 1）",
                take.contains("IntArgument.optionalNonNegative(payload, \"index\")"));
        assertFalse("不能再用 num() 读 index", take.contains("num(payload, \"index\""));
        int strict = take.indexOf("IntArgument.optionalNonNegative(payload, \"index\")");
        int acquire = take.indexOf("getDeclaredMethod(\"acquireCard\"");
        assertTrue("校验必须排在真正拿牌之前（被拒 = 零副作用）", acquire < 0 || strict < acquire);
        assertTrue("非整数/负数要 BAD_REQUEST", take.contains("ErrorCodes.BAD_REQUEST"));
        assertTrue("越界要带合法范围（C7-04 口径）", take.contains("CardRewardPolicy.rangeText("));
        assertTrue("越界要列可拿的牌", take.contains("cardRewardSummary("));
    }

    // ---------------------------------------------------------------- C8-01 claim_relic / buy

    @Test
    public void claimRelicAnswersWithRangeAndCandidates() throws IOException {
        String claim = methodBody(executor(), "private static ActionResult claimRelic(JsonObject payload)");
        assertTrue("claim_relic 的 index 也要严格（同 #111）",
                claim.contains("IntArgument.optionalNonNegative("));
        assertTrue("越界要带「合法范围 0..N-1」（C8-01）", claim.contains("bossRelicRange("));
        assertTrue("越界要列候选清单（C8-01）", claim.contains("bossRelicSummary("));
        assertFalse("不能再只回一句「遗物序号越界」", claim.contains("\"遗物序号越界: \""));
    }

    @Test
    public void buyAnswersWithRangeAndCandidates() throws IOException {
        String buy = methodBody(executor(), "private static ActionResult buy(JsonObject payload)");
        assertTrue("buy 的 index 要严格（同 #111）", buy.contains("IntArgument.requiredNonNegative("));
        assertTrue("越界要带合法范围（C8-01）", buy.contains("shopRangeText("));
        assertTrue("三种货架都要给候选清单（C8-01）",
                buy.contains("shopCardsSummary(") && buy.contains("shopRelicsSummary(")
                        && buy.contains("shopPotionsSummary("));
        assertFalse("不能再只回一句「卡牌序号越界」", buy.contains("\"卡牌序号越界: \""));
        assertFalse("遗物也一样", buy.contains("\"遗物序号越界: \""));
        assertFalse("药水也一样", buy.contains("\"药水序号越界: \""));
    }

    // ---------------------------------------------------------------- C8-02 take_reward

    /**
     * C8-02: a non-integer / negative / missing index is the *same* payload problem as an
     * out-of-range one, so it must carry the legal range too — the parse failure used to answer with
     * the item list only.
     */
    @Test
    public void takeRewardCarriesTheRangeForEveryPayloadRejection() throws IOException {
        String take = methodBody(executor(), "private static ActionResult takeReward(JsonObject payload)");
        int parse = take.indexOf("TakeRewardRequest.parse(");
        int catchAt = take.indexOf("catch (BadRequestException e)", parse);
        assertTrue("take_reward 仍然用 TakeRewardRequest 解析", parse >= 0);
        assertTrue("要有 BAD_REQUEST 的 catch 分支", catchAt > parse);
        String rejection = blockAt(take, catchAt);
        assertTrue("C8-02：解析失败也要带「合法范围 0..N-1」",
                rejection.contains("TakeRewardRequest.rangeText("));
        assertTrue("还要列能领的奖励", rejection.contains("takeableRewardSummary(screen)"));
        assertTrue("是参数级（不进「不一致」WARN）", rejection.contains("rejectParam("));
    }

    // ---------------------------------------------------------------- param level (C7-05)

    /**
     * {@code ToolCatalog} only prints {@code planner/executor 不一致} for a non-param rejection
     * (C7-05), so a payload problem that is answered with {@code reject} re-creates the false alarm
     * #110 reported. All five payload-reading actions must answer {@code rejectParam}.
     */
    @Test
    public void payloadRejectionsStayParamLevelSoNoFalseMismatchWarn() throws IOException {
        String source = executor();
        for (String signature : new String[] {
                "private static ActionResult takeCard(JsonObject payload)",
                "private static ActionResult takeReward(JsonObject payload)",
                "private static ActionResult claimRelic(JsonObject payload)",
                "private static ActionResult buy(JsonObject payload)",
                "private static ActionResult chooseMapNode(JsonObject payload)"}) {
            String body = methodBody(source, signature);
            assertTrue(signature + " 的 payload 拒绝要标参数级", body.contains("rejectParam("));
            assertTrue(signature + " 要用 BAD_REQUEST", body.contains("ErrorCodes.BAD_REQUEST"));
        }
    }

    /** The coercing reader stays, but only for call sites that have not been audited yet. */
    @Test
    public void theCoercingReaderIsDocumentedAsLegacy() throws IOException {
        String source = executor();
        int doc = source.indexOf("Legacy coercing reader");
        int method = source.indexOf("private static int num(JsonObject payload, String key, int fallback)");
        assertTrue("num() 要标注为会截断的遗留读取器", doc >= 0);
        assertTrue("注释要说明它不是给新 payload 用的", source.indexOf("IntArgument", doc) > doc);
        assertTrue(method > doc);
    }

    // ---------------------------------------------------------------- #113 choose_option / choose_match_card

    /**
     * #113: {@code choose_option} read its index with {@code num()}, so {@code index:1.5} clicked
     * option 1 and an out-of-range index answered a bare {@code "选项序号越界: 9"}. Both are payload
     * problems: strict integer, BAD_REQUEST, and the range plus the real option labels in the message.
     */
    @Test
    public void chooseOptionReadsItsIndexStrictlyAndNamesTheOptions() throws IOException {
        String option = methodBody(executor(), "private static ActionResult chooseOption(JsonObject payload)");
        int strict = option.indexOf("IntArgument.optionalNonNegative(payload, \"index\")");
        assertTrue("choose_option 的 index 要走 IntArgument（#113）", strict >= 0);
        assertFalse("不能再用 num() 读 index", option.contains("num(payload, \"index\""));
        int effect = option.indexOf("invokeButtonEffect(");
        assertTrue("校验必须排在点按钮之前（被拒 = 零副作用）", effect < 0 || strict < effect);
        assertTrue("越界要带「合法范围 0..N-1」", option.contains("eventOptionRange("));
        assertTrue("越界要列事件选项文案", option.contains("eventOptionSummary("));
        assertTrue("是参数级 BAD_REQUEST", option.contains("ErrorCodes.BAD_REQUEST"));
        assertFalse("不能再回裸「选项序号越界」", option.contains("选项序号越界"));
    }

    /** The candidate list is built from what the screen really shows, with locked options marked. */
    @Test
    public void theEventOptionCandidatesAreTheRealLabels() throws IOException {
        String summary = methodBody(executor(), "private static String eventOptionSummary(");
        assertTrue("要用选项自己的文案", summary.contains("option.msg"));
        assertTrue("要先压成一行再进报文（LabelText）", summary.contains("LabelText.oneLine("));
        assertTrue("序号要跟 payload 一致", summary.contains("IndexListText.entry("));
        assertTrue("要用同一个「可点」判据（#95），锁定的要标出来",
                summary.contains("EventOptionPolicy.selectable("));
        assertTrue(summary.contains("锁定"));
    }

    /**
     * #113: {@code choose_match_card} had the same truncation — {@code index:1.5} flipped card 1, and
     * a bad index only said {@code "index 越界或这张牌现在不能翻"}.
     */
    @Test
    public void chooseMatchCardReadsItsIndexStrictlyAndNamesTheBoard() throws IOException {
        String match = methodBody(executor(), "private static ActionResult chooseMatchCard(JsonObject args)");
        int strict = match.indexOf("IntArgument.optionalNonNegative(args, \"index\")");
        assertTrue("choose_match_card 的 index 要走 IntArgument（#113）", strict >= 0);
        assertFalse("不能再用 num() 读 index", match.contains("num(args, \"index\""));
        int flip = match.indexOf("MatchGame.click(");
        assertTrue("校验必须排在真正翻牌之前", flip < 0 || strict < flip);
        assertTrue("越界要带「合法范围 0..N-1」", match.contains("matchCardRange("));
        assertTrue("越界要列牌面", match.contains("matchCardSummary("));
        assertTrue("是参数级 BAD_REQUEST", match.contains("ErrorCodes.BAD_REQUEST"));
        assertFalse("不能再回裸「index 越界或这张牌现在不能翻: N」",
                executor().contains("index 越界或这张牌现在不能翻: "));
    }

    @Test
    public void theMatchCardCandidatesAreTheRealBoard() throws IOException {
        String summary = methodBody(executor(), "private static String matchCardSummary(");
        assertTrue("要用牌面本身", summary.contains("MatchGame.cards()"));
        assertTrue("已配对的牌要标出来", summary.contains("MatchGame.matched("));
        assertTrue("已翻开等配对的那张要标出来", summary.contains("chosenCard()"));
        assertTrue(summary.contains("LabelText.oneLine("));
        assertTrue(summary.contains("IndexListText.entry("));
    }
}