package com.osrstranslate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
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
        try (InputStream is = openTranslationStream(owner, path))
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

    static InputStream openTranslationStream(Class<?> owner, String path) throws Exception
    {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        File workspaceFile = new File("src/main/resources", relative);
        if (workspaceFile.exists())
        {
            return new FileInputStream(workspaceFile);
        }
        return owner.getResourceAsStream(path);
    }

    static Map<String, String> parseJsonMap(InputStream is)
    {
        try
        {
            JsonElement element = new JsonParser().parse(new InputStreamReader(is, StandardCharsets.UTF_8));
            JsonObject object = element.getAsJsonObject();
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet())
            {
                result.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
            }
            return result;
        }
        catch (Exception e)
        {
            return Collections.emptyMap();
        }
    }

    static List<PatternEntry> compileRegexTranslations(Map<String, String> source)
    {
        List<PatternEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : source.entrySet())
        {
            if (!entry.getKey().contains("["))
            {
                continue;
            }

            String[] parts = PLACEHOLDER.split(entry.getKey(), -1);
            StringBuilder pattern = new StringBuilder();
            for (int i = 0; i < parts.length; i++)
            {
                pattern.append(Pattern.quote(parts[i]));
                if (i < parts.length - 1)
                {
                    pattern.append("(.+?)");
                }
            }
            entries.add(new PatternEntry(Pattern.compile(pattern.toString()), entry.getValue()));
        }
        return entries;
    }

    static String findTranslation(Map<String, String> source, String lookup)
    {
        String id = textToId(lookup);
        String translation = id == null ? null : source.get(id);
        return translation == null ? source.get(lookup) : translation;
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
            for (int i = 1; i <= matcher.groupCount(); i++)
            {
                result = result.replaceFirst("\\[[^\\]]+\\]", Matcher.quoteReplacement(matcher.group(i)));
            }
            return result;
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

        private PatternEntry(Pattern pattern, String translation)
        {
            this.pattern = pattern;
            this.translation = translation;
        }
    }
}
