package spirelink.ui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns agent notes into the one HUD line humans can see ("the agent really does take notes").
 *
 * <p>Kept apart from {@link AgentHud} so the label/name lookups live with the game types while the
 * buffer stays pure. Every method swallows its own errors: a HUD nicety must never fail an MCP call.
 */
public final class NoteHud {

    /** How long each kind of line stays on screen. */
    public static final long WROTE_MS = 9000L;
    public static final long READ_MS = 9000L;
    public static final long SEEN_MS = 6000L;
    /** Passive "has a note" lines repeat at most this often per key. */
    private static final long SEEN_DEDUPE_MS = 60000L;
    /** Full-width characters kept from the note body before the ellipsis. */
    private static final int BODY_CHARS = 24;

    private static final Map<String, String> KIND_ZH = new ConcurrentHashMap<String, String>();
    private static final Map<String, Long> SEEN_AT = new ConcurrentHashMap<String, Long>();

    static {
        KIND_ZH.put("event", "事件");
        KIND_ZH.put("potion", "药水");
        KIND_ZH.put("card", "卡牌");
        KIND_ZH.put("monster", "敌人");
        KIND_ZH.put("relic", "遗物");
        KIND_ZH.put("general", "通用");
    }

    private NoteHud() {
    }

    public static String kindZh(String kind) {
        String zh = kind == null ? null : KIND_ZH.get(kind);
        return zh == null ? (kind == null ? "通用" : kind) : zh;
    }

    /** {@code [事件] 大鱼肉：选 1 回血}, with the body cut to ~24 characters. */
    public static String label(String kind, String id, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(kindZh(kind)).append("] ");
        sb.append(displayName(kind, id));
        String body = text == null ? "" : cleanMarkup(text).replace("\r", " ").replace("\n", " ").trim();
        if (!body.isEmpty()) {
            sb.append('：').append(clip(body));
        }
        return sb.toString();
    }

