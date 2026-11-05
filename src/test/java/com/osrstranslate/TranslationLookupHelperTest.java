package com.osrstranslate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Collections;
import java.util.HashSet;
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

    private static String gameMessage(Map<String, String> source, String message)
    {
        return TranslationLookupHelper.findGameMessageTranslation(
            source,
            new HashSet<>(source.values()),
            TranslationLookupHelper.compileRegexTranslations(source),
            message
        );
    }

    @Test
    public void highlightedChatMessageFindsItsKey()
    {
        Map<String, String> source = Collections.singletonMap("Nothing interesting happens.", "Nada de interessante acontece.");
        assertEquals("Nada de interessante acontece.", gameMessage(source, "Nothing interesting <col=ff0000>happens</col>."));
        assertNull("already translated", gameMessage(source, "Nada de interessante <col=ff0000>acontece</col>."));
    }

    @Test
    public void chatMessagePlaceholderKeepsTheGamesColour()
    {
        assertEquals(
            "Voce desbloqueou uma nova faixa de musica: <col=e00a19>Forever",
            gameMessage(MUSIC, "You have unlocked a new music track: <col=e00a19>Forever")
        );
    }

    @Test
    public void capturedValueThatLooksLikeAPlaceholderIsNotFilledAgain()
    {
        Map<String, String> source = Collections.singletonMap("[a] and [b]", "[a] e [b]");
        assertEquals(
            "x [y] e z",
            TranslationLookupHelper.findRegexTranslation(TranslationLookupHelper.compileRegexTranslations(source), "x [y] and z")
        );
    }

    @Test
    public void placeholderPlusPunctuationIsNotACatchAll()
    {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("[Vampyre name].", "[Vampyre name]");
        source.put("[player]?", "¿[player]?");
        source.put("Hi [player].", "Oi [player].");
        List<TranslationLookupHelper.PatternEntry> patterns = TranslationLookupHelper.compileRegexTranslations(source);
        assertNull(TranslationLookupHelper.findRegexTranslation(patterns, "Welcome to my shop."));
        assertNull(TranslationLookupHelper.findRegexTranslation(patterns, "What is this?"));
        assertEquals("Oi Bob.", TranslationLookupHelper.findRegexTranslation(patterns, "Hi Bob."));
    }

    @Test
    public void chatboxKeepsColourEndsWhileTheOverlayTurnsThemBlack()
    {
        assertEquals("A\nB</col>C", TranslationLookupHelper.chatboxLineBreaks("A<br>B</col>C"));
        assertEquals("A\nB<col=000000>C", TranslationLookupHelper.normalizeChatOverlayTags("A<br>B</col>C"));
    }
}
