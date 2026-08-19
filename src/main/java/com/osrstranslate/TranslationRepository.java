package com.osrstranslate;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
final class TranslationRepository {
    private static final String TRANSLATIONS = "translations.json";
    private static final String TRANSLATIONS_SKILLS = "translations_skills.json";
    private static final String TRANSLATIONS_QUESTS = "translations_quests.json";
    private static final String TRANSLATIONS_ITEMS = "translations_items.json";
    private static final String TRANSLATIONS_MENU = "translations_menu.json";
    private static final String TRANSLATIONS_OVERHEAD = "translations_overhead.json";
    private static final String TRANSLATIONS_GAME_MESSAGE = "translations_game_message.json";
    private static final String TRANSLATIONS_WELCOME = "translations_welcome.json";
    private static final String TRANSLATIONS_SETTINGS = "translations_settings.json";

    private volatile File remoteCacheDirectory;

    boolean configureRemoteCacheDirectory(File directory) {
        File previous = remoteCacheDirectory;
        remoteCacheDirectory = directory;
        return previous == null ? directory != null : !previous.equals(directory);
    }

    TranslationState loadState() {
        Map<String, String> translations = loadMap(TRANSLATIONS);
        Map<String, String> translationsSkills = loadMap(TRANSLATIONS_SKILLS);
        Map<String, String> translationsQuests = loadMap(TRANSLATIONS_QUESTS);
        Map<String, String> translationsItems = loadMap(TRANSLATIONS_ITEMS);
        Map<String, String> translationsMenu = loadMap(TRANSLATIONS_MENU);
        Map<String, String> translationsOverhead = loadMap(TRANSLATIONS_OVERHEAD);
        Map<String, String> translationsGameMessage = loadMap(TRANSLATIONS_GAME_MESSAGE);
        Map<String, String> translationsWelcome = loadMap(TRANSLATIONS_WELCOME);
        Map<String, String> translationsSettings = loadMap(TRANSLATIONS_SETTINGS);

        TranslationState state = new TranslationState(
            translations,
            translationsSkills,
            translationsQuests,
            translationsItems,
            translationsMenu,
            translationsOverhead,
            translationsGameMessage,
            translationsWelcome,
            translationsSettings,
            new HashSet<>(translations.values()),
            new HashSet<>(translationsSkills.values()),
            new HashSet<>(translationsQuests.values()),
            new HashSet<>(translationsItems.values()),
            new HashSet<>(translationsMenu.values()),
            new HashSet<>(translationsGameMessage.values()),
            new HashSet<>(translationsWelcome.values()),
            new HashSet<>(translationsSettings.values()),
            TranslationLookupHelper.compileRegexTranslations(translations),
            TranslationLookupHelper.compileRegexTranslations(translationsOverhead),
            TranslationLookupHelper.compileRegexTranslations(translationsGameMessage),
            TranslationLookupHelper.compileRegexTranslations(translationsWelcome),
            buildReverseMenuMap(translationsMenu)
        );

        log.info(
            "PT-BR carregado: dialogos={} skills={} quests={} items={} menu={} "
                + "overhead={} gameMessages={} welcome={} settings={} regex={} "
                + "overheadRegex={} welcomeRegex={}",
            translations.size(),
            translationsSkills.size(),
            translationsQuests.size(),
            translationsItems.size(),
            translationsMenu.size(),
            translationsOverhead.size(),
            translationsGameMessage.size(),
            translationsWelcome.size(),
            translationsSettings.size(),
            state.regexTranslations.size(),
            state.regexOverheadTranslations.size(),
            state.regexWelcomeTranslations.size()
        );
        return state;
    }

    private Map<String, String> loadMap(String fileName) {
        File cacheDirectory = remoteCacheDirectory;
        if (cacheDirectory == null) {
            return Collections.emptyMap();
        }

        File remoteFile = new File(cacheDirectory, fileName);
        if (!remoteFile.isFile()) {
            log.warn("Arquivo ausente no cache remoto: {}", fileName);
            return Collections.emptyMap();
        }

        try (InputStream input = new FileInputStream(remoteFile)) {
            return TranslationLookupHelper.parseJsonMap(input);
        } catch (Exception e) {
            log.warn("Cache remoto invalido para {}", fileName, e);
            return Collections.emptyMap();
        }
    }

    private Map<String, String> buildReverseMenuMap(Map<String, String> source) {
        Map<String, String> reverse = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = stripMenuText(entry.getKey());
            String value = normalizeMenuLookup(entry.getValue());
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            reverse.putIfAbsent(value, key);
        }
        return reverse;
    }

    private String stripMenuText(String text) {
        return text.replaceAll("<[^>]+>", "").trim();
    }

    private String normalizeMenuLookup(String text) {
        return stripMenuText(text).toLowerCase(Locale.ROOT);
    }
}
