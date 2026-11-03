package spirelink.util;

import com.google.gson.JsonObject;
import com.megacrit.cardcrawl.helpers.GameDictionary;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The game's vocabulary, and marking of that vocabulary inside returned text.
 *
 * <p>A model reading "获得格挡 5 点" has to guess what 格挡 means; the same text as
 * "获得【格挡】5 点" plus {@code keywords:["格挡"]} is unambiguous, and {@code glossary} answers the
 * definition when it is still unclear.
 *
 * <p>Terms come from two game-side sources: {@link GameDictionary}'s Keyword fields (格挡/易伤/固有/…)
 * and the localised power table (力量/敏捷/中毒/…). Both are read lazily on the main thread and cached.
 */
public final class Glossary {

    /** One vocabulary entry. */
    public static final class Entry {
        public final String term;
        public final List<String> aliases;
        public final String definition;
        /** {@code keyword} or {@code power}. */
        public final String source;

        Entry(String term, List<String> aliases, String definition, String source) {
            this.term = term;
            this.aliases = aliases;
            this.definition = definition;
            this.source = source;
        }
    }

    private static final Object LOCK = new Object();
    private static volatile List<Entry> entries;
    private static volatile Map<String, Entry> index;
    /** Terms by length, longest first: matched first so 易伤 wins over 伤. */
    private static volatile List<String> keysByLength;

    private Glossary() {
    }

    /** All known terms, sorted by term. */
    public static List<Entry> entries() {
        ensureLoaded();
        return entries;
    }

    public static List<Entry> lookup(String termOrAlias) {
        ensureLoaded();
        if (termOrAlias == null) {
            return Collections.emptyList();
        }
        Entry exact = index.get(normalize(termOrAlias));
        if (exact != null) {
            return Collections.singletonList(exact);
        }
        List<Entry> out = new ArrayList<Entry>();
        String needle = normalize(termOrAlias);
        for (Entry entry : entries) {
            for (String alias : entry.aliases) {
                String key = normalize(alias);
                if (key.equals(needle)) {
                    out.add(entry);
                    break;
                }
            }
        }
        return out;
    }

    /** Prefix / contains search over names, aliases and ids. */
    public static List<Entry> search(String query) {
        ensureLoaded();
        String needle = normalize(query);
        if (needle.isEmpty()) {
            return Collections.emptyList();
        }
        List<Entry> contains = new ArrayList<Entry>();
        List<Entry> starts = new ArrayList<Entry>();
        for (Entry entry : entries) {
            boolean hit = false;
            boolean prefix = false;
            for (String alias : entry.aliases) {
                String key = normalize(alias);
                if (key.startsWith(needle)) {
                    hit = true;
                    prefix = true;
                    break;
                }
                if (key.contains(needle)) {
                    hit = true;
                }
            }
            if (hit) {
                (prefix ? starts : contains).add(entry);
            }
        }
        List<Entry> out = new ArrayList<Entry>(starts);
        out.addAll(contains);
        return out;
    }

