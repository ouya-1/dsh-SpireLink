package spirelink.state;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #124 · C15-02 / C15-04 / C15-05：三处「口径」问题。
 *
 * <ul>
 *   <li>C15-02：怪物便签 {@code note「遇 史莱姆老大：47血」} 与同回包 {@code current_hp:140} 并存，
 *       历史记忆文本没有过期/历史标记 —— 现在每条实体便签都带 {@code note_history:true} +
 *       {@code note_updated_at}，并在 {@code hints.note_history} 里用一句话说明「以同回包字段为准」。</li>
 *   <li>C15-04：非战斗态 {@code player.energy / energy_max} 为 null 且无说明 —— 现在带 {@code energy_note}。</li>
 *   <li>C15-05：无阻塞时 {@code play_blocked} 是空串 —— 改成「有阻塞 = 一句话，没阻塞 = null」，形状稳定。</li>
 * </ul>
 */
public class PayloadClarityTest {

    // ------------------------------------------------------------ C15-02 便签历史标记

    @Test
    public void everyEntityNoteIsMarkedAsHistory() throws IOException {
        String annotator = source("src/main/java/spirelink/state/NoteAnnotator.java");
        assertTrue("便签要标成历史", annotator.contains("item.addProperty(\"note_history\", true)"));
        assertTrue("要带写入时间", annotator.contains("item.addProperty(\"note_updated_at\""));
        assertTrue("时间来自 Notes.updatedAt", annotator.contains("spirelink.util.Notes.updatedAt(kind, id)"));
        int annotate = annotator.indexOf("static void annotate(JsonObject root)");
        assertTrue("每个 body 都要重置标记（否则上一帧的便签会污染这一帧）",
                annotator.substring(annotate, annotate + 300).contains("historySeen = false;"));
        assertTrue("要有一句话解释（hints.note_history）",
                annotator.contains("hints.addProperty(\"note_history\""));
        String builder = source("src/main/java/spirelink/state/StateBuilder.java");
        assertTrue("body 构建后要调用这句话", builder.contains("NoteAnnotator.explainHistory(root);"));
    }

    // ------------------------------------------------------------ C15-04 非战斗态能量

    @Test
    public void nonCombatEnergyNullIsExplained() throws IOException {
        String builder = source("src/main/java/spirelink/state/StateBuilder.java");
        int player = builder.indexOf("private JsonObject buildPlayer(AbstractPlayer player)");
        assertTrue(player > 0);
        String body = builder.substring(player, Math.min(builder.length(), player + 2200));
        assertTrue("非战斗态仍要报 null（数字会是上一战的残留）",
                body.contains("json.add(\"energy\", JsonNull.INSTANCE)"));
        assertTrue("但必须带说明", body.contains("json.addProperty(\"energy_note\""));
        assertTrue("说明要点名「进战斗后会回来」", body.contains("进战斗后 energy / energy_max 会回来"));
    }

    // ------------------------------------------------------------ C15-05 play_blocked

    @Test
    public void playBlockedIsAlwaysPresentAndNullWhenFine() throws IOException {
        String builder = source("src/main/java/spirelink/state/StateBuilder.java");
        assertTrue("有阻塞时是一句话，没阻塞时是 null（不再空串）",
                builder.contains("combat.add(\"play_blocked\""));
        assertTrue(builder.contains("JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(blocked)"));
        // 纯函数：任何输入都不该给出空白句子
        String[] sentences = {
                StateBuilder.playBlockedSentence(0, 3, 3),
                StateBuilder.playBlockedSentence(0, 0, 1),
                StateBuilder.playBlockedSentence(3, 3, 10)
        };
        for (String sentence : sentences) {
            assertFalse("不许空串", sentence == null || sentence.trim().isEmpty());
            assertTrue(sentence, sentence.contains("能量不足"));
        }
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
