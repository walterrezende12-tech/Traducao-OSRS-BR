package com.osrstranslate;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TranslationLookupHelperTest
{
    private static final Map<String, String> MUSIC = Collections.singletonMap(
        "You have unlocked a new music track: [track]",
        "Voce desbloqueou uma nova faixa de musica: [track]"
    );

    private static String translate(Map<String, String> source, String text)
    {
        List<TranslationLookupHelper.PatternEntry> patterns = TranslationLookupHelper.compileRegexTranslations(source);
        String lookup = TranslationLookupHelper.normalizeLookupText(TranslationLookupHelper.cleanText(text));
        return TranslationLookupHelper.findTranslation(source, patterns, lookup, TranslationLookupHelper.richLookupText(text));
    }

    @Test
    public void placeholderKeepsTheGamesColour()
    {
        assertEquals(
            "Voce desbloqueou uma nova faixa de musica: <col=e00a19>Forever",
            translate(MUSIC, "You have unlocked a new music track: <col=e00a19>Forever")
        );
    }

    @Test
    public void tagInsideTheKeyFallsBackToThePlainText()
    {
        assertEquals(
            "Voce desbloqueou uma nova faixa de musica: Forever",
            translate(MUSIC, "You have <col=ff0000>unlocked</col> a new music track: Forever")
        );
    }

    @Test
    public void richLookupTextDropsLeadingTagsAndLineBreaks()
    {
        assertEquals("Hi there.", TranslationLookupHelper.richLookupText("<col=ff0000>Hi<br>there ."));
        assertEquals("a <col=ff0000>b</col>", TranslationLookupHelper.richLookupText("<str><col=000000>a  <col=ff0000>b</col>"));
    }

    @Test
    public void exactKeysAreUnchanged()
    {
        Map<String, String> source = new LinkedHashMap<>(MUSIC);
        source.put("Nothing interesting happens.", "Nada de interessante acontece.");
        assertEquals("Nada de interessante acontece.", translate(source, "Nothing interesting happens."));
    }
}