    /** Strips the game's colour markers ({@code #g}/{@code #r}/…) so the HUD shows plain text. */
    public static String cleanMarkup(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '#' && i + 1 < text.length()) {
                char next = Character.toLowerCase(text.charAt(i + 1));
                if ("rgbyp".indexOf(next) >= 0 || next == '#' || next == '@') {
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    static String clip(String body) {
        if (body.length() <= BODY_CHARS) {
            return body;
        }
        return body.substring(0, BODY_CHARS) + "…";
    }

    /** Best-effort human name for an id; falls back to the id itself. */
    public static String displayName(String kind, String id) {
        if (id == null || id.isEmpty()) {
            return "?";
        }
        try {
            if ("potion".equals(kind)) {
                com.megacrit.cardcrawl.potions.AbstractPotion potion =
                        com.megacrit.cardcrawl.helpers.PotionHelper.getPotion(id);
                if (potion != null && potion.name != null) {
                    return potion.name;
                }
            } else if ("relic".equals(kind)) {
                com.megacrit.cardcrawl.relics.AbstractRelic relic =
                        com.megacrit.cardcrawl.helpers.RelicLibrary.getRelic(id);
                if (relic != null && relic.name != null) {
                    return relic.name;
                }
            } else if ("monster".equals(kind)) {
                // Monster ids match the MonsterStrings keys; EventStrings-style private nested type,
                // so read NAME through reflection.
                Object pack = com.megacrit.cardcrawl.core.CardCrawlGame.languagePack;
                java.lang.reflect.Method getter =
                        pack.getClass().getMethod("getMonsterStrings", String.class);
                Object strings = getter.invoke(pack, id);
                Object name = spirelink.util.FieldKit.fieldOrNull(strings, "NAME");
                if (name instanceof String && !((String) name).isEmpty()
                        && !"MISSING_NAME".equals(name)) {
                    return (String) name;
                }
            } else if ("card".equals(kind)) {
                com.megacrit.cardcrawl.cards.AbstractCard card =
                        com.megacrit.cardcrawl.helpers.CardLibrary.getCard(id);
                if (card != null && card.name != null) {
                    return card.name;
                }
            } else if ("event".equals(kind)) {
                // The live event knows its own (localized) name; language pack lookups of event ids
                // often resolve to the "MISSING_NAME" placeholder, which is worse than the raw id.
                Object room = com.megacrit.cardcrawl.dungeons.AbstractDungeon.getCurrRoom();
                Object liveEvent = room == null ? null
                        : spirelink.util.FieldKit.fieldOrNull(room, "event");
                Object liveId = liveEvent == null ? null
                        : spirelink.util.FieldKit.fieldOrNull(liveEvent, "eventID");
                if (id.equals(liveId)) {
                    Object liveName = spirelink.util.FieldKit.fieldOrNull(liveEvent, "name");
                    if (liveName instanceof String && !((String) liveName).isEmpty()) {
                        return (String) liveName;
                    }
                }
                // EventStrings is a private nested type, so the getter has to be invoked reflectively.
                Object pack = com.megacrit.cardcrawl.core.CardCrawlGame.languagePack;
                java.lang.reflect.Method getter =
                        pack.getClass().getMethod("getEventString", String.class);
                Object strings = getter.invoke(pack, id);
                Object name = spirelink.util.FieldKit.fieldOrNull(strings, "NAME");
                if (name instanceof String && !((String) name).isEmpty()
                        && !"MISSING_NAME".equals(name)) {
                    return (String) name;
                }
            }
        } catch (Throwable ignored) {
            // fall through to the raw id
        }
        return id;
    }

    /** A: the agent just wrote a note - phrased like a player muttering to themselves. */
    public static void wrote(String kind, String id, String text) {
        String body = cleanMarkup(text == null ? "" : text).replace("\r", " ")
                .replace("\n", " ").trim();
        String name = displayName(kind, id);
        show(clipLine("记下：" + sentence(name, body)), WROTE_MS);
    }

    /** B: the agent just read a note back. */
    public static void read(String kind, String id, String text) {
        String body = cleanMarkup(text == null ? "" : text).replace("\r", " ")
                .replace("\n", " ").trim();
        String name = displayName(kind, id);
        show(clipLine("翻了下笔记：" + sentence(name, body)), READ_MS);
    }

    /** B for {@code notes_list}: count plus the newest entry. */
    public static void readList(int count, String newestLine) {
        if (count <= 0) {
            return;
        }
        String text = newestLine == null || newestLine.isEmpty()
                ? "翻了下笔记：一共 " + count + " 条"
                : clipLine("翻了下笔记：共 " + count + " 条，最近记的是「"
                        + cleanMarkup(newestLine) + "」");
        show(text, READ_MS);
    }

    /** C: entering something the agent already has a note about — shown once per key per minute. */
    public static void seen(String kind, String id, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String key = kind + ":" + id;
        long now = System.currentTimeMillis();
        Long last = SEEN_AT.get(key);
        if (last != null && now - last.longValue() < SEEN_DEDUPE_MS) {
            return;
        }
        SEEN_AT.put(key, Long.valueOf(now));
        show(clipLine("上次记过：" + sentence(displayName(kind, id), cleanMarkup(text))),
                SEEN_MS);
    }

    /** One short human sentence, HUD width is about 28 characters. */
    static String sentence(String name, String body) {
        String cleanBody = body == null ? "" : body.trim();
        if (cleanBody.isEmpty()) {
            return name;
        }
        if ("?".equals(name) || name.isEmpty()) {
            return cleanBody;
        }
        // "大颚虫会加力量，先杀它" reads better than "大颚虫：会加力量，先杀它" for most notes, but the
        // colon stays when the body starts with a full clause of its own.
        return name + (cleanBody.startsWith("会") || cleanBody.startsWith("怕")
                || cleanBody.startsWith("先") ? "" : "：") + cleanBody;
    }

    /**
     * The note block wraps to three rows in the top-right corner, so a note is only cut when it
     * would not fit there at all (three rows of ~26 characters).
     */
    static String clipLine(String text) {
        if (text == null) {
            return "";
        }
        int max = 78;
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static void show(String text, long durationMs) {
        try {
            AgentHud.note(text, durationMs);
        } catch (Throwable ignored) {
            // HUD must never break the caller
        }
    }

}
