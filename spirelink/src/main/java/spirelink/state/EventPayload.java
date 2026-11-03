package spirelink.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.events.GenericEventDialog;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.EventRoom;
import com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton;

import java.util.List;

/**
 * The {@code event} block of get_state: text, options and the agent's own note.
 *
 * <p>Issue #75 (round 2) hangs on two facts that this class owns:
 *
 * <ul>
 *   <li>{@link #decisionToken()} - the only event fact allowed to move {@code state_version}. It is
 *       the option set (count / labels / disabled mask) plus the event id; <em>no text</em>.</li>
 *   <li>{@link #progressToken()} - everything the agent <em>reads</em> (live text + typing flag +
 *       options). It moves on every animation tick on purpose and must never reach the version.</li>
 * </ul>
 *
 * <p>{@code text_typing} used to be read from {@code event.roomEventText.waitTimer}: {@code
 * roomEventText} is a {@code RoomEventDialog} (it has {@code textDone}/{@code wordTimer}, not {@code
 * waitTimer}) and most events - Bonfire and the Cleric among them - type into {@code
 * imageEventText}, a {@code GenericEventDialog} whose {@code textDone} is <em>static</em>. The field
 * therefore never existed, {@code fieldOrNull} answered null and {@code text_typing} was false on
 * every screen, which is exactly what QA saw. It now comes from the dialog that is on screen.
 */
public final class EventPayload {

    private EventPayload() {
    }

