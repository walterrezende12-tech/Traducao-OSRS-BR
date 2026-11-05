package com.osrstranslate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TranslationLookupHelper
{
    private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[^\\]]+\\]");
    private static final Pattern START_TAGS = Pattern.compile("^(<str>|<col=[^>]+>)+");

    private TranslationLookupHelper()
    {
    }

    static Map<String, String> loadMap(Class<?> owner, String path)
    {
        try (InputStream is = owner.getResourceAsStream(path))
        {
            if (is == null)
            {
                return Collections.emptyMap();
            }
            return parseJsonMap(is);
        }
        catch (Exception e)
        {
            return Collections.emptyMap();
        }
    }

    static Map<String, String> parseJsonMap(InputStream is)
    {
        try
        {
            return parseJsonMapStrict(is);
        }
        catch (Exception e)
        {
            return Collections.emptyMap();
        }
    }

    static Map<String, String> parseJsonMapStrict(InputStream is) throws IOException
    {
        try
        {
            JsonElement element = new JsonParser().parse(
                new InputStreamReader(is, StandardCharsets.UTF_8)
            );
            if (element == null || !element.isJsonObject())
            {
                throw new IOException("A raiz do JSON deve ser um objeto");
            }

            JsonObject object = element.getAsJsonObject();
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet())
            {
                JsonElement value = entry.getValue();
                if (value == null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString())
                {
                    throw new IOException("Traducao invalida para a chave: " + entry.getKey());
                }
                result.putIfAbsent(entry.getKey(), value.getAsString());
            }
            return result;
        }
        catch (IOException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new IOException("JSON de traducoes invalido", e);
        }
    }

    static List<PatternEntry> compileRegexTranslations(Map<String, String> source)
    {
        List<PatternEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : source.entrySet())
        {
            String key = entry.getKey();
            String translation = entry.getValue();
            if (key == null || translation == null || !PLACEHOLDER.matcher(key).find())
            {
                continue;
            }

            if (isIdentityTemplate(key, translation))
            {
                continue;
            }

            int specificity = staticTextLength(key);
            if (specificity == 0)
            {
                // A key made only of brackets is an alternative/choice form,
                // not a safe generic pattern.  It would match every text,
                // including NPC names such as "Banker".
                continue;
            }

            String[] parts = PLACEHOLDER.split(key, -1);
            StringBuilder pattern = new StringBuilder();
            for (int i = 0; i < parts.length; i++)
            {
                pattern.append(Pattern.quote(parts[i]));
                if (i < parts.length - 1)
                {
                    pattern.append("(.+?)");
                }
            }
            entries.add(new PatternEntry(
                Pattern.compile(pattern.toString()),
                translation,
                specificity
            ));
        }
        entries.sort((left, right) -> Integer.compare(right.specificity, left.specificity));
        return entries;
    }

    private static boolean isIdentityTemplate(String key, String translation)
    {
        return templateSignature(key).equals(templateSignature(translation));
    }

    private static String templateSignature(String text)
    {
        return PLACEHOLDER.matcher(text).replaceAll(Matcher.quoteReplacement("\u0000"));
    }

    private static int staticTextLength(String text)
    {
        Matcher matcher = PLACEHOLDER.matcher(text);
        int length = 0;
        int end = 0;
        while (matcher.find())
        {
            length += matcher.start() - end;
            end = matcher.end();
        }
        return length + text.length() - end;
    }

    static String findTranslation(Map<String, String> source, String lookup)
    {
        return source.get(lookup);
    }

    static String findTranslation(Map<String, String> source, List<PatternEntry> patterns, String lookup)
    {
        String translation = findTranslation(source, lookup);
        if (translation != null || patterns == null || patterns.isEmpty())
        {
            return translation;
        }
        return findRegexTranslation(patterns, lookup);
    }

    /**
     * Like {@link #findTranslation(Map, List, String)}, but when a [placeholder] key matches,
     * its placeholders are filled from {@code richLookup} (the same text with its inline tags
     * kept, see {@link #richLookupText(String)}) if the key matches that too. A value the game
     * colours, such as the track name in "You have unlocked a new music track:
     * &lt;col=e00a19&gt;Forever", then keeps its colour. When the key does not match the tagged
     * text (a tag inside the key's own words), the plain lookup is used, as before.
     */
    static String findTranslation(
        Map<String, String> source,
        List<PatternEntry> patterns,
        String lookup,
        String richLookup)
    {
        String translation = findTranslation(source, lookup);
        if (translation != null || patterns == null || patterns.isEmpty())
        {
            return translation;
        }

        for (PatternEntry entry : patterns)
        {
            String result = applyPattern(entry, lookup);
            if (result == null)
            {
                continue;
            }
            if (richLookup != null && !richLookup.equals(lookup))
            {
                String rich = applyPattern(entry, richLookup);
                if (rich != null)
                {
                    return rich;
                }
            }
            return result;
        }
        return null;
    }

    /**
     * The translation of a chat message, or null. The lookup is normalised as widget text is: a
     * highlighted word ("Nothing interesting <col=ff0000>happens</col>.") leaves a space before
     * the full stop once its tags become spaces, which the dictionary's key does not have.
     */
    static String findGameMessageTranslation(
        Map<String, String> source,
        Set<String> values,
        List<PatternEntry> patterns,
        String message)
    {
        String clean = normalizeLookupText(cleanText(message));
        if (clean.isEmpty() || values.contains(clean))
        {
            return null;
        }
        return findTranslation(source, patterns, clean, richLookupText(message));
    }

    static String findRegexTranslation(List<PatternEntry> patterns, String lookup)
    {
        for (PatternEntry entry : patterns)
        {
            String result = applyPattern(entry, lookup);
            if (result != null)
            {
                return result;
            }
        }
        return null;
    }

    private static String applyPattern(PatternEntry entry, String lookup)
    {
        Matcher matcher = entry.pattern.matcher(lookup);
        if (!matcher.matches())
        {
            return null;
        }

        String result = entry.translation;
        boolean replacedPlaceholder = false;
        // Search after each inserted value, so a captured "[x]" is not taken for a placeholder.
        int from = 0;
        for (int i = 1; i <= matcher.groupCount(); i++)
        {
            Matcher placeholderMatcher = PLACEHOLDER.matcher(result);
            if (!placeholderMatcher.find(from))
            {
                break;
            }

            String value = matcher.group(i);
            result = result.substring(0, placeholderMatcher.start())
                + value
                + result.substring(placeholderMatcher.end());
            from = placeholderMatcher.start() + value.length();
            replacedPlaceholder = true;
        }
        return replacedPlaceholder && !lookup.equals(result) ? result : null;
    }

    static String cleanText(String text)
    {
        if (text == null)
        {
            return "";
        }

        String clean = BR_TAG.matcher(text).replaceAll(" ");
        clean = HTML_TAG.matcher(clean).replaceAll(" ");
        return clean.replaceAll("\\s+", " ").trim();
    }

    /**
     * The lookup text with its inline tags kept: &lt;br&gt; becomes a space, the leading
     * &lt;col&gt;/&lt;str&gt; tags are dropped (the caller puts them back in front of the
     * translation), whitespace is collapsed and the text is normalised as the lookup is.
     */
    static String richLookupText(String text)
    {
        if (text == null)
        {
            return "";
        }

        String rich = BR_TAG.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").trim();
        rich = START_TAGS.matcher(rich).replaceFirst("").trim();
        return normalizeLookupText(rich);
    }

    /** The leading &lt;str&gt;/&lt;col&gt; tags of {@code text}, or an empty string. */
    static String startTags(String text)
    {
        Matcher matcher = START_TAGS.matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    /**
     * Rich translations use &lt;br&gt; and &lt;/col&gt;. The chat overlay (263) wants new lines,
     * and black where a colour ends, since it is drawn on a light background.
     */
    static String normalizeChatOverlayTags(String text)
    {
        if (text == null || text.isEmpty())
        {
            return text;
        }
        return chatboxLineBreaks(text).replace("</col>", "<col=000000>");
    }

    /** For chatbox lines: &lt;br&gt; becomes a new line and colour tags are left alone. */
    static String chatboxLineBreaks(String text)
    {
        if (text == null || text.isEmpty())
        {
            return text;
        }
        return BR_TAG.matcher(text).replaceAll("\n");
    }

    static String normalizeLookupText(String text)
    {
        return text
            .replaceAll("\\s+([.,!?])", "$1")
            .replaceAll("([Hh])ardcore Ironmen ", "$1ardcore Ironman ")
            .replaceAll("standard Ironmen ", "standard Ironman ");
    }

    static String textToId(String text)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++)
            {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            return null;
        }
    }

    static final class PatternEntry
    {
        private final Pattern pattern;
        private final String translation;
        private final int specificity;

        private PatternEntry(Pattern pattern, String translation, int specificity)
        {
            this.pattern = pattern;
            this.translation = translation;
            this.specificity = specificity;
        }
    }
}
