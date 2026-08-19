package com.osrstranslate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TranslationState {
    final Map<String, String> translations;
    final Map<String, String> translationsSkills;
    final Map<String, String> translationsQuests;
    final Map<String, String> translationsItems;
    final Map<String, String> translationsMenu;
    final Map<String, String> translationsOverhead;
    final Map<String, String> translationsGameMessage;
    final Map<String, String> translationsWelcome;
    final Map<String, String> translationsSettings;
    final Set<String> translationValues;
    final Set<String> translationSkillsValues;
    final Set<String> translationQuestsValues;
    final Set<String> translationItemsValues;
    final Set<String> translationMenuValues;
    final Set<String> translationGameMessageValues;
    final Set<String> translationWelcomeValues;
    final Set<String> translationSettingsValues;
    final List<TranslationLookupHelper.PatternEntry> regexTranslations;
    final List<TranslationLookupHelper.PatternEntry> regexOverheadTranslations;
    final List<TranslationLookupHelper.PatternEntry> regexGameMessageTranslations;
    final List<TranslationLookupHelper.PatternEntry> regexWelcomeTranslations;
    final Map<String, String> reverseTranslationsMenu;

    TranslationState(
        Map<String, String> translations,
        Map<String, String> translationsSkills,
        Map<String, String> translationsQuests,
        Map<String, String> translationsItems,
        Map<String, String> translationsMenu,
        Map<String, String> translationsOverhead,
        Map<String, String> translationsGameMessage,
        Map<String, String> translationsWelcome,
        Map<String, String> translationsSettings,
        Set<String> translationValues,
        Set<String> translationSkillsValues,
        Set<String> translationQuestsValues,
        Set<String> translationItemsValues,
        Set<String> translationMenuValues,
        Set<String> translationGameMessageValues,
        Set<String> translationWelcomeValues,
        Set<String> translationSettingsValues,
        List<TranslationLookupHelper.PatternEntry> regexTranslations,
        List<TranslationLookupHelper.PatternEntry> regexOverheadTranslations,
        List<TranslationLookupHelper.PatternEntry> regexGameMessageTranslations,
        List<TranslationLookupHelper.PatternEntry> regexWelcomeTranslations,
        Map<String, String> reverseTranslationsMenu
    ) {
        this.translations = translations;
        this.translationsSkills = translationsSkills;
        this.translationsQuests = translationsQuests;
        this.translationsItems = translationsItems;
        this.translationsMenu = translationsMenu;
        this.translationsOverhead = translationsOverhead;
        this.translationsGameMessage = translationsGameMessage;
        this.translationsWelcome = translationsWelcome;
        this.translationsSettings = translationsSettings;
        this.translationValues = translationValues;
        this.translationSkillsValues = translationSkillsValues;
        this.translationQuestsValues = translationQuestsValues;
        this.translationItemsValues = translationItemsValues;
        this.translationMenuValues = translationMenuValues;
        this.translationGameMessageValues = translationGameMessageValues;
        this.translationWelcomeValues = translationWelcomeValues;
        this.translationSettingsValues = translationSettingsValues;
        this.regexTranslations = regexTranslations;
        this.regexOverheadTranslations = regexOverheadTranslations;
        this.regexGameMessageTranslations = regexGameMessageTranslations;
        this.regexWelcomeTranslations = regexWelcomeTranslations;
        this.reverseTranslationsMenu = reverseTranslationsMenu;
    }

    static TranslationState empty() {
        return new TranslationState(
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptySet(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyMap()
        );
    }
}