    /** Null when the current room is not an event room. */
    public static JsonObject build() {
        try {
            // The previous room stays mounted while a run-over screen is up; reporting its event
            // there made the agent read stale text (the death screen showed an old event's body).
            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.DEATH
                    || AbstractDungeon.screen == AbstractDungeon.CurrentScreen.VICTORY
                    || AbstractDungeon.screen == AbstractDungeon.CurrentScreen.CREDITS) {
                return null;
            }
            AbstractRoom room = roomOrNull();
            if (!(room instanceof EventRoom)) {
                return null;
            }
            if (room.phase != AbstractRoom.RoomPhase.EVENT) {
                return null;
            }
            AbstractEvent event = ((EventRoom) room).event;
            if (event == null) {
                return null;
            }
            String id = event.getClass().getSimpleName();
            JsonObject out = new JsonObject();
            out.addProperty("id", id);
            // Prefer the live event's own name: the localization table returns the "MISSING_NAME"
            // placeholder for several events, which read like a bug to the agent.
            Object liveName = spirelink.util.FieldKit.fieldOrNull(event, "name");
            Object packName = spirelink.util.FieldKit.fieldOrNull(event, "NAME");
            String resolved = null;
            if (liveName instanceof String && !((String) liveName).isEmpty()
                    && !"MISSING_NAME".equals(liveName)) {
                resolved = (String) liveName;
            } else if (packName instanceof String && !((String) packName).isEmpty()
                    && !"MISSING_NAME".equals(packName)) {
                resolved = (String) packName;
            }
            out.addProperty("name", resolved == null ? id : resolved);

            // Issue #75: the text the agent reads is the ACTIVE dialog's (see {@link Dialogs}); it is
            // re-read on every tick so a page change / re-typed body reaches the agent even though the
            // version number does not move.
            Dialogs dialogs = readDialogs(event);
            Object body = spirelink.util.FieldKit.fieldOrNull(event, "body");
            String source = pickBody(pickLiveText(dialogs), body instanceof String ? (String) body : null);
            if (source == null) {
                out.addProperty("body", "");
                out.addProperty("body_unavailable", true);
            } else {
                // Issue #76: marked body (【格挡】 etc.) plus a keywords array next to it, and the same
                // cleaner for every source so one screen cannot answer two ways.
                String clean = cleanEventText(source);
                spirelink.util.Glossary.addMarked(out, "body",
                        clean.length() > 2000 ? clean.substring(0, 2000) : clean);
            }

            List<LargeDialogOptionButton> options = spirelink.actions.ActionPlanner.dialogOptions();
            JsonArray optionArray = new JsonArray();
            if (options != null) {
                for (int i = 0; i < options.size(); i++) {
                    LargeDialogOptionButton button = options.get(i);
                    if (button == null) {
                        continue;
                    }
                    JsonObject option = new JsonObject();
                    option.addProperty("index", i);
                    // Same cleaner as the body: an option label is prose the agent reads, and #76 is
                    // about "the same object must always come back the same" (#y/NL noise included).
                    String text = cleanEventText(button.msg == null ? "" : button.msg);
                    if (text.length() > 500) {
                        text = text.substring(0, 500);
                    }
                    spirelink.util.Glossary.addMarked(option, "text", text);
                    option.addProperty("disabled", button.isDisabled);
                    optionArray.add(option);
                }
            }
            out.add("options", optionArray);

            // Issue #75 round 2: text_typing is the ACTIVE dialog's "still typing" state, and
            // text_source says which dialog answered ("none" = no readable flag, typing unknown).
            String typingSource = typingSource(dialogs);
            out.addProperty("text_typing", typingFlag(dialogs));
            out.addProperty("text_source", typingSource);
            if ("none".equals(typingSource)) {
                noteMissingTypingSignal(id);
            }

            String note = spirelink.util.Notes.get("event", id);
            if (note == null) {
                out.add("note", com.google.gson.JsonNull.INSTANCE);
            } else {
                out.addProperty("note", note);
                // Issue #124 · C15-02: same marker as every entity note (NoteAnnotator): the text is
                // history, the live fields of this payload are the current truth.
                out.addProperty("note_history", true);
                long updated = spirelink.util.Notes.updatedAt("event", id);
                if (updated > 0) {
                    out.addProperty("note_updated_at", updated);
                }
            }
            return out;
        } catch (Throwable t) {
            // Transition frames can have a half-built event; log at most once every 10 s so a real
            // problem stays visible instead of drowning in WARN spam.
            noteOnce("event 读取失败: ", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ dialogs (#75)

    /**
     * The two dialogs every {@code AbstractEvent} owns, as plain values.
     *
     * <p>An event types into exactly one of them: image events (Bonfire, the Cleric, GremlinWheelGame)
     * into {@code imageEventText}, a per-event {@link GenericEventDialog} whose {@code words} and
     * {@code textDone} are <em>static</em>; room events (Neow, DeadAdventurer, ...) into {@code
     * roomEventText}, a {@link com.megacrit.cardcrawl.events.RoomEventDialog} with instance fields of
     * the same names. Reading the wrong pair is what made {@code text_typing} constant false.
     */
    static final class Dialogs {
        /** True for an {@link com.megacrit.cardcrawl.events.AbstractImageEvent} (drives imageEventText). */
        final boolean imageFlavour;
        final boolean imageShown;
        /** null when the flag could not be read (never "false" - that would invent a typing state). */
        final Boolean imageTextDone;
        final String imageText;
        final boolean roomShown;
        final Boolean roomTextDone;
        final String roomText;

        Dialogs(boolean imageFlavour, boolean imageShown, Boolean imageTextDone, String imageText,
                boolean roomShown, Boolean roomTextDone, String roomText) {
            this.imageFlavour = imageFlavour;
            this.imageShown = imageShown;
            this.imageTextDone = imageTextDone;
            this.imageText = imageText == null ? "" : imageText;
            this.roomShown = roomShown;
            this.roomTextDone = roomTextDone;
            this.roomText = roomText == null ? "" : roomText;
        }
    }

    /** Which dialog is on screen, as a label for {@code event.text_source}. */
    static String typingSource(Dialogs dialogs) {
        if (dialogs == null) {
            return "none";
        }
        boolean imageHasText = !dialogs.imageText.trim().isEmpty();
        boolean roomHasText = !dialogs.roomText.trim().isEmpty();
        if (imageHasText && roomHasText) {
            return shownOrFlavour(dialogs);
        }
        if (roomHasText) {
            return "room";
        }
        if (imageHasText) {
            return "image";
        }
        // Neither has text yet (the page was just set): fall back to what is shown / what the event
        // class drives, and only answer "none" when no textDone flag is readable at all.
        String guess = shownOrFlavour(dialogs);
        boolean image = "image".equals(guess);
        Boolean chosen = image ? dialogs.imageTextDone : dialogs.roomTextDone;
        Boolean other = image ? dialogs.roomTextDone : dialogs.imageTextDone;
        if (chosen != null) {
            return guess;
        }
        if (other != null) {
            return image ? "room" : "image";
        }
        return "none";
    }

    /**
     * The dialog that is live: the one currently shown; when both (or neither) are shown, the one the
     * event's own class types into. An image event never writes {@code roomEventText} and a room event
     * never writes {@code imageEventText} (checked against the vanilla classes), so the class is the
     * honest tiebreaker.
     */
    private static String shownOrFlavour(Dialogs dialogs) {
        if (dialogs.imageShown != dialogs.roomShown) {
            return dialogs.imageShown ? "image" : "room";
        }
        return dialogs.imageFlavour ? "image" : "room";
    }

    /** True while the active dialog is still animating its words out. */
    static boolean typingFlag(Dialogs dialogs) {
        String source = typingSource(dialogs);
        Boolean done;
        if ("image".equals(source)) {
            done = dialogs.imageTextDone;
        } else if ("room".equals(source)) {
            done = dialogs.roomTextDone;
        } else {
            return false;
        }
        return Boolean.FALSE.equals(done);
    }

    /** The text of the dialog that is on screen ("" when none has any). */
    static String pickLiveText(Dialogs dialogs) {
        String source = typingSource(dialogs);
        if ("room".equals(source)) {
            return dialogs.roomText;
        }
        if ("image".equals(source)) {
            return dialogs.imageText;
        }
        return "";
    }

    /**
     * Body text for the payload: the live dialog text wins (that is what the player sees), the
     * event's own {@code body} field is the last resort (it is frozen at the intro page for most
     * events, which is why it must not win when a dialog has text).
     */
    static String pickBody(String liveText, String eventBody) {
        if (liveText != null && !liveText.trim().isEmpty()) {
            return liveText;
        }
        if (eventBody != null && !eventBody.trim().isEmpty()) {
            return eventBody;
        }
        return null;
    }

    private static Dialogs readDialogs(AbstractEvent event) {
        Object roomDialog = event == null ? null : spirelink.util.FieldKit.fieldOrNull(
                event, "roomEventText");
        // "shown" is a plain boolean (absent = not shown); "textDone" stays a Boolean because null
        // means "no readable flag" and must never be unboxed into "not done" (= typing forever).
        return new Dialogs(
                event instanceof com.megacrit.cardcrawl.events.AbstractImageEvent,
                Boolean.TRUE.equals(spirelink.util.FieldKit.staticFieldOrNull(
                        GenericEventDialog.class, "show")),
                asBoolean(spirelink.util.FieldKit.staticFieldOrNull(
                        GenericEventDialog.class, "textDone")),
                imageDialogText(),
                Boolean.TRUE.equals(spirelink.util.FieldKit.fieldOrNull(roomDialog, "show")),
                asBoolean(spirelink.util.FieldKit.fieldOrNull(roomDialog, "textDone")),
                wordsText(spirelink.util.FieldKit.fieldOrNull(roomDialog, "words")));
    }

    private static Boolean asBoolean(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static volatile long lastMissingSignalAt;

    /** One line per 10 s when no dialog flag is readable: a real regression must stay visible. */
    private static void noteMissingTypingSignal(String id) {
        long now = System.currentTimeMillis();
        if (now - lastMissingSignalAt > 10_000L) {
            lastMissingSignalAt = now;
            spirelink.util.Diag.warn("event.text_typing 无信号源（class=" + id
                    + "）：imageEventText/roomEventText 的 textDone 都读不到，按 false 返回");
        }
    }

    private static volatile long lastWarnAt;

    private static void noteOnce(String prefix, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt > 10_000L) {
            lastWarnAt = now;
            spirelink.util.Diag.warn(prefix + t);
        }
    }

    /**
     * Raw text of the shared image dialog. <b>Diagnostics only</b>: the words are process-wide static
     * state with no owner (issue #107), so a payload must go through {@link #dialogTextFor} instead.
     *
     * <p>Deliberately still image-only: the {@code neow} screen is not an {@code EventRoom}, so it has
     * no body-refresh key and must not start answering with a half-typed page that nothing would ever
     * finish. Issue #75 is about event rooms, where the refresh key exists.
     */
    public static String dialogText() {
        return imageDialogText();
    }

    // --------------------------------------------------- text provenance (#107)

    /**
     * The shared image dialog's text, <b>but only when this frame can prove it belongs to
     * {@code event}</b> (issue #107). Anything else answers {@code ""}.
     *
     * <p>Why the check exists: {@code GenericEventDialog.words} is <em>static</em> - one page shared by
     * every image event of every run in this process - and nothing clears it when a run ends without
     * disposing its last event. {@code buildNeowEvent} used to read it unconditionally, so a brand new
     * Neow screen reported the previous run's last event body (QA round 7: BigFish's "你吃下了香蕉…").
     *
     * <p>Round 8 reproduced it 3/3 again, which means the round-7 proof ("this event's own title is a
     * String, so it displayed the page") was not a proof at all. Two facts, both read off the shipped
     * jar ({@code lib/desktop-1.0.jar}) with {@code javap} rather than guessed, are the contract now:
     *
     * <ol>
     *   <li><b>Only a non-empty title proves anything.</b> {@code GenericEventDialog}'s own constructor
     *       is {@code ldc ""; putfield title} (constant pool #12 = String #285 = the empty string), so
     *       every freshly constructed dialog - and therefore every freshly constructed event - already
     *       carries {@code ""}. "title is a String" is true before any page is shown and must never be
     *       accepted as ownership. Only {@code show(title, text)} overwrites the title, and it is the
     *       same call that replaces the static words: a <em>non-empty</em> title is the one
     *       per-instance trace of "this instance displayed this page".</li>
     *   <li><b>Only the image-event family can display that page at all.</b> {@code NeowEvent} extends
     *       {@code AbstractEvent}, not {@code AbstractImageEvent}, and its whole bytecode only ever
     *       touches its own {@code roomEventText} ({@code clear}, {@code addDialogOption},
     *       {@code updateDialogOption}, {@code clearRemainingOptions}, {@code getSelectedOption},
     *       {@code waitForInput}) - never {@code imageEventText}. A room event can therefore never own
     *       the shared page whatever the title says; its own body is {@code ""}, because Neow says its
     *       lines in speech bubbles ({@code InfiniteSpeechBubble}).</li>
     * </ol>
     *
     * <p>Do not add a "title is non-null" style check back: that is exactly what QA round 8 broke.
     * The three gates below are separate so each one is provable on its own -
     * {@link #canDisplayImagePage} (class family), {@link #imagePageTitle} (instance trace),
     * {@link #ownedDialogText} (pure decision, unit-tested).
     */
    public static String dialogTextFor(AbstractEvent event) {
        try {
            String raw = imageDialogText();
            String eventClass = event == null ? null : event.getClass().getSimpleName();
            String text = "";
            String rejection = null;
            if (event == null) {
                rejection = "当前没有事件";
            } else if (!canDisplayImagePage(event.getClass())) {
                rejection = "该类不是图片事件，从不写这一页";
            } else {
                String title = imagePageTitle(event);
                text = ownedDialogText(raw, title, eventClass, eventClass);
                if (text.isEmpty()) {
                    rejection = title == null || title.isEmpty()
                            ? "自有对话框的标题仍是构造函数的空串（没调用过 show）"
                            : "这一页的显示者类不一致";
                }
            }
            if (text.isEmpty() && raw != null && !raw.isEmpty()) {
                // A page nobody on this screen displayed is exactly the #107 shape: say it once per
                // 10 s so QA can see the check working instead of wondering why the body is empty.
                noteLeftoverPage(eventClass, rejection);
            }
            return text;
        } catch (Throwable t) {
            // A body is never worth a throw on a transition frame; "" is the same answer as "no owner".
            return "";
        }
    }

    private static volatile long lastLeftoverAt;

    private static void noteLeftoverPage(String eventClass, String reason) {
        long now = System.currentTimeMillis();
        if (now - lastLeftoverAt <= 10_000L) {
            return;
        }
        lastLeftoverAt = now;
        spirelink.util.Diag.warn("正文归属不符（" + reason + "）：共享图片对话框里还剩别人的正文，当前屏幕 "
                + eventClass + " 拿不到这一页，按空串返回（#107）");
    }

    /**
     * True when this event class can ever put a page into the shared image dialog.
     *
     * <p>Vanilla contract (checked with {@code javap} on the shipped jar): only
     * {@code AbstractImageEvent} calls {@code imageEventText.show(title, text)}; room events - Neow,
     * DeadAdventurer, ... - write their own {@code roomEventText} for options and never touch it. So a
     * Neow screen can never own {@code GenericEventDialog.words}, which is the second half of #107.
     *
     * <p>Takes a {@link Class} rather than an instance so a unit test can pin the vanilla hierarchy
     * without building a game.
     */
    static boolean canDisplayImagePage(Class<?> eventClass) {
        return eventClass != null && com.megacrit.cardcrawl.events.AbstractImageEvent.class
                .isAssignableFrom(eventClass);
    }

    /**
     * Title carried by {@code event}'s <em>own</em> {@code imageEventText}, or {@code null} when it
     * cannot be read.
     *
     * <p>{@code ""} is the vanilla constructor's default and means "this instance never called
     * {@code show()}" - it is the round-8 root cause and must be read as "no proof", never as an
     * event that displayed a page with an empty heading.
     */
    static String imagePageTitle(AbstractEvent event) {
        if (event == null) {
            return null;
        }
        // peek: the field is optional per event kind, and a missing one must not warn on every frame.
        Object ownDialog = spirelink.util.FieldKit.peek(event, "imageEventText");
        Object title = spirelink.util.FieldKit.peek(ownDialog, "title");
        return title instanceof String ? (String) title : null;
    }

    /**
     * Issue #107, pure form: may the shared page answer for this screen? Three facts, and only when
     * all three agree:
     *
     * <ul>
     *   <li>the page is not empty;</li>
     *   <li>the claimant carries a <em>non-empty</em> title - the only trace {@code show(title, text)}
     *       leaves on its own instance, since a fresh dialog's title defaults to {@code ""};</li>
     *   <li>the claimant's class is the class the screen is showing.</li>
     * </ul>
     *
     * <p>{@code pageClass} is its own parameter - not {@code eventClass} twice - because the rule
     * being pinned is "a page belongs to the instance that displayed it". The production caller reads
     * the title off the event on screen, so today the two names are equal; a test can still hand over
     * a different one and prove the mismatch branch rejects.
     */
    static String ownedDialogText(String dialogText, String pageTitle, String pageClass,
            String eventClass) {
        if (dialogText == null || dialogText.isEmpty()) {
            return "";
        }
        if (pageTitle == null || pageTitle.isEmpty()) {
            return "";
        }
        if (pageClass == null || eventClass == null) {
            return "";
        }
        return pageClass.equals(eventClass) ? dialogText : "";
    }

    private static AbstractRoom roomOrNull() {
        try {
            // getCurrRoom() dereferences currMapNode without a null check: calling it on the main menu
            // kills the frame, and these token readers run once per frame.
            if (AbstractDungeon.currMapNode == null) {
                return null;
            }
            return AbstractDungeon.getCurrRoom();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Text currently held by the image dialog's (static) word list. */
    private static String imageDialogText() {
        return wordsText(spirelink.util.FieldKit.staticFieldOrNull(
                com.megacrit.cardcrawl.events.GenericEventDialog.class, "words"));
    }

    /** Joins a {@code List<DialogWord>}: one {@code DialogWord} per word, one per <em>char</em> in CJK. */
    private static String wordsText(Object words) {
        if (!(words instanceof List)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(256);
        for (Object word : (List<?>) words) {
            Object text = spirelink.util.FieldKit.fieldOrNull(word, "word");
            if (text instanceof String) {
                appendDialogWord(sb, (String) text);
            }
        }
        return sb.toString().trim();
    }

    /**
     * Issue #76: the dialog stores one {@code DialogWord} per word - and for Chinese text that is one
     * word <em>per character</em>, so the old "append(word + ' ')" turned every sentence into
     * "当 你 走 过 一 条 长 廊". A space is only inserted where the original text had one: between two
     * non-CJK tokens. Result is stable and re-cleanable.
     */
    static void appendDialogWord(StringBuilder out, String word) {
        if (word == null || word.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            char previous = out.charAt(out.length() - 1);
            char next = word.charAt(0);
            // Both sides wide = the spacer is an artefact of the per-character word list.
            if (previous != ' ' && !isWide(previous) && !isWide(next)) {
                out.append(' ');
            }
        }
        out.append(word);
    }

    /** CJK / full-width characters, where a space is never part of the original wording. */
    static boolean isWide(char c) {
        return c >= 0x2E80;
    }

    /**
     * Issue #76: the one deterministic post-processing for event prose. Same input always gives the
     * same output, and cleaning twice changes nothing, because the alternation QA saw (raw markers
     * vs. per-character spacing) came from two different sources being formatted differently.
     *
     * <p>B2-18: the pipeline itself now lives in {@link ProseClean} and is shared with cards, potions,
     * relics and powers. One cleaner, one answer - the same kind of marker cannot be stripped on one
     * screen and survive on another (which is exactly how {@code NL} got into card text).
     */
    static String cleanEventText(String raw) {
        return ProseClean.cleanEvent(raw);
    }

    // ------------------------------------------------------------------ the two tokens (#75)

    /**
     * The only event fact that may move {@code state_version}: <b>the option set</b>.
     *
     * <p>The version answers one question - "is this still the same decision point?" - so it carries
     * the event id, how many options there are, what they say and which are disabled. The body text
     * is deliberately absent, and so is {@code textDone}: a body that is typing out, re-typed from
     * the top, or replaced by the next story page never changes what the agent may click.
     *
     * <p>QA round 2 measured the old behaviour: one Bonfire page change produced 28 -> 29 -> 30 in
     * 70 ms (typing restarted, then finished) and a Cleric screen moved the version with an identical
     * body/options/ready - every one of those bumps was text, not a decision.
     *
     * <p>MaskedBandits still bumps: "付钱" -> "继续" changes the label and the legal action list.
     */
    public static String decisionToken() {
        try {
            AbstractRoom room = roomOrNull();
            if (!(room instanceof EventRoom)) {
                return "-";
            }
            AbstractEvent event = ((EventRoom) room).event;
            if (event == null) {
                return "-";
            }
            List<LargeDialogOptionButton> options = spirelink.actions.ActionPlanner.dialogOptions();
            return decisionToken(event.getClass().getSimpleName(),
                    options == null ? 0 : options.size(),
                    optionLabels(options), disabledMask(options));
        } catch (Throwable t) {
            return "-";
        }
    }

    /**
     * Everything about the event screen that the agent <em>reads</em>: id + live text + typing flag +
     * the option set. This is the {@code event} block's content, so it changes on every animation
     * tick by design and must never reach {@code state_version}.
     *
     * <p>Two callers: {@link StateBuilder} uses it as the "only the body moved, refresh it quietly"
     * key, and the executor compares it before/after a click to prove the click did something.
     */
    public static String progressToken() {
        try {
            AbstractRoom room = roomOrNull();
            if (!(room instanceof EventRoom) || room.phase != AbstractRoom.RoomPhase.EVENT) {
                return "-";
            }
            AbstractEvent event = ((EventRoom) room).event;
            if (event == null) {
                return "-";
            }
            Dialogs dialogs = readDialogs(event);
            List<LargeDialogOptionButton> options = spirelink.actions.ActionPlanner.dialogOptions();
            return bodyToken(event.getClass().getSimpleName(),
                    cleanEventText(pickLiveText(dialogs)), typingFlag(dialogs),
                    options == null ? 0 : options.size(),
                    optionLabels(options), disabledMask(options));
        } catch (Throwable t) {
            // getCurrRoom() NPEs outside a run / on transition frames: a token is never worth a throw.
            return "-";
        }
    }

    /** Pure form of {@link #decisionToken()}: the version half. No text, no typing. */
    static String decisionToken(String id, int optionCount, String labels, int disabledMask) {
        return (id == null ? "-" : id) + "|o=" + optionCount + "|d=" + disabledMask
                + "|l=" + Integer.toHexString(labels == null ? 0 : labels.hashCode());
    }

    /** Pure form of {@link #progressToken()}: the body half. Changes on every typing tick. */
    static String bodyToken(String id, String text, boolean typing, int optionCount,
            String labels, int disabledMask) {
        String clean = text == null ? "" : text;
        return decisionToken(id, optionCount, labels, disabledMask)
                + "|t=" + Integer.toHexString(clean.hashCode())
                + "|n=" + clean.length()
                + "|ty=" + typing;
    }

    /** Option labels joined: part of both tokens (a relabelled button is a new decision). */
    static String optionLabels(List<LargeDialogOptionButton> options) {
        StringBuilder sb = new StringBuilder();
        if (options != null) {
            for (LargeDialogOptionButton option : options) {
                sb.append(option == null ? "-" : String.valueOf(option.msg).trim()).append('|');
            }
        }
        return sb.toString();
    }

    /**
     * Which options are disabled, as a bit mask. The legal action list contains {@code choose_option}
     * for a disabled button too (so the agent can see it), so without this a button that becomes
     * clickable would not move the version.
     */
    static int disabledMask(List<LargeDialogOptionButton> options) {
        int mask = 0;
        if (options != null) {
            for (int i = 0; i < options.size() && i < 32; i++) {
                LargeDialogOptionButton option = options.get(i);
                if (option != null && option.isDisabled) {
                    mask |= (1 << i);
                }
            }
        }
        return mask;
    }

    /** Current event id, or null. Used by the note tools. */
    public static String currentEventId() {
        JsonObject payload = build();
        if (payload == null || !payload.has("id")) {
            return null;
        }
        return payload.get("id").getAsString();
    }
}
