package com.osrstranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import org.junit.Test;

public class JournalLineJoinerTest
{
    private static final String KEY = "Have Wizard Cromperty teleport you to the Rune Essence mine.";
    private static final String TRANSLATION =
        "Peça ao Wizard Cromperty para teletransportar você até a mina de Rune Essence.";

    // One width unit per character, so wrapping is easy to follow.
    private static final ToIntFunction<String> CHARS = String::length;

    private static final Map<String, String> DICTIONARY = new HashMap<>();
    private static final Set<String> VALUES = new HashSet<>();

    static
    {
        DICTIONARY.put(KEY, TRANSLATION);
        DICTIONARY.put("Kill a cow.", "Mate uma vaca.");
        VALUES.addAll(DICTIONARY.values());
    }

    private static final JournalLineJoiner.Dictionary LOOKUP = new JournalLineJoiner.Dictionary()
    {
        @Override
        public boolean translatesAlone(String line)
        {
            String clean = TranslationLookupHelper.normalizeLookupText(TranslationLookupHelper.cleanText(line));
            return clean.isEmpty() || VALUES.contains(clean) || DICTIONARY.containsKey(clean);
        }

        @Override
        public String translateJoined(String joined)
        {
            return DICTIONARY.get(TranslationLookupHelper.normalizeLookupText(TranslationLookupHelper.cleanText(joined)));
        }
    };

    private static final List<String> DIARY = Arrays.asList(
        "<col=000000>Kill a cow.",
        "<col=000000>Have Wizard Cromperty teleport you to the Rune Essence",
        "<str>mine.",
        "<col=000000>Untranslated task text",
        ""
    );

    private static IntFunction<String> at(List<String> lines, int index)
    {
        return offset -> {
            int i = index + offset;
            return i < 0 || i >= lines.size() ? null : lines.get(i);
        };
    }

    @Test
    public void joinsAWrappedTaskForwards()
    {
        JournalLineJoiner.Join join = JournalLineJoiner.find(at(DIARY, 1), LOOKUP);
        assertNotNull(join);
        assertEquals(0, join.start);
        assertEquals(DIARY.subList(1, 3), join.lines);
        assertEquals(TRANSLATION, join.translation);
    }

    @Test
    public void joinsAWrappedTaskBackwardsFromItsSecondLine()
    {
        JournalLineJoiner.Join join = JournalLineJoiner.find(at(DIARY, 2), LOOKUP);
        assertNotNull(join);
        assertEquals(-1, join.start);
        assertEquals(TRANSLATION, join.translation);
    }

    @Test
    public void leavesLinesThatTranslateAloneAndLinesThatFormNoKey()
    {
        assertNull("a key by itself", JournalLineJoiner.find(at(DIARY, 0), LOOKUP));
        assertNull("already translated", JournalLineJoiner.find(at(Arrays.asList("Mate uma vaca."), 0), LOOKUP));
        assertNull("no join is a key", JournalLineJoiner.find(at(DIARY, 3), LOOKUP));
    }

    @Test
    public void doesNotReachPastALineThatEndsItsOwnSentence()
    {
        // "Kill a cow." translates alone, so "Kill a cow. Have Wizard..." is never tried.
        List<String> lines = Arrays.asList("Kill a cow.", "Have Wizard Cromperty teleport you to the Rune Essence");
        assertNull(JournalLineJoiner.find(at(lines, 1), LOOKUP));
    }

    @Test
    public void sharesTheTranslationOutKeepingEachLinesTags()
    {
        JournalLineJoiner.Join join = JournalLineJoiner.find(at(DIARY, 1), LOOKUP);
        List<String> display = JournalLineJoiner.shareOut(join, 60, CHARS);
        assertEquals(Arrays.asList(
            "<col=000000>Peça ao Wizard Cromperty para teletransportar você até a",
            "<str>mina de Rune Essence."
        ), display);
    }

    @Test
    public void spareLinesBecomeEmptyAndExtraTextRunsOnInTheLastLine()
    {
        JournalLineJoiner.Join three = new JournalLineJoiner.Join(0, Arrays.asList("<col=ff0000>a", "b", "c"), "um dois");
        assertEquals(Arrays.asList("<col=ff0000>um dois", "", ""), JournalLineJoiner.shareOut(three, 60, CHARS));

        JournalLineJoiner.Join two = new JournalLineJoiner.Join(0, Arrays.asList("a", "b"), "um dois tres quatro");
        assertEquals(Arrays.asList("um", "dois tres quatro"), JournalLineJoiner.shareOut(two, 4, CHARS));
    }

    @Test
    public void aColourOpenAtTheWrapCarriesOnIntoTheNextLine()
    {
        // As the quest journal's translations colour names inline.
        JournalLineJoiner.Join join = new JournalLineJoiner.Join(
            0,
            Arrays.asList("<col=000080>a", "<col=000080>b", "<col=000080>c"),
            "fale com <col=800000>Observatory Professor<col=000080> hoje"
        );
        assertEquals(Arrays.asList(
            "<col=000080>fale com <col=800000>Observatory",
            "<col=000080><col=800000>Professor<col=000080> hoje",
            ""
        ), JournalLineJoiner.shareOut(join, 20, CHARS));
    }

    @Test
    public void wrapMeasuresWithoutTagsAndHonoursLineBreaks()
    {
        assertEquals(Arrays.asList("<col=ff0000>ab cd</col>", "ef"), JournalLineJoiner.wrap("<col=ff0000>ab cd</col> ef", 5, CHARS));
        assertEquals(Arrays.asList("ab", "cd"), JournalLineJoiner.wrap("ab<br>cd", 50, CHARS));
    }
}