    /** Closest three terms by edit distance, for a helpful TERM_NOT_FOUND. */
    public static List<String> suggestions(String term) {
        ensureLoaded();
        String needle = normalize(term == null ? "" : term);
        final Map<String, Integer> scored = new HashMap<String, Integer>();
        for (Entry entry : entries) {
            int best = Integer.MAX_VALUE;
            for (String alias : entry.aliases) {
                best = Math.min(best, distance(needle, normalize(alias)));
            }
            scored.put(entry.term, Integer.valueOf(best));
        }
        List<String> terms = new ArrayList<String>(scored.keySet());
        Collections.sort(terms, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                int byScore = scored.get(a).compareTo(scored.get(b));
                return byScore != 0 ? byScore : a.compareTo(b);
            }
        });
        List<String> out = new ArrayList<String>();
        for (String candidate : terms) {
            if (out.size() >= 3) {
                break;
            }
            if (scored.get(candidate).intValue() <= Math.max(2, needle.length() / 2)) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** Result of marking one piece of text. */
    public static final class Marked {
        public final String text;
        public final List<String> keywords;

        Marked(String text, List<String> keywords) {
            this.text = text;
            this.keywords = keywords;
        }
    }

    /** Wraps known terms in 【】 and returns the terms that were marked, in text order. */
    public static Marked mark(String text) {
        ensureLoaded();
        return mark(entries(), keysByLength, text);
    }

    /** Adds {@code field} (marked) and, when something was marked, {@code keywords} next to it. */
    public static void addMarked(JsonObject out, String field, String raw) {
        Marked marked = mark(raw);
        out.addProperty(field, marked.text);
        if (!marked.keywords.isEmpty()) {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            for (String keyword : marked.keywords) {
                array.add(keyword);
            }
            out.add("keywords", array);
        }
    }

    // ---------- pure helpers (unit tested without the game) ----------

    static Marked mark(List<Entry> source, List<String> keys, String text) {
        if (text == null || text.isEmpty() || keys == null || keys.isEmpty()) {
            return new Marked(text == null ? "" : text, Collections.<String>emptyList());
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        Set<String> markedTerms = new LinkedHashSet<String>();
        int i = 0;
        while (i < text.length()) {
            String key = matchAt(text, i, keys);
            if (key != null) {
                Entry entry = find(source, key);
                if (entry != null && !markedTerms.contains(entry.term)) {
                    out.append('【').append(entry.term).append('】');
                    markedTerms.add(entry.term);
                    i += key.length();
                    continue;
                }
            }
            out.append(text.charAt(i));
            i++;
        }
        return new Marked(out.toString(), new ArrayList<String>(markedTerms));
    }

    private static Entry find(List<Entry> source, String key) {
        String needle = normalize(key);
        for (Entry entry : source) {
            for (String alias : entry.aliases) {
                if (normalize(alias).equals(needle)) {
                    return entry;
                }
            }
            if (normalize(entry.term).equals(needle)) {
                return entry;
            }
        }
        return null;
    }

    /** Longest key that matches {@code text} at {@code index} (case-insensitive for latin). */
    private static String matchAt(String text, int index, List<String> keys) {
        for (String key : keys) {
            if (key.length() > text.length() - index) {
                continue;
            }
            String slice = text.substring(index, index + key.length());
            if (normalize(slice).equals(key)) {
                return slice;
            }
        }
        return null;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    // ---------- loading ----------

    private static void ensureLoaded() {
        if (entries != null) {
            return;
        }
        synchronized (LOCK) {
            if (entries != null) {
                return;
            }
            List<Entry> loaded = new ArrayList<Entry>();
            loadKeywords(loaded);
            loadPowers(loaded);
            Collections.sort(loaded, new java.util.Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    return a.term.compareTo(b.term);
                }
            });
            Map<String, Entry> map = new HashMap<String, Entry>();
            List<String> keys = new ArrayList<String>();
            for (Entry entry : loaded) {
                for (String alias : entry.aliases) {
                    String key = normalize(alias);
                    if (!key.isEmpty() && !map.containsKey(key)) {
                        map.put(key, entry);
                        keys.add(key);
                    }
                }
            }
            Collections.sort(keys, new java.util.Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return b.length() - a.length();
                }
            });
            keysByLength = keys;
            index = map;
            entries = loaded;
        }
    }

    private static void loadKeywords(List<Entry> out) {
        try {
            for (Field field : GameDictionary.class.getDeclaredFields()) {
                if (field.getType() != com.megacrit.cardcrawl.localization.Keyword.class) {
                    continue;
                }
                Object value = field.get(null);
                if (!(value instanceof com.megacrit.cardcrawl.localization.Keyword)) {
                    continue;
                }
                com.megacrit.cardcrawl.localization.Keyword keyword =
                        (com.megacrit.cardcrawl.localization.Keyword) value;
                if (keyword.NAMES == null || keyword.NAMES.length == 0 || keyword.NAMES[0] == null) {
                    continue;
                }
                List<String> aliases = new ArrayList<String>();
                for (String name : keyword.NAMES) {
                    if (name != null && !name.trim().isEmpty()) {
                        aliases.add(name.trim());
                    }
                }
                // The constant name doubles as the machine id ("BLOCK" -> block).
                aliases.add(field.getName());
                out.add(new Entry(keyword.NAMES[0], aliases,
                        TextClean.stripGameMarkup(keyword.DESCRIPTION), "keyword"));
            }
        } catch (Throwable t) {
            Diag.warn("glossary: 读取 Keyword 失败: " + t);
        }
    }

    private static void loadPowers(List<Entry> out) {
        try {
            java.util.TreeSet<String> names = powerClassNames();
            for (String name : names) {
                com.megacrit.cardcrawl.localization.PowerStrings strings =
                        com.megacrit.cardcrawl.core.CardCrawlGame.languagePack.getPowerStrings(name);
                if (strings == null || strings.NAME == null || strings.NAME.trim().isEmpty()
                        || strings.DESCRIPTIONS == null || strings.DESCRIPTIONS.length == 0) {
                    continue;
                }
                List<String> aliases = new ArrayList<String>();
                aliases.add(strings.NAME.trim());
                aliases.add(name);
                out.add(new Entry(strings.NAME.trim(), aliases,
                        TextClean.stripGameMarkup(strings.DESCRIPTIONS[0]), "power"));
            }
        } catch (Throwable t) {
            Diag.warn("glossary: 读取 Power 词条失败: " + t);
        }
    }

    /** Class names under {@code com.megacrit.cardcrawl.powers} - the power ids are the simple names. */
    private static java.util.TreeSet<String> powerClassNames() throws Exception {
        java.util.TreeSet<String> names = new java.util.TreeSet<String>();
        java.net.URL url = com.megacrit.cardcrawl.powers.AbstractPower.class
                .getResource("/com/megacrit/cardcrawl/powers/AbstractPower.class");
        if (url == null) {
            return names;
        }
        java.net.URLConnection connection = url.openConnection();
        if (!(connection instanceof java.net.JarURLConnection)) {
            return names;
        }
        java.util.jar.JarFile jar = ((java.net.JarURLConnection) connection).getJarFile();
        String prefix = "com/megacrit/cardcrawl/powers/";
        java.util.Enumeration<java.util.jar.JarEntry> items = jar.entries();
        while (items.hasMoreElements()) {
            String entryName = items.nextElement().getName();
            if (!entryName.startsWith(prefix) || !entryName.endsWith(".class")) {
                continue;
            }
            String simple = entryName.substring(prefix.length(), entryName.length() - 6);
            if (simple.indexOf('/') >= 0 || simple.indexOf('$') >= 0) {
                continue;
            }
            names.add(simple);
        }
        return names;
    }
}
