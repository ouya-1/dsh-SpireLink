package spirelink.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Before;
import org.junit.Test;
import spirelink.state.StateBuilder;
import spirelink.util.ModLog;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #59: {@code note_set} without {@code text} used to answer {@code ok:true} and <b>delete</b>
 * the note, because {@code Notes.set(kind, id, null)} treats null as blank and blank means delete.
 *
 * <p>The documented rule is "an explicit empty string deletes, nothing else does", so the matrix is:
 * missing → BAD_REQUEST, null → BAD_REQUEST, {@code ""} → delete, text → write. Every assertion here
 * is on the pure argument contract, so the human's {@code notes.json} is never touched.
 */
public class NoteSetValidationTest {

    private ToolCatalog catalog;

    @Before
    public void setUp() {
        ModLog log = new ModLog(new File("target/note-validation-test"), false);
        catalog = new ToolCatalog(new MainThreadQueue(), new StateBuilder(log, "test"), log);
    }

    private static JsonObject args(String json) {
        return new JsonParser().parse(json).getAsJsonObject();
    }

    // ------------------------------------------------------------------ the three-way matrix

    /** 漏传 text → BAD_REQUEST，文案要告诉 agent「删除请显式传空串」。 */
    @Test
    public void missingTextIsRejectedWithTheDeleteRecipe() {
        JsonObject a = args("{\"kind\":\"general\",\"id\":\"run-1\"}");
        String message = ToolCatalog.noteSetArgumentError(a, ToolCatalog.TOOL_NOTE_SET);
        assertNotNull("漏传 text 必须被拒", message);
        assertTrue("文案要点名 text: " + message, message.contains("text"));
        assertTrue("文案要说清删除写法: " + message, message.contains("\"\""));
        assertTrue("文案要提 issue 语义: " + message, message.contains("删除"));
    }

    /** 显式 null 与漏传同罪：都不是「删除」的意思。 */
    @Test
    public void nullTextIsRejected() {
        String message = ToolCatalog.noteSetArgumentError(
                args("{\"kind\":\"card\",\"id\":\"Strike_R\",\"text\":null}"),
                ToolCatalog.TOOL_NOTE_SET);
        assertNotNull(message);
        assertTrue(message.contains("text"));
    }

    /** 空串 = 删除：这是文档承诺的唯一删除写法，必须放行（真正的删除发生在 Notes.set 里）。 */
    @Test
    public void emptyTextMeansDeleteAndPassesValidation() {
        assertNull(ToolCatalog.noteSetArgumentError(
                args("{\"kind\":\"card\",\"id\":\"Strike_R\",\"text\":\"\"}"),
                ToolCatalog.TOOL_NOTE_SET));
    }

    /** 正常文本放行。 */
    @Test
    public void realTextPassesValidation() {
        assertNull(ToolCatalog.noteSetArgumentError(
                args("{\"kind\":\"monster\",\"id\":\"Louse\",\"text\":\"先杀叫的\"}"),
                ToolCatalog.TOOL_NOTE_SET));
    }

    /** text 类型错误（数字/对象/数组）也要拒，不能当空串删了。 */
    @Test
    public void nonStringTextIsRejected() {
        for (String raw : new String[]{"123", "{}", "[]", "true"}) {
            String message = ToolCatalog.noteTextArgumentError(
                    args("{\"kind\":\"general\",\"id\":\"x\",\"text\":" + raw + "}"),
                    ToolCatalog.TOOL_NOTE_SET);
            assertNotNull("text=" + raw + " 必须被拒", message);
            assertTrue("文案要点名字符串: " + message, message.contains("字符串"));
        }
    }

    // ------------------------------------------------------------------ kind / id 仍然必填

    @Test
    public void kindAndIdStayRequired() {
        assertNotNull(ToolCatalog.noteSetArgumentError(
                args("{\"id\":\"x\",\"text\":\"a\"}"), ToolCatalog.TOOL_NOTE_SET));
        assertNotNull(ToolCatalog.noteSetArgumentError(
                args("{\"kind\":\"bogus\",\"id\":\"x\",\"text\":\"a\"}"),
                ToolCatalog.TOOL_NOTE_SET));
        assertNotNull(ToolCatalog.noteSetArgumentError(
                args("{\"kind\":\"general\",\"text\":\"a\"}"), ToolCatalog.TOOL_NOTE_SET));
    }

    // ------------------------------------------------------------------ 线上回包形状

    /** 走一遍真实工具调用：漏 text 的答案是 ok=false + BAD_REQUEST，且没有删掉任何东西。 */
    @Test
    public void toolCallAnswersBadRequestInsteadOfDeleting() {
        ToolCatalog.ToolResult result = catalog.call(ToolCatalog.TOOL_NOTE_SET,
                args("{\"kind\":\"general\",\"id\":\"qa-note-1\"}"));
        assertNotNull(result);
        assertTrue("必须是工具级错误", result.isError);
        JsonObject payload = result.payload;
        assertFalse(payload.get("ok").getAsBoolean());
        // Issue #86: the error is an object now, not a bare string code.
        JsonObject error = payload.getAsJsonObject("error");
        assertNotNull("error 必须是对象: " + payload, error);
        assertEquals("BAD_REQUEST", error.get("code").getAsString());
        assertTrue("message 要在 error 里", error.get("message").getAsString().contains("text"));
        assertFalse("顶层 message 必须收进 error 里", payload.has("message"));
    }

    /** event_note 的同一张网：里层校验与 note_set 共用，事件房里漏 text 同样拒绝。 */
    @Test
    public void eventNoteSharesTheSameTextRule() {
        String message = ToolCatalog.noteTextArgumentError(
                args("{}"), ToolCatalog.TOOL_EVENT_NOTE);
        assertNotNull(message);
        assertTrue(message.contains("text"));
        assertNull(ToolCatalog.noteTextArgumentError(
                args("{\"text\":\"\"}"), ToolCatalog.TOOL_EVENT_NOTE));
    }
}
