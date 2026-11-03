package spirelink.state;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #125 · C16-01 / C16-04：过渡路径不许抛异常，进战斗首帧不许当作可行动。
 *
 * <ul>
 *   <li>C16-01：{@code abandon_mid_run} 过渡期 {@code RuntimeProbe.dump()} 里
 *       {@code AbstractDungeon.getCurrRoom()} 对 null 的 {@code currMapNode} 解引用，NPE 被外层 catch
 *       吞掉，于是诊断日志丢掉了后面所有字段，QA 只看到 {@code "error":"java.lang.NullPointerException"}。</li>
 *   <li>C16-04：进战斗首帧 {@code ready:true}，但 {@code hand=[] / energy=0 / intent={"id":"DEBUG"}} ——
 *       怪还挂着引擎占位意图（{@code Intent.DEBUG}），这根本不是决策点。</li>
 * </ul>
 */
public class CombatSettlingTest {

    private static StableGate.Inputs inGame() {
        StableGate.Inputs in = new StableGate.Inputs();
        in.mode = StableGate.Mode.GAMEPLAY;
        in.inGame = true;
        in.playerPresent = true;
        return in;
    }

    // ------------------------------------------------------------ C16-04 战斗初始化

    @Test
    public void anInitializingCombatIsNotADecisionPoint() {
        StableGate.Inputs in = inGame();
        in.combatSettling = true;
        StableGate.Decision decision = StableGate.evaluate(in);
        assertFalse("初始化中的战斗不许 ready", decision.ready);
        assertEquals("combat_settling", decision.reason);

        // 初始化完成（占位意图没了）就该恢复，别把正常战斗卡成 not-ready。
        StableGate.Inputs settled = inGame();
        settled.combatSettling = false;
        assertTrue(StableGate.evaluate(settled).ready);
    }

    @Test
    public void theLoadingAndLeavingWindowsStillWinFirst() {
        // 载入/放弃窗口的判定必须排在战斗初始化之前（否则过渡期会掉进 combat 分支）。
        StableGate.Inputs loading = inGame();
        loading.loading = true;
        loading.combatSettling = true;
        assertEquals("loading", StableGate.evaluate(loading).reason);
        StableGate.Inputs leaving = inGame();
        leaving.leavingRun = true;
        leaving.combatSettling = true;
        assertEquals("leaving_run", StableGate.evaluate(leaving).reason);
    }

    @Test
    public void theProbeUsesTheEnginesPlaceholderIntent() throws IOException {
        String gate = source("src/main/java/spirelink/state/StableGate.java");
        assertTrue("probe 要填 combatSettling", gate.contains("in.combatSettling = combatSettling();"));
        assertTrue("判据是引擎占位意图",
                gate.contains("monster.intent == com.megacrit.cardcrawl.monsters.AbstractMonster.Intent.DEBUG"));
        assertTrue("半成品房间不许抛异常", gate.contains("private static boolean hasPlaceholderIntent()"));
        int helper = gate.indexOf("private static boolean hasPlaceholderIntent()");
        String body = gate.substring(helper, Math.min(gate.length(), helper + 1400));
        assertTrue("要判房间是 COMBAT", body.contains("RoomPhase.COMBAT"));
        assertTrue("占位意图判断要在 try 里（诊断路径不许炸）",
                gate.substring(gate.indexOf("private static boolean combatSettling()"),
                        Math.min(gate.length(), gate.indexOf("private static boolean combatSettling()") + 700))
                        .contains("catch (Throwable t)"));
        assertTrue("要有上界：永不滚意图的怪不许把 agent 卡死",
                gate.contains("SETTLING_MAX_MS") && gate.contains("settlingSinceMs = 0L"));
        assertTrue("awaiting 词表要认得它", source("src/main/java/spirelink/state/StateBuilder.java")
                .contains("case \"combat_settling\":"));
    }

    // ------------------------------------------------------------ C16-01 过渡期 NPE

    @Test
    public void theDiagnosticProbeNeverDereferencesANullRoom() throws IOException {
        String probe = source("src/main/java/spirelink/util/RuntimeProbe.java");
        int room = probe.indexOf("AbstractRoom room =");
        assertTrue(room > 0);
        String line = probe.substring(room, Math.min(probe.length(), room + 220));
        assertTrue("要先看 currMapNode 是不是 null: " + line,
                line.contains("AbstractDungeon.currMapNode == null"));
        int monsters = probe.indexOf("MonsterGroup monsters =");
        String monstersLine = probe.substring(monsters, Math.min(probe.length(), monsters + 160));
        assertTrue("getMonsters() 也要先看 room: " + monstersLine, monstersLine.contains("room == null"));
    }

    // ------------------------------------------------------------ helpers

    private static String source(String relative) throws IOException {
        String raw = read(relative);
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        int n = raw.length();
        while (i < n) {
            if (raw.startsWith("/*", i)) {
                int end = raw.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (raw.startsWith("//", i)) {
                int end = raw.indexOf('\n', i);
                i = end < 0 ? n : end;
                continue;
            }
            out.append(raw.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static String read(String relative) throws IOException {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8);
            }
            dir = dir.getParentFile();
        }
        fail("找不到 " + relative + "（user.dir=" + System.getProperty("user.dir") + "）");
        return null;
    }
}
