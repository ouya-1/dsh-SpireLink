package spirelink.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import spirelink.util.Glossary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code glossary}: the in-game encyclopedia for the vocabulary that appears in returned text.
 *
 * <p>Every text field the mod returns marks known terms as 【格挡】/**【易伤】 and carries a machine
 * readable {@code keywords} array; when a term is still unclear the agent asks here instead of
 * guessing from context.
 */
public final class GlossaryTool {

    private GlossaryTool() {
    }

    static JsonObject run(JsonObject arguments) {
        String term = str(arguments, "term");
        JsonArray terms = array(arguments, "terms");
        String query = str(arguments, "query");
        boolean list = arguments != null && arguments.has("list") && !arguments.get("list").isJsonNull()
                && arguments.get("list").getAsBoolean();

        if (term != null && !term.trim().isEmpty()) {
            return one(term);
        }
        if (terms != null && terms.size() > 0) {
            return many(terms);
        }
        if (query != null && !query.trim().isEmpty()) {
            return search(query);
        }
        if (list || (term == null && terms == null && query == null)) {
            return all();
        }
        return all();
    }

    private static JsonObject one(String term) {
        JsonArray results = new JsonArray();
        for (Glossary.Entry entry : Glossary.lookup(term)) {
            JsonObject item = entryJson(entry);
            if (item != null) {
                results.add(item);
            }
        }
        if (results.size() == 0) {
            return notFound(term);
        }
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("query", term);
        out.add("results", results);
        return out;
    }

