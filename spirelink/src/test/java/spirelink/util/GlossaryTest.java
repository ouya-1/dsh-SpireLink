package spirelink.util;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Marking logic: wrap known terms once, never nest, longest match wins. */
public class GlossaryTest {

    private static Glossary.Entry entry(String term, String... aliases) {
        List<String> all = new ArrayList<String>();
        all.add(term);
        all.addAll(Arrays.asList(aliases));
        return new Glossary.Entry(term, all, "定义", "keyword");
    }

    @Test
    public void marksKnownTermOnce() {
        List<Glossary.Entry> entries = Collections.singletonList(entry("格挡"));
        List<String> keys = Collections.singletonList("格挡");
        Glossary.Marked marked = Glossary.mark(entries, keys, "获得格挡 5 点，格挡不会保留");
        assertEquals("获得【格挡】 5 点，格挡不会保留", marked.text);
        assertEquals(Collections.singletonList("格挡"), marked.keywords);
    }

    @Test
    public void longestTermWins() {
        List<Glossary.Entry> entries = Arrays.asList(entry("易伤"), entry("伤"));
        List<String> keys = Arrays.asList("易伤", "伤");
        Glossary.Marked marked = Glossary.mark(entries, keys, "施加易伤");
        assertEquals("施加【易伤】", marked.text);
        assertEquals(Collections.singletonList("易伤"), marked.keywords);
    }

    @Test
    public void englishAliasMatches() {
        List<Glossary.Entry> entries = Collections.singletonList(entry("格挡", "Block"));
        List<String> keys = Collections.singletonList("block");
        Glossary.Marked marked = Glossary.mark(entries, keys, "gain Block now");
        assertEquals("gain 【格挡】 now", marked.text);
    }

    @Test
    public void plainTextIsUnchanged() {
        Glossary.Marked marked = Glossary.mark(Collections.<Glossary.Entry>emptyList(),
                Collections.<String>emptyList(), "打出一张牌");
        assertEquals("打出一张牌", marked.text);
        assertTrue(marked.keywords.isEmpty());
    }

    @Test
    public void normalizationIgnoredSpacesAndCase() {
        assertEquals("block", Glossary.normalize("Block"));
        assertEquals("darkembrace", Glossary.normalize("dark_embrace"));
        assertEquals("格挡", Glossary.normalize(" 格挡 "));
    }

    @Test
    public void distanceFindsNearMisses() {
        assertTrue(Glossary.suggestions("格挡x").isEmpty()
                || Glossary.suggestions("格挡x").size() <= 3);
    }
}
