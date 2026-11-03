package spirelink.state;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #123 · C14-05 / C14-06：资源边界缺可对账的字段与说明。
 *
 * <ul>
 *   <li>C14-05：能打出的牌为 0 时 {@code play_card} 全消失、{@code awaiting} 仍 playing、没有任何说明；
 *       {@code get_state{include:["combat"]}} 里也没有 energy 可以对账（energy 只在 player 块里）。</li>
 *   <li>C14-06：药水满时 {@code combat_reward} 只广告 discard_potion + proceed，而 proceed 的标签是
 *       「继续（放弃剩余奖励）」—— 没有一句话说明「丢一瓶就能拿」。</li>
 * </ul>
 *
 * <p>两句话都是纯函数，措辞直接钉死；字段接线用源码断言（都要在游戏里才跑得到）。</p>
 */
public class ResourceHintsTest {

    @Test
    public void theZeroPlayableSentenceSaysWhatToDo() {
        String sentence = StateBuilder.playBlockedSentence(0, 3, 3);
        assertTrue(sentence, sentence.contains("能量不足"));
        assertTrue("要给出可对账的数字: " + sentence, sentence.contains("energy=0/3"));
        assertTrue("要说手牌几张: " + sentence, sentence.contains("手牌 3 张"));
        assertTrue("要给下一步: " + sentence, sentence.contains("end_turn"));
        assertTrue("要解释动作表不是出错: " + sentence, sentence.contains("不是接口出错"));
    }

    @Test
    public void theFullBeltSentenceSaysHowToGetTheReward() {
        String sentence = StateBuilder.potionBlockedSentence(3, 3);
        assertTrue(sentence, sentence.contains("药水槽已满（3/3）"));
        assertTrue("要给出做法: " + sentence, sentence.contains("discard_potion"));
        assertTrue("要说明之后会出现 take_reward: " + sentence, sentence.contains("take_reward"));
        assertTrue("要提示 proceed 的代价: " + sentence, sentence.contains("放弃"));
    }

    @Test
    public void theCombatBlockCarriesEnergyAndPlayableCount() throws IOException {
        String builder = source("src/main/java/spirelink/state/StateBuilder.java");
        int combat = builder.indexOf("private JsonObject buildCombat()");
        assertTrue(combat > 0);
        String body = builder.substring(combat, Math.min(builder.length(), combat + 4000));
        assertTrue("energy 要在 combat 块里（include:[\"combat\"] 才看得到）",
                body.contains("combat.addProperty(\"energy\""));
        assertTrue(body.contains("combat.addProperty(\"energy_max\""));
        assertTrue("还要给能打的张数", body.contains("combat.addProperty(\"playable_cards\""));
        assertTrue("0 张时要带解释", body.contains("combat.add(\"play_blocked\""));
        assertTrue("张数要用原版判定", body.contains("hasEnoughEnergy()"));
    }

    @Test
    public void theRewardBlockCarriesTheBeltRule() throws IOException {
        String builder = source("src/main/java/spirelink/state/StateBuilder.java");
        int rewards = builder.indexOf("private static JsonObject buildRewards()");
        assertTrue(rewards > 0);
        String body = builder.substring(rewards, Math.min(builder.length(), rewards + 5000));
        assertTrue("screen 级要有一句话", body.contains("rewards.addProperty(\"potion_blocked\""));
        assertTrue("逐条的 takeable/blocked_reason 仍然要留",
                body.contains("item.addProperty(\"takeable\"")
                        && body.contains("item.addProperty(\"blocked_reason\""));
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
