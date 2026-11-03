package spirelink.state;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Issue #107（第二轮）：涅奥屏的 {@code event.text} 只能是涅奥自己的正文。
 *
 * <p>QA 第 7 轮、第 8 轮（同进程 3/3）都看到涅奥屏报出上一局 BigFish 的
 * 「你吃下了香蕉，它很有营养，似乎还有些魔法，回复了你的生命。」。第 7 轮的判据是「这个事件自己的
 * {@code imageEventText.title} 是 String ⇒ 这一页是它显示的」，第 8 轮证明它不成立：反编译
 * {@code desktop-1.0.jar} 可见 {@code GenericEventDialog} 的构造函数就是
 * {@code ldc ""; putfield title}（常量池 #12 = String #285 = 空串），也就是<b>任何新建事件的 title
 * 从构造那一刻起就是 {@code ""}</b>。判据因此永远放行，全进程 static words 里上一局的那一页原样
 * 透传成涅奥正文。
 *
 * <p>所以修法不靠「title 不是 null」，而是三条各自独立、都能单独证明的闸门：
 *
 * <ol>
 *   <li><b>类族</b>{@link EventPayload#canDisplayImagePage}：只有 {@code AbstractImageEvent} 会调用
 *       {@code imageEventText.show(title, text)} 去写那一页；涅奥是房间事件，javap 核对其全部 dialog
 *       调用只有 {@code roomEventText}（clear / addDialogOption / updateDialogOption /
 *       clearRemainingOptions / getSelectedOption / waitForInput），从不碰 {@code imageEventText}。</li>
 *   <li><b>实例证据</b>{@link EventPayload#imagePageTitle}：只有 {@code show()} 会覆盖 title，而构造
 *       函数留下的是空串，所以非空 title 才是「这个实例显示过这一页」的唯一痕迹。</li>
 *   <li><b>纯判据</b>{@link EventPayload#ownedDialogText}：正文非空 + 标题非空 + 显示者类一致，
 *       三条全中才放行。</li>
 * </ol>
 *
 * <p>下面两条硬事实用真实 vanilla 类做<b>行为断言</b>（都不需要开游戏）：
 * {@code new GenericEventDialog().title == ""}，以及 {@code NeowEvent} 不属于
 * {@code AbstractImageEvent} 族。这样以后谁把「title 判归属」加回来，单测会先红。
 */
public class NeowTextProvenanceTest {

    /** 上一局 BigFish 留在 static words 里的那一页（QA 原文）。 */
    private static final String BIG_FISH_PAGE = "你吃下了香蕉，它很有营养，似乎还有些魔法，回复了你的生命。";

    // ------------------------------------------- 硬事实 1：新建 dialog 的 title 就是空串

    @Test
    public void aVanillaDialogIsBornWithAnEmptyTitle() {
        com.megacrit.cardcrawl.events.GenericEventDialog dialog =
                new com.megacrit.cardcrawl.events.GenericEventDialog();
        Object title = spirelink.util.FieldKit.peek(dialog, "title");
        assertTrue("title 从构造起就是 String（所以「是 String」不能当归属证据）",
                title instanceof String);
        assertEquals("而且就是空串：GenericEventDialog 构造函数 ldc \"\" / putfield title"
                        + "（第 8 轮根因，别再用 title 非 null 判归属）",
                "", title);
    }

    // ------------------------------------------- 硬事实 2：类族闸门

    @Test
    public void onlyTheImageEventFamilyCanEverDisplayTheSharedPage() {
        assertFalse("涅奥是房间事件：javap 里它只碰 roomEventText，从不碰 imageEventText",
                EventPayload.canDisplayImagePage(com.megacrit.cardcrawl.neow.NeowEvent.class));
        assertFalse("抽象房间事件基类同样不是图片事件族",
                EventPayload.canDisplayImagePage(com.megacrit.cardcrawl.events.AbstractEvent.class));
        assertTrue("图片事件族才是这一页的写者",
                EventPayload.canDisplayImagePage(com.megacrit.cardcrawl.events.AbstractImageEvent.class));
        assertFalse("没有事件就没有类族", EventPayload.canDisplayImagePage(null));
    }

    // ------------------------------------------- 端到端复刻 QA 现场（static words 真有上一局的页）

    /**
     * QA 第 8 轮的现场，端到端复刻（同样不需要开游戏）：全进程 {@code static words} 里还留着上一局
     * BigFish 的那一页，当前屏幕换成房间事件（涅奥这类，从不写 {@code imageEventText}）。
     *
     * <p>先断言「现场确实有别人的正文」（读得到），再断言判据回空串 —— 否则空串可能只是「读不到」，
     * 这条回归就白写了。static 状态测完清空，不污染别的用例。
     */
    @Test
    public void aRoomEventScreenNeverAnswersWithTheLeftoverPage() throws Exception {
        List<Object> words = staticWords();
        words.clear();
        words.add(new FakeWord(BIG_FISH_PAGE));
        try {
            assertEquals("现场：共享图片页里确实有上一局 BigFish 的正文", BIG_FISH_PAGE,
                    EventPayload.dialogText());
            assertEquals("房间事件永远拿不到这张共享页（#107 二轮）", "",
                    EventPayload.dialogTextFor(new RoomOnlyEvent()));
            assertEquals("新建事件的 title 就是空串（第 8 轮根因），所以它不能当归属证据", "",
                    EventPayload.imagePageTitle(new RoomOnlyEvent()));
        } finally {
            words.clear();
        }
    }

    // ------------------------------------------- 纯判据：三种输入，只有一种放行

    @Test
    public void anEmptyTitleNeverProvesOwnership() {
        assertEquals("空标题不得放行（第 7 轮就栽在这里）", "",
                EventPayload.ownedDialogText(BIG_FISH_PAGE, "", "BigFish", "BigFish"));
        assertEquals("null 标题同样不算证据", "",
                EventPayload.ownedDialogText(BIG_FISH_PAGE, null, "BigFish", "BigFish"));
    }

    @Test
    public void aTitleFromAnotherClassIsStillSomebodyElsesPage() {
        assertEquals("标题非空、但显示者不是当前屏的事件：不能拿", "",
                EventPayload.ownedDialogText(BIG_FISH_PAGE, "大鱼", "BigFish", "NeowEvent"));
    }

    @Test
    public void onlySameClassAndNonEmptyTitleAnswers() {
        assertEquals("类相同 + 标题非空 = 这一页确实是它显示的", BIG_FISH_PAGE,
                EventPayload.ownedDialogText(BIG_FISH_PAGE, "大鱼", "BigFish", "BigFish"));
    }

    @Test
    public void emptyPagesAndUnknownClassesStayEmpty() {
        assertEquals("", EventPayload.ownedDialogText(null, "大鱼", "BigFish", "BigFish"));
        assertEquals("", EventPayload.ownedDialogText("", "大鱼", "BigFish", "BigFish"));
        assertEquals("干净新局的基线就是一个空串", "",
                EventPayload.ownedDialogText("", "", "NeowEvent", "NeowEvent"));
        assertEquals("", EventPayload.ownedDialogText(BIG_FISH_PAGE, "大鱼", null, "BigFish"));
        assertEquals("", EventPayload.ownedDialogText(BIG_FISH_PAGE, "大鱼", "BigFish", null));
    }

    @Test
    public void thePageIsHandedOverVerbatim() {
        // 归属判定不碰文字本身：清洗仍然只有下游那一次（cleanMarkup），不会二次加工。
        assertEquals("#y 标记原样交回，交给下游清洗", "你的 #yHP 是 5 。",
                EventPayload.ownedDialogText("你的 #yHP 是 5 。", "营火", "Bonfire", "Bonfire"));
    }

    // ------------------------------------------- 帧级读取器

    @Test
    public void noEventMeansNoTitleAndNoText() {
        assertNull("没有事件就没有标题证据", EventPayload.imagePageTitle(null));
        assertEquals("没有事件就没有正文", "", EventPayload.dialogTextFor(null));
        // 无游戏环境：static words 读不到，登录/主菜单帧也一样要回空串，绝不允许抛。
        assertEquals("", EventPayload.dialogText());
    }

    // ------------------------------------------- 调用点 wiring（方法体要 AbstractDungeon 才能跑）

    @Test
    public void buildNeowEventReadsThroughTheOwnershipCheck() throws IOException {
        String neow = methodBody(source("src/main/java/spirelink/state/StateBuilder.java"),
                "private static JsonObject buildNeowEvent()");
        assertTrue("涅奥正文必须走归属判定（#107）", neow.contains("EventPayload.dialogTextFor("));
        assertFalse("不许再无条件读全进程 static 文字", neow.contains("EventPayload.dialogText()"));
        assertTrue("读不到时仍要回落到事件自己的 body 字段（实例字段，不可能是上一局的页）",
                neow.contains("fieldOrNull(room.event, \"body\")"));
        assertTrue("现有文本清洗保持不变（一次 cleanMarkup）",
                neow.contains("NoteHud.cleanMarkup(body)"));
    }

    @Test
    public void theOwnershipCheckHasAllThreeGates() throws IOException {
        String whole = source("src/main/java/spirelink/state/EventPayload.java");
        String payload = methodBody(whole, "public static String dialogTextFor(AbstractEvent event)");
        assertTrue("闸门 1：类族（只有 AbstractImageEvent 会显示这一页）",
                payload.contains("canDisplayImagePage("));
        assertTrue("闸门 2：实例证据（自有对话框的 title）", payload.contains("imagePageTitle("));
        assertTrue("闸门 3：纯判据（标题非空 + 类一致）", payload.contains("ownedDialogText("));
        assertFalse("旧的「title 是 String 就算归属」判据已删除，不许加回", whole.contains("imagePageOwner"));
    }

    // ------------------------------------------- helpers

    /** A room event with no game behind it: enough for the provenance gates (no dialog is shown). */
    private static final class RoomOnlyEvent extends com.megacrit.cardcrawl.events.AbstractEvent {
        @Override
        protected void buttonEffect(int buttonIndex) {
            // No options on this test event: the gates never call this.
        }
    }

    /** {@code wordsText} only reads a {@code word} field off each entry, so no real DialogWord is needed. */
    private static final class FakeWord {
        public String word;

        FakeWord(String word) {
            this.word = word;
        }
    }

    /** The vanilla process-wide word list behind {@code GenericEventDialog}. */
    @SuppressWarnings("unchecked")
    private static List<Object> staticWords() throws Exception {
        java.lang.reflect.Field field = com.megacrit.cardcrawl.events.GenericEventDialog.class
                .getDeclaredField("words");
        field.setAccessible(true);
        Object words = field.get(null);
        assertTrue("static words 必须存在，否则这条回归测试没有意义", words instanceof List);
        return (List<Object>) words;
    }

    private static String source(String relative) throws IOException {
        File dir = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                // Normalised: some sources are CRLF (StateBuilder.java is), others LF.
                return new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n");
            }
            dir = dir.getParentFile();
        }
        throw new IOException("找不到 " + relative);
    }

    /** Body of a 4-space indented method: from its signature to the first closing brace at that level. */
    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        if (at < 0) {
            fail("找不到方法: " + signature);
        }
        int end = source.indexOf("\n    }\n", at);
        if (end < 0) {
            fail("找不到方法结尾: " + signature);
        }
        return source.substring(at, end);
    }
}