    private static JsonObject many(JsonArray terms) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        JsonArray results = new JsonArray();
        JsonArray missing = new JsonArray();
        for (int i = 0; i < terms.size(); i++) {
            String term = terms.get(i).isJsonNull() ? null : terms.get(i).getAsString();
            List<Glossary.Entry> hits = Glossary.lookup(term);
            if (hits.isEmpty()) {
                JsonObject item = new JsonObject();
                item.addProperty("query", term);
                item.add("suggestions", suggestionsJson(term));
                missing.add(item);
                continue;
            }
            JsonObject item = entryJson(hits.get(0));
            if (item != null) {
                results.add(item);
            } else {
                // The term exists in the game but carries no usable name: say so instead of silence.
                JsonObject miss = new JsonObject();
                miss.addProperty("query", term);
                miss.addProperty("reason", "NO_LOCALISED_NAME");
                miss.add("suggestions", suggestionsJson(term));
                missing.add(miss);
            }
        }
        out.addProperty("count", results.size());
        out.add("results", results);
        if (missing.size() > 0) {
            out.add("not_found", missing);
        }
        return out;
    }

    private static JsonObject search(String query) {
        List<Glossary.Entry> hits = Glossary.search(query);
        JsonObject out = new JsonObject();
        JsonArray results = new JsonArray();
        for (Glossary.Entry entry : hits) {
            JsonObject item = entryJson(entry);
            if (item != null) {
                results.add(item);
            }
        }
        out.addProperty("ok", results.size() > 0);
        out.addProperty("query", query);
        out.addProperty("count", results.size());
        out.add("results", results);
        if (results.size() == 0) {
            out.addProperty("error", "TERM_NOT_FOUND");
            out.add("suggestions", suggestionsJson(query));
        }
        return out;
    }

    /**
     * {@code glossary{list:true}} / {@code glossary{}}.
     *
     * <p>Issue #61: this used to answer 171 entries - most of them {@code [MISSING_NAME]} with
     * {@code [MISSING_0]} definitions - under the key {@code terms} while every other path and the
     * docs say {@code results}, and without the {@code by_source} the docs promise. Now: entries that
     * have no usable name are dropped ({@link #resolve}), machine ids replace placeholders and say
     * {@code name_resolved:false}, the key is {@code results} everywhere, and {@code by_source} counts
     * what was actually emitted.
     */
    private static JsonObject all() {
        return listPayload(Glossary.entries());
    }

    /**
     * The {@code list:true} body for one entry list.
     *
     * <p>Pure on purpose: the field names ({@code results}, not the pre-#61 {@code terms}),
     * {@code count} and {@code by_source} are the contract with the docs, and the placeholder
     * filtering is what the unit test pins - no game objects, no localisation needed.
     */
    static JsonObject listPayload(List<Glossary.Entry> entries) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        JsonArray results = new JsonArray();
        Map<String, Integer> bySource = new LinkedHashMap<String, Integer>();
        if (entries != null) {
            for (Glossary.Entry entry : entries) {
                JsonObject item = entryJson(entry);
                if (item == null) {
                    continue;
                }
                results.add(item);
                bySource.merge(entry.source, 1, Integer::sum);
            }
        }
        out.addProperty("count", results.size());
        out.add("results", results);
        out.add("by_source", bySourceJson(bySource));
        return out;
    }

    /** {@code by_source:{"keyword":12,"power":3}} - only sources that actually produced an entry. */
    private static JsonObject bySourceJson(Map<String, Integer> bySource) {
        JsonObject counts = new JsonObject();
        for (Map.Entry<String, Integer> item : bySource.entrySet()) {
            counts.addProperty(item.getKey(), item.getValue().intValue());
        }
        return counts;
    }

    private static JsonObject notFound(String term) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", "TERM_NOT_FOUND");
        out.addProperty("query", term);
        out.add("suggestions", suggestionsJson(term));
        out.addProperty("hint", "用 glossary{query:\"前缀\"} 模糊搜索，或用 glossary{list:true} 看全部词条");
        return out;
    }

    private static JsonArray suggestionsJson(String term) {
        JsonArray out = new JsonArray();
        for (String suggestion : Glossary.suggestions(term)) {
            out.add(suggestion);
        }
        return out;
    }

    /**
     * One entry as it is emitted, or {@code null} when the game gave us no usable name at all.
     *
     * <p>Issue #61: {@code [MISSING_NAME]} / {@code [MISSING_0]} are the localisation's "I have no
     * strings for this" markers, not names. An entry whose display name is such a marker falls back
     * to its machine id (e.g. {@code AccuracyPower}) and says {@code name_resolved:false}; an entry
     * with no id either is dropped instead of being sent as dirty data.
     */
    private static JsonObject entryJson(Glossary.Entry entry) {
        if (entry == null) {
            return null;
        }
        Resolved resolved = resolve(entry.term, entry.aliases, entry.definition);
        if (resolved == null) {
            return null;
        }
        JsonObject item = new JsonObject();
        item.addProperty("term", resolved.term);
        JsonArray aliases = new JsonArray();
        for (String alias : resolved.aliases) {
            aliases.add(alias);
        }
        item.add("aliases", aliases);
        item.addProperty("definition", resolved.definition);
        item.addProperty("source", entry.source);
        if (!resolved.nameResolved) {
            item.addProperty("name_resolved", false);
        }
        return item;
    }

    /** One glossary entry after placeholder cleanup. */
    static final class Resolved {
        final String term;
        final boolean nameResolved;
        final String definition;
        final List<String> aliases;

        Resolved(String term, boolean nameResolved, String definition, List<String> aliases) {
            this.term = term;
            this.nameResolved = nameResolved;
            this.definition = definition;
            this.aliases = aliases;
        }
    }

    /**
     * True for "the game has no localised name here" markers: {@code [MISSING_NAME]},
     * {@code [MISSING_0]}, {@code MISSING_DESCRIPTION}, and for null/blank text.
     *
     * <p>Deliberately narrow: only the {@code MISSING_} marker shape counts, so a real name such as
     * {@code MissingNo} or a Chinese name containing 缺失 is never swallowed.
     */
    static boolean isPlaceholderName(String text) {
        if (text == null) {
            return true;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        String core = trimmed;
        if (core.startsWith("[")) {
            core = core.substring(1);
        }
        if (core.endsWith("]")) {
            core = core.substring(0, core.length() - 1);
        }
        return core.toUpperCase(java.util.Locale.ROOT).startsWith("MISSING_");
    }

    /**
     * Cleans one entry: real name kept, placeholder name replaced by the machine id, placeholder
     * definition dropped, placeholder aliases removed.
     *
     * @return {@code null} when not even the id is usable (the entry must not be emitted)
     */
    static Resolved resolve(String term, List<String> aliases, String definition) {
        boolean nameResolved = !isPlaceholderName(term);
        String display = term;
        if (!nameResolved) {
            display = firstRealAlias(term, aliases);
            if (display == null) {
                return null;
            }
        }
        List<String> kept = new ArrayList<String>();
        kept.add(display);
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !isPlaceholderName(alias) && !kept.contains(alias)) {
                    kept.add(alias);
                }
            }
        }
        String text = isPlaceholderName(definition) ? "" : definition.trim();
        return new Resolved(display, nameResolved, text, kept);
    }

    /** First alias that is a real name (never the placeholder we are replacing). */
    private static String firstRealAlias(String placeholder, List<String> aliases) {
        if (aliases == null) {
            return null;
        }
        for (String alias : aliases) {
            if (alias != null && !isPlaceholderName(alias)
                    && (placeholder == null || !alias.equals(placeholder))) {
                return alias;
            }
        }
        return null;
    }

    private static String str(JsonObject arguments, String name) {
        if (arguments == null || !arguments.has(name) || arguments.get(name).isJsonNull()) {
            return null;
        }
        try {
            return arguments.get(name).getAsString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonArray array(JsonObject arguments, String name) {
        if (arguments == null || !arguments.has(name) || arguments.get(name).isJsonNull()) {
            return null;
        }
        try {
            return arguments.getAsJsonArray(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static JsonObject definition() {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", ToolCatalog.TOOL_GLOSSARY);
        tool.addProperty("description",
                "百科全书：查询游戏专有名词（格挡/力量/易伤/中毒/固有/消耗…）。"
                        + "返回文本里的术语会被标成【格挡】并带 keywords 数组；"
                        + "对某个术语拿不准时用它查定义。");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();

        JsonObject list = new JsonObject();
        list.addProperty("type", "boolean");
        list.addProperty("description", "true 时列出全部词条");
        properties.add("list", list);

        JsonObject term = new JsonObject();
        term.addProperty("type", "string");
        term.addProperty("description", "查一个术语（中文名/英文名/id 都认，如 格挡 / block / BLOCK）");
        properties.add("term", term);

        JsonObject terms = new JsonObject();
        terms.addProperty("type", "array");
        terms.addProperty("description", "批量查询");
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        terms.add("items", items);
        properties.add("terms", terms);

        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "模糊搜索（前缀或包含）");
        properties.add("query", query);

        schema.add("properties", properties);
        tool.add("inputSchema", schema);
        return tool;
    }

    /** Never used at runtime; keeps the import list honest for tests. */
    static List<String> emptyList() {
        return new ArrayList<String>();
    }
}
