package spirelink.state;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #125 · S2-16-01（新 S2）：便签字段时有时无 + 与便签库一致 + 药水便签可达。
 *
 * <p>现场：同一场战斗同一只邪教徒，首帧 {@code note/note_history/note_updated_at} 都在，稳定帧三键
 * 整体消失 —— 因为旧实现「本局已推送过就 return」，连 {@code note:null} 都不写。QA 读成「这只怪没便签」。</p>
 *
 * <p>这里用真的便签库（临时目录）跑两个「帧」，钉住：同样的实体每一帧拿到同样的键集合、文案能回溯到
 * 存储（{@code note_key}）、推送过只体现在 {@code note_pushed} 上。</p>
 */
public class NoteShapeTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File dataDir;

    @Before
    public void setUp() throws Exception {
        dataDir = folder.newFolder("notes");
        spirelink.util.Notes.useDirectoryForTests(dataDir);
        spirelink.util.Notes.resetForTests();
        // 「已推送过」按 (kind:id@updated_at) 记，两个测试同一秒写同一条键会互相影响。
        spirelink.util.NotesDedupe.resetForTests();
    }

    @After
    public void tearDown() {
        spirelink.util.Notes.resetForTests();
    }

    private static final String BODY = "{\"combat\":{\"monsters\":["
            + "{\"index\":0,\"id\":\"Cultist\",\"name\":\"邪教徒\",\"current_hp\":52}]},"
            + "\"potions\":{\"slot_count\":2,\"slots\":["
            + "{\"slot\":0,\"empty\":true},"
            + "{\"slot\":1,\"id\":\"Fire Potion\",\"name\":\"火焰药水\"}]},"
            + "\"hints\":{}}";

    @Test
    public void theSameEntityKeepsTheSameNoteFieldsOnEveryFrame() {
        spirelink.util.Notes.set("monster", "Cultist", "遇 邪教徒：1血 意图未知");

        JsonObject first = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(first);
        JsonObject monster1 = first.getAsJsonObject("combat").getAsJsonArray("monsters")
                .get(0).getAsJsonObject();
        assertEquals("遇 邪教徒：1血 意图未知", monster1.get("note").getAsString());
        assertEquals("第一次投递", "new", monster1.get("note_state").getAsString());
        assertTrue("历史标记", monster1.get("note_history").getAsBoolean());
        assertTrue("写入时间", monster1.get("note_updated_at").getAsLong() > 0L);
        assertEquals("monster:Cultist", monster1.get("note_key").getAsString());
        assertFalse("这一帧是第一次推送", monster1.get("note_pushed").getAsBoolean());

        // 第二帧（稳定帧）：旧实现这时三键整体消失。
        JsonObject second = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(second);
        JsonObject monster2 = second.getAsJsonObject("combat").getAsJsonArray("monsters")
                .get(0).getAsJsonObject();
        for (String key : new String[]{"note", "note_state", "note_history", "note_updated_at",
                "note_key", "note_pushed"}) {
            assertTrue("第二帧不许丢 " + key, monster2.has(key));
        }
        // Issue #127: 第二帧不再重复正文（这是用户实测报的「一局里反复返回同一段便签」）。
        assertTrue("第二帧不带正文（省 token）", monster2.get("note").isJsonNull());
        assertEquals("已经看过要能看出来", "seen", monster2.get("note_state").getAsString());
        assertTrue("已经推送过要能看出来", monster2.get("note_pushed").getAsBoolean());
        assertEquals("键与版本号不变", monster1.get("note_key").getAsString(),
                monster2.get("note_key").getAsString());
        assertEquals("版本号不变", monster1.get("note_updated_at").getAsLong(),
                monster2.get("note_updated_at").getAsLong());
    }

    @Test
    public void theTextIsExactlyWhatTheStoreHolds() {
        spirelink.util.Notes.set("monster", "Cultist", "遇 邪教徒：1血 意图未知");
        JsonObject body = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(body);
        JsonObject monster = body.getAsJsonObject("combat").getAsJsonArray("monsters")
                .get(0).getAsJsonObject();
        // ① 可复核：note_key + note_get 一定能拿到同一段文字
        String key = monster.get("note_key").getAsString();
        String text = spirelink.util.Notes.get("monster", key.split(":")[1]);
        assertEquals(monster.get("note").getAsString(), text);
    }

    @Test
    public void anEntityWithoutANoteSaysNullEveryFrame() {
        JsonObject body = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(body);
        JsonObject monster = body.getAsJsonObject("combat").getAsJsonArray("monsters")
                .get(0).getAsJsonObject();
        assertTrue("没有便签也要有 note 键（null）", monster.has("note"));
        assertTrue("值是 null", monster.get("note").isJsonNull());
        assertEquals("显式说明这里没有便签", "none", monster.get("note_state").getAsString());
        assertFalse("没有便签就没有历史标记", monster.has("note_history"));
    }

    @Test
    public void potionNotesAreReachableNow() {
        spirelink.util.Notes.set("potion", "Fire Potion", "药水便签：火焰药水 20 伤");
        JsonObject body = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(body);
        JsonObject slot = body.getAsJsonObject("potions").getAsJsonArray("slots")
                .get(1).getAsJsonObject();
        assertEquals("药水便签：火焰药水 20 伤", slot.get("note").getAsString());
        assertEquals("new", slot.get("note_state").getAsString());
        assertEquals("potion:Fire Potion", slot.get("note_key").getAsString());
        JsonObject empty = body.getAsJsonObject("potions").getAsJsonArray("slots")
                .get(0).getAsJsonObject();
        assertFalse("空槽没有便签字段（也就没有键漂移）", empty.has("note"));
    }

    @Test
    public void theBodyExplainsOnceThatNotesAreHistory() {
        spirelink.util.Notes.set("monster", "Cultist", "遇 邪教徒：1血 意图未知");
        JsonObject body = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(body);
        NoteAnnotator.explainHistory(body);
        JsonObject hints = body.getAsJsonObject("hints");
        assertNotNull("hints.note_history 必须出现（上一轮 QA 说它从不出现）", hints.get("note_history"));
        assertTrue(hints.get("note_history").getAsString().contains("历史便签"));
        // 第二帧（#127）：正文不再重复，所以也不再每帧刷那句说明 —— 它只陪「新正文」出现。
        JsonObject again = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(again);
        NoteAnnotator.explainHistory(again);
        assertNull("没有新正文的帧不再重复那句说明",
                again.getAsJsonObject("hints").get("note_history"));
    }

    @Test
    public void anEntityWithOnlyANameStableKey() {
        spirelink.util.Notes.set("monster", "邪教徒", "按名字写的便签");
        JsonObject body = new JsonParser().parse("{\"combat\":{\"monsters\":["
                + "{\"index\":0,\"name\":\"邪教徒\",\"current_hp\":52},"
                + "{\"index\":1,\"id\":\"Cultist\",\"name\":\"邪教徒\",\"current_hp\":52}]}}")
                .getAsJsonObject();
        NoteAnnotator.annotate(body);
        // 只有名字的怪：按名字命中便签
        JsonObject byName = body.getAsJsonObject("combat").getAsJsonArray("monsters")
                .get(0).getAsJsonObject();
        assertEquals("monster:邪教徒", byName.get("note_key").getAsString());
        // 有 id 的同一只怪：id 没有便签，名字有 —— 也要落在同一个键上（旧实现会按 id 查而查不到）
        JsonObject withId = body.getAsJsonObject("combat").getAsJsonArray("monsters")
                .get(1).getAsJsonObject();
        assertEquals("monster:邪教徒", withId.get("note_key").getAsString());
        // #127：同一份便签正文在同一帧里只投递一次，第二只怪拿到的是 seen（键仍在，正文省略）。
        assertEquals("seen", withId.get("note_state").getAsString());
        assertTrue("正文不重复", withId.get("note").isJsonNull());
    }

    /** Issue #127: 便签被改写（版本号变了）要重新投递一次正文，否则 agent 永远看不到新内容。 */
    @Test
    public void editingANoteDeliversTheNewTextOnce() {
        spirelink.util.Notes.set("monster", "Cultist", "旧内容");
        JsonObject first = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(first);
        JsonObject m1 = first.getAsJsonObject("combat").getAsJsonArray("monsters")
                .get(0).getAsJsonObject();
        assertEquals("旧内容", m1.get("note").getAsString());
        long v1 = m1.get("note_updated_at").getAsLong();

        spirelink.util.Notes.set("monster", "Cultist", "新内容");
        JsonObject second = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(second);
        JsonObject m2 = second.getAsJsonObject("combat").getAsJsonArray("monsters")
                .get(0).getAsJsonObject();
        assertEquals("改写后重新投递正文", "新内容", m2.get("note").getAsString());
        assertEquals("new", m2.get("note_state").getAsString());
        assertTrue("版本号必须前进（同一秒内也要）", m2.get("note_updated_at").getAsLong() > v1);

        // 再下一帧又回到 seen
        JsonObject third = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(third);
        JsonObject m3 = third.getAsJsonObject("combat").getAsJsonArray("monsters")
                .get(0).getAsJsonObject();
        assertEquals("seen", m3.get("note_state").getAsString());
        assertTrue(m3.get("note").isJsonNull());
    }

    /** Issue #127: force_notes 一次取回全文，之后恢复去重。 */
    @Test
    public void forceNotesResendsTheTextOnce() {
        spirelink.util.Notes.set("monster", "Cultist", "遇 邪教徒：1血 意图未知");
        JsonObject warmup = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(warmup);

        spirelink.util.NotesDedupe.setForce(true);
        JsonObject forced = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(forced);
        JsonObject m = forced.getAsJsonObject("combat").getAsJsonArray("monsters")
                .get(0).getAsJsonObject();
        assertEquals("force_notes 必须重新给全文", "遇 邪教徒：1血 意图未知", m.get("note").getAsString());
        assertEquals("new", m.get("note_state").getAsString());

        spirelink.util.NotesDedupe.setForce(false);
        JsonObject after = new JsonParser().parse(BODY).getAsJsonObject();
        NoteAnnotator.annotate(after);
        JsonObject m2 = after.getAsJsonObject("combat").getAsJsonArray("monsters")
                .get(0).getAsJsonObject();
        assertEquals("强制一次之后恢复去重", "seen", m2.get("note_state").getAsString());
    }
}
