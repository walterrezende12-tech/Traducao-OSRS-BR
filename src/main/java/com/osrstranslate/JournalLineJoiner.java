package com.osrstranslate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Journals (the quest journal, the quest scroll, the achievement diary scroll and books) put
 * each line in its own component, wrapped to the scroll's width, while the dictionaries hold
 * whole sentences. A diary task "Have Wizard Cromperty teleport you to the Rune Essence" /
 * "mine." therefore matches no key line by line. This joins a line that has no translation of
 * its own with up to three lines after it, and with up to three before it in case it continues
 * a sentence, and shares the translation of the first join that is a key out over those lines.
 */
final class JournalLineJoiner
{
    /** How many components one joined sentence may span. */
    static final int MAX_JOIN = 4;

    private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern COLOUR_TAG = Pattern.compile("<col=[^>]+>|</col>");

    interface Dictionary
    {
        /** True when the line needs no join: empty, already translated, or a key by itself. */
        boolean translatesAlone(String line);

        /** The translation of several lines joined with spaces, or null. */
        String translateJoined(String joined);
    }

    static final class Join
    {
        /** Offset of the sentence's first line from the line that asked. */
        final int start;
        final List<String> lines;
        final String translation;

        Join(int start, List<String> lines, String translation)
        {
            this.start = start;
            this.lines = lines;
            this.translation = translation;
        }
    }

    private JournalLineJoiner()
    {
    }

    /**
     * The sentence the line at offset 0 belongs to, or null. {@code textAt} gives the text of the
     * line at an offset from it (negative before it), or null where there is no such line.
     */
    static Join find(IntFunction<String> textAt, Dictionary dictionary)
    {
        String own = textAt.apply(0);
        if (own == null || dictionary.translatesAlone(own))
        {
            return null;
        }

        for (int start = 0; start > -MAX_JOIN; start--)
        {
            String first = textAt.apply(start);
            // A line before this one that translates by itself ends its own sentence.
            if (first == null || (start < 0 && dictionary.translatesAlone(first)))
            {
                break;
            }

            List<String> lines = new ArrayList<>();
            for (int offset = start; offset < start + MAX_JOIN; offset++)
            {
                String line = textAt.apply(offset);
                if (line == null || TranslationLookupHelper.cleanText(line).isEmpty())
                {
                    break;
                }
                lines.add(line);
                // A join must reach this line and have at least two lines.
                if (lines.size() < 2 || offset < 0)
                {
                    continue;
                }
                String translation = dictionary.translateJoined(String.join(" ", lines));
                if (translation != null)
                {
                    return new Join(start, Collections.unmodifiableList(new ArrayList<>(lines)), translation);
                }
            }
        }
        return null;
    }

    /**
     * What each of the join's lines shows: the translation wrapped to {@code width}, one part
     * per line, each keeping its line's leading &lt;col&gt;/&lt;str&gt; tags. Spare lines are
     * empty; when the translation needs more lines than there are, the rest runs on in the last.
     */
    static List<String> shareOut(Join join, int width, ToIntFunction<String> measure)
    {
        List<String> parts = wrap(join.translation, width, measure);
        int count = join.lines.size();
        if (parts.size() > count)
        {
            List<String> fitted = new ArrayList<>(parts.subList(0, count - 1));
            fitted.add(String.join(" ", parts.subList(count - 1, parts.size())));
            parts = fitted;
        }

        List<String> display = new ArrayList<>(count);
        // A colour opened in one part and not closed carries on into the next, as it would
        // have had the text been drawn as one wrapped block.
        String openColour = "";
        for (int i = 0; i < count; i++)
        {
            String part = i < parts.size() ? parts.get(i) : "";
            display.add(part.isEmpty() ? "" : TranslationLookupHelper.startTags(join.lines.get(i)) + openColour + part);
            openColour = colourAtEnd(part, openColour);
        }
        return display;
    }

    private static String colourAtEnd(String text, String colour)
    {
        Matcher matcher = COLOUR_TAG.matcher(text);
        while (matcher.find())
        {
            colour = matcher.group().startsWith("</") ? "" : matcher.group();
        }
        return colour;
    }

    /**
     * Word wrap as the client draws it: &lt;br&gt; and new lines start a new line, words are
     * never broken, and the width of a line is measured without its tags.
     */
    static List<String> wrap(String text, int width, ToIntFunction<String> measure)
    {
        List<String> out = new ArrayList<>();
        int maxWidth = Math.max(1, width);
        for (String paragraph : BR_TAG.matcher(text).replaceAll("\n").split("\n", -1))
        {
            if (paragraph.trim().isEmpty())
            {
                out.add("");
                continue;
            }
            String current = "";
            for (String word : paragraph.trim().split("\\s+"))
            {
                if (current.isEmpty())
                {
                    current = word;
                    continue;
                }
                String candidate = current + " " + word;
                if (measure.applyAsInt(HTML_TAG.matcher(candidate).replaceAll("")) <= maxWidth)
                {
                    current = candidate;
                }
                else
                {
                    out.add(current);
                    current = word;
                }
            }
            out.add(current);
        }
        return out;
    }
}
