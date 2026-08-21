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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TranslationLookupHelper
{
    private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[^\\]]+\\]");

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

    static String findRegexTranslation(List<PatternEntry> patterns, String lookup)
    {
        for (PatternEntry entry : patterns)
        {
            Matcher matcher = entry.pattern.matcher(lookup);
            if (!matcher.matches())
            {
                continue;
            }

            String result = entry.translation;
            boolean replacedPlaceholder = false;
            for (int i = 1; i <= matcher.groupCount(); i++)
            {
                Matcher placeholderMatcher = PLACEHOLDER.matcher(result);
                if (!placeholderMatcher.find())
                {
                    break;
                }

                result = result.substring(0, placeholderMatcher.start())
                    + matcher.group(i)
                    + result.substring(placeholderMatcher.end());
                replacedPlaceholder = true;
            }
            if (replacedPlaceholder && !lookup.equals(result))
            {
                return result;
            }
        }
        return null;
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
