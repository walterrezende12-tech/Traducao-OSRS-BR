package com.osrstranslate;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WelcomeTranslationService {
    private static final int WELCOME_SCREEN = 378;
    private static final String LAST_LOGIN_TEMPLATE = "You last logged in [value] ago.";
    private static final String HANS_TEMPLATE_KEY = "__hans.template";
    private static final Pattern WELCOME_LAST_LOGIN_PATTERN =
        Pattern.compile("^You last logged in (.+) ago\\.$");
    private static final Pattern ENGLISH_DURATION_UNIT_PATTERN =
        Pattern.compile("\\b(\\d[\\d,]*)\\s+(day|days|hour|hours|minute|minutes|second|seconds)\\b");
    private static final Pattern ENGLISH_SINGLE_DURATION_UNIT_PATTERN =
        Pattern.compile("\\b(a|an)\\s+(day|hour|minute|second)\\b");
    private static final Pattern HANS_PATTERN = Pattern.compile(
        "^You've spent (\\d[\\d,]*) (day|days), (\\d[\\d,]*) (hour|hours), "
            + "(\\d[\\d,]*) (minute|minutes) in the world since you arrived "
            + "(\\d[\\d,]*) (day|days) ago\\.$"
    );
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d[\\d,]*)");

    String translateSpecialText(
        String lookup,
        int interfaceId,
        Set<Integer> loginInterfaceGroups,
        Map<String, String> translations
    ) {
        if (isHansTimePlayedMessage(lookup)) {
            return translateHans(lookup, translations);
        }

        if (interfaceId != WELCOME_SCREEN && !loginInterfaceGroups.contains(interfaceId)) {
            return null;
        }

        Matcher matcher = WELCOME_LAST_LOGIN_PATTERN.matcher(lookup);
        if (!matcher.matches()) {
            return null;
        }

        String template = translations.get(LAST_LOGIN_TEMPLATE);
        if (template == null || template.isEmpty()) {
            return null;
        }

        return template.replace("[value]", translateEnglishDuration(matcher.group(1), translations));
    }

    private boolean isHansTimePlayedMessage(String lookup) {
        return lookup.startsWith("You've spent")
            && lookup.contains("in the world since you arrived")
            && lookup.endsWith("ago.");
    }

    private String translateHans(String clean, Map<String, String> translations) {
        String template = translations.get(HANS_TEMPLATE_KEY);
        if (template == null || template.isEmpty()) {
            return null;
        }

        Matcher matcher = HANS_PATTERN.matcher(clean);
        String daysInWorld;
        String daysInWorldUnit;
        String hoursInWorld;
        String hoursInWorldUnit;
        String minutesInWorld;
        String minutesInWorldUnit;
        String daysSinceArrival;
        String daysSinceArrivalUnit;
        if (matcher.matches()) {
            daysInWorld = matcher.group(1);
            daysInWorldUnit = translateDurationUnit(matcher.group(2), translations);
            hoursInWorld = matcher.group(3);
            hoursInWorldUnit = translateDurationUnit(matcher.group(4), translations);
            minutesInWorld = matcher.group(5);
            minutesInWorldUnit = translateDurationUnit(matcher.group(6), translations);
            daysSinceArrival = matcher.group(7);
            daysSinceArrivalUnit = translateDurationUnit(matcher.group(8), translations);
        } else {
            Matcher fallback = NUMBER_PATTERN.matcher(clean);
            daysInWorld = findNext(fallback);
            hoursInWorld = findNext(fallback);
            minutesInWorld = findNext(fallback);
            daysSinceArrival = findNext(fallback);
            daysInWorldUnit = translateDurationUnit("days", translations);
            hoursInWorldUnit = translateDurationUnit("hours", translations);
            minutesInWorldUnit = translateDurationUnit("minutes", translations);
            daysSinceArrivalUnit = translateDurationUnit("days", translations);
        }

        return template
            .replace("[daysInWorld]", daysInWorld)
            .replace("[daysUnit]", daysInWorldUnit)
            .replace("[hoursInWorld]", hoursInWorld)
            .replace("[hoursUnit]", hoursInWorldUnit)
            .replace("[minutesInWorld]", minutesInWorld)
            .replace("[minutesUnit]", minutesInWorldUnit)
            .replace("[daysSinceArrival]", daysSinceArrival)
            .replace("[daysSinceArrivalUnit]", daysSinceArrivalUnit);
    }

    private String findNext(Matcher matcher) {
        return matcher.find() ? matcher.group(1) : "";
    }

    private String translateEnglishDuration(String text, Map<String, String> translations) {
        Matcher matcher = ENGLISH_DURATION_UNIT_PATTERN.matcher(text);
        StringBuffer translated = new StringBuffer();
        boolean found = false;

        while (matcher.find()) {
            found = true;
            String amount = matcher.group(1);
            String unit = matcher.group(2);
            matcher.appendReplacement(
                translated,
                Matcher.quoteReplacement(amount + " " + translateDurationUnit(unit, translations))
            );
        }

        if (!found) {
            Matcher singleMatcher = ENGLISH_SINGLE_DURATION_UNIT_PATTERN.matcher(text);
            StringBuffer singleTranslated = new StringBuffer();
            boolean singleFound = false;

            while (singleMatcher.find()) {
                singleFound = true;
                String unit = singleMatcher.group(2);
                singleMatcher.appendReplacement(
                    singleTranslated,
                    Matcher.quoteReplacement("1 " + translateDurationUnit(unit, translations))
                );
            }

            if (!singleFound) {
                return text;
            }

            singleMatcher.appendTail(singleTranslated);
            return singleTranslated.toString();
        }

        matcher.appendTail(translated);
        return translated.toString();
    }

    private String translateDurationUnit(String unit, Map<String, String> translations) {
        String translated = translations.get("__duration." + unit);
        return translated == null || translated.isEmpty() ? unit : translated;
    }
}
