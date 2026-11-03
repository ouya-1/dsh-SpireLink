package spirelink.actions;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Source-level wiring for QA round 13 (issue #122) on the actions side.
 *
 * <p>Both findings are call sites, so the heads-down JUnit suite can only pin them by reading the
 * source (same technique as {@link Fix8ActionsWiringTest} / {@link Fix9ActionsWiringTest}): the
 * planner and the executor read {@code AbstractDungeon}/{@code CharacterSelectScreen}, whose static
 * initialisers need the game.</p>
 *
 * <ul>
 *   <li><b>C13-03</b>: {@code abandon_mid_run} used to be executable on the combat screen but never
 *       advertised there, so {@code legal_actions} was not authoritative. Chosen fix: advertise it
 *       (last, with a label that says the save is deleted) — the escape hatch stays;</li>
 *   <li><b>C13-05</b>: clicking the already-selected character twice answered {@code ok} without
 *       moving anything, which {@code ToolCatalog} reports as {@code queued} and then locks with
 *       {@code ACTION_IN_FLIGHT} for the next 3 s (1.68 s observed). It is {@code verified()} now:
 *       "the effect I promise is already true", answered as {@code applied} at once.</li>
 * </ul>
 */
public class RoundThirteenActionsWiringTest {

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

    /** The {@code chars} characters right after {@code marker} (assertions must stay local). */
    private static String after(String source, String marker, int chars) {
        int at = source.indexOf(marker);
        if (at < 0) {
            fail("找不到: " + marker);
        }
        return source.substring(at, Math.min(source.length(), at + chars));
    }

    private static int countOf(String source, String marker) {
        int count = 0;
        int at = source.indexOf(marker);
        while (at >= 0) {
            count++;
            at = source.indexOf(marker, at + marker.length());
        }
        return count;
    }

    private static String executor() throws IOException {
        return read("src/main/java/spirelink/actions/ActionExecutor.java");
    }

    private static String planner() throws IOException {
        return read("src/main/java/spirelink/actions/ActionPlanner.java");
    }

    // ---------------------------------------------------------------- C13-03 combat 屏的 abandon

    /**
     * C13-03: the combat screen must advertise {@code abandon_mid_run}.
     *
     * <p>Two properties are asserted here on purpose: it is <b>advertised</b> (that is the bug fix),
     * and it is <b>last</b> (a harness that always takes {@code actions[0]} must never walk into a
     * destructive action). The label has to name the cost - the save is deleted, unlike the map's
     * shorter label - because this is the screen where an agent spends most of its time.</p>
     */
    @Test
    public void abandonIsAdvertisedOnTheCombatScreen() throws IOException {
        String body = methodBody(planner(), "private static void combat(List<ActionSpec> out)");
        int abandon = body.indexOf("ActionTypes.ABANDON_MID_RUN");
        assertTrue("C13-03: 战斗屏也要广告 abandon_mid_run", abandon >= 0);
        int endTurn = body.indexOf("ActionTypes.END_TURN");
        assertTrue("要排在 end_turn 之后（最后一条）", endTurn >= 0 && endTurn < abandon);
        assertTrue("ready 帧才广告（不能把放弃塞进过场帧）",
                body.indexOf("playerTurnReady()") < abandon);
        String label = after(body, "ActionTypes.ABANDON_MID_RUN", 120);
        assertTrue("label 要写清会弃档: " + label, label.contains("删存档"));
        assertTrue("还要写清去哪: " + label, label.contains("主菜单"));
    }

    /**
     * The advertised action must also be executable in that ui (I4: an offered action may never be
     * rejected) — {@code ActionLegality} is the one place that decides it, so it is asserted directly
     * rather than through the planner.
     */
    @Test
    public void theCombatAbandonIsExecutableToo() {
        assertTrue("advertised ⊆ executable", ActionLegality.allows("combat", ActionTypes.ABANDON_MID_RUN));
        assertNull(ActionLegality.check("combat", ActionTypes.ABANDON_MID_RUN));
        assertTrue("地图上那条不能丢（死角唯一出路）",
                ActionLegality.allows("map", ActionTypes.ABANDON_MID_RUN));
        assertTrue("其它局内界面仍按既有约定放行（escape hatch）",
                ActionLegality.allows("shop", ActionTypes.ABANDON_MID_RUN));
    }

    // ---------------------------------------------------------------- C13-05 连点已选角色

    /**
     * C13-05: re-selecting the already-selected character must answer {@code verified()}.
     *
     * <p>{@code ok()} there is the trap: nothing moves, so {@code ToolCatalog} waits for a new
     * {@code state_version}, answers {@code queued} and marks the action in flight — the next act is
     * then refused with {@code ACTION_IN_FLIGHT} (1.68 s on the real machine) although the agent only
     * pressed "保持 X".</p>
     */
    @Test
    public void keepingTheSelectedCharacterIsVerifiedNotOk() throws IOException {
        String body = methodBody(executor(),
                "private static ActionResult chooseCharacter(JsonObject payload)");
        int at = body.indexOf("if (option.selected) {");
        assertTrue("要有「已经选中」分支", at >= 0);
        int verified = body.indexOf("return ActionResult.verified();", at);
        assertTrue("C13-05: 已选中要回 verified()（否则 ok 没动就是 queued + in-flight）",
                verified > at);
        assertEquals("这个分支的第一件事就是 verified（不许先回 ok 再补）",
                verified, body.indexOf("return ActionResult.", at));
        String branch = body.substring(at, verified);
        assertFalse("这个分支不许回 ok()", branch.contains("ActionResult.ok()"));
        assertFalse("已经选中时不许再点屏幕（重复点会重放选中动画）", branch.contains("hb.clicked"));
        assertTrue("要留一条日志，真机上能 grep 到", branch.contains("choose_character"));
        assertEquals("choose_character 只有这一处 verified",
                1, countOf(body, "ActionResult.verified()"));
    }

    /** The advertised "保持 X" row is exactly the payload that reaches the verified branch. */
    @Test
    public void theKeepRowIsThePayloadTheVerifiedBranchAnswers() throws IOException {
        String body = methodBody(planner(),
                "static void charSelectActions(List<ActionSpec> out, List<CharOption> options, int cap,");
        assertTrue("保持行要带 selected:true 标记", body.contains("payload.addProperty(\"selected\", true)"));
        assertTrue("保持行的 label", body.contains("\"保持 \" + option.name"));
        // The row exists for the naive-first-action harness: embark must stay first.
        int embark = body.indexOf("ActionTypes.EMBARK");
        int keep = body.indexOf("\"保持 \"");
        assertTrue("embark 仍要是第一条", embark >= 0 && embark < keep);
    }
}
