package com.osrstranslate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.NPC;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
    name = "OSRS Translate PT-BR",
    description = "Traduz dialogos do OSRS para Portugues Brasileiro",
    tags = {"translate", "portuguese", "ptbr"}
)
public class OsrsTranslatePlugin extends Plugin {
    private static final int DIALOG_MESSAGE = 229;
    private static final int ITEM_PREVIEW = 203;
    private static final int SKILL_GUIDE = 214;
    private static final int BOOKS_NOTES = 680;
    private static final int QUEST_JOURNAL_MINIMAP = 782;
    private static final int QUEST_JOURNAL = 119;

    private static final int[] DIALOG_INTERFACES = {
        InterfaceID.DIALOG_NPC,
        InterfaceID.DIALOG_PLAYER,
        InterfaceID.DIALOG_OPTION,
        InterfaceID.DIALOG_SPRITE,
    };

    private static final int[] MAIN_TEXT_INTERFACES = {
        InterfaceID.LEVEL_UP,
        DIALOG_MESSAGE,
    };

    private static final int[] QUEST_INTERFACES = {
        QUEST_JOURNAL_MINIMAP,
        QUEST_JOURNAL,
    };

    private static final int[] ITEM_INTERFACES = {
        ITEM_PREVIEW,
        BOOKS_NOTES,
    };

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[^\\]]+\\]");
    private static final Pattern START_TAGS = Pattern.compile("^(<str>|<col=[^>]+>)+");
    private static final Pattern HANS_PATTERN = Pattern.compile(
        "^You've spent (\\d[\\d,]*) day(?:s)?, (\\d[\\d,]*) hour(?:s)?, "
            + "(\\d[\\d,]*) minute(?:s)? in the world since you arrived "
            + "(\\d[\\d,]*) day(?:s)? ago\\.$"
    );
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d[\\d,]*)");

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private OsrsTranslateConfig config;

    private volatile Map<String, String> translations = Collections.emptyMap();
    private volatile Map<String, String> translationsSkills = Collections.emptyMap();
    private volatile Map<String, String> translationsQuests = Collections.emptyMap();
    private volatile Map<String, String> translationsItems = Collections.emptyMap();
    private volatile Map<String, String> translationsMenu = Collections.emptyMap();
    private volatile Map<String, String> translationsOverhead = Collections.emptyMap();
    private volatile Map<String, String> translationsGameMessage = Collections.emptyMap();

    private volatile Set<String> translationValues = Collections.emptySet();
    private volatile Set<String> translationSkillsValues = Collections.emptySet();
    private volatile Set<String> translationQuestsValues = Collections.emptySet();
    private volatile Set<String> translationItemsValues = Collections.emptySet();
    private volatile Set<String> translationGameMessageValues = Collections.emptySet();
    private volatile List<PatternEntry> regexTranslations = Collections.emptyList();

    private boolean skillGuideOpen;
    private boolean skillGuideNeedsTranslation;

    private static class PatternEntry {
        private final Pattern pattern;
        private final String translation;

        private PatternEntry(Pattern pattern, String translation) {
            this.pattern = pattern;
            this.translation = translation;
        }
    }

    private static class TranslationDomain {
        private final Map<String, String> translations;
        private final Set<String> values;
        private final boolean useRegexFallback;

        private TranslationDomain(Map<String, String> translations, Set<String> values, boolean useRegexFallback) {
            this.translations = translations;
            this.values = values;
            this.useRegexFallback = useRegexFallback;
        }
    }

    @Override
    protected void startUp() {
        loadTranslations();
    }

    @Override
    protected void shutDown() {
        translations = Collections.emptyMap();
        translationsSkills = Collections.emptyMap();
        translationsQuests = Collections.emptyMap();
        translationsItems = Collections.emptyMap();
        translationsMenu = Collections.emptyMap();
        translationsOverhead = Collections.emptyMap();
        translationsGameMessage = Collections.emptyMap();
        translationValues = Collections.emptySet();
        translationSkillsValues = Collections.emptySet();
        translationQuestsValues = Collections.emptySet();
        translationItemsValues = Collections.emptySet();
        translationGameMessageValues = Collections.emptySet();
        regexTranslations = Collections.emptyList();
        skillGuideOpen = false;
        skillGuideNeedsTranslation = false;
    }

    private void loadTranslations() {
        Map<String, String> newTranslations = loadMap("/com/osrstranslate/translations.json");
        Map<String, String> newSkills = loadMap("/com/osrstranslate/translations_skills.json");
        Map<String, String> newQuests = loadMap("/com/osrstranslate/translations_quests.json");
        Map<String, String> newItems = loadMap("/com/osrstranslate/translations_items.json");
        Map<String, String> newMenu = loadMap("/com/osrstranslate/translations_menu.json");
        Map<String, String> newOverhead = loadMap("/com/osrstranslate/translations_overhead.json");
        Map<String, String> newGameMessage = loadMap("/com/osrstranslate/translations_game_message.json");

        translations = newTranslations;
        translationsSkills = newSkills;
        translationsQuests = newQuests;
        translationsItems = newItems;
        translationsMenu = newMenu;
        translationsOverhead = newOverhead;
        translationsGameMessage = newGameMessage;
        translationValues = new HashSet<>(newTranslations.values());
        translationSkillsValues = new HashSet<>(newSkills.values());
        translationQuestsValues = new HashSet<>(newQuests.values());
        translationItemsValues = new HashSet<>(newItems.values());
        translationGameMessageValues = new HashSet<>(newGameMessage.values());
        regexTranslations = compileRegexTranslations(newTranslations);

        log.info(
            "PT-BR carregado: dialogos={} skills={} quests={} items={} menu={} overhead={} gameMessages={} regex={}",
            newTranslations.size(), newSkills.size(), newQuests.size(), newItems.size(), newMenu.size(),
            newOverhead.size(), newGameMessage.size(), regexTranslations.size()
        );
    }

    private Map<String, String> loadMap(String path) {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                log.warn("Arquivo de traducao nao encontrado: {}", path);
                return Collections.emptyMap();
            }
            return parseJsonMap(is);
        } catch (Exception e) {
            log.error("Erro ao carregar {}", path, e);
            return Collections.emptyMap();
        }
    }

    private Map<String, String> parseJsonMap(InputStream is) {
        try {
            JsonElement element = new JsonParser().parse(new InputStreamReader(is, StandardCharsets.UTF_8));
            JsonObject object = element.getAsJsonObject();
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                result.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
            }
            return result;
        } catch (Exception e) {
            log.error("Erro ao parsear JSON de traducao", e);
            return Collections.emptyMap();
        }
    }

    private List<PatternEntry> compileRegexTranslations(Map<String, String> source) {
        List<PatternEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (!entry.getKey().contains("[")) {
                continue;
            }

            String[] parts = PLACEHOLDER.split(entry.getKey(), -1);
            StringBuilder pattern = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                pattern.append(Pattern.quote(parts[i]));
                if (i < parts.length - 1) {
                    pattern.append("(.+?)");
                }
            }
            entries.add(new PatternEntry(Pattern.compile(pattern.toString()), entry.getValue()));
        }
        return entries;
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        int groupId = event.getGroupId();

        if (config.enableDialogues() && contains(DIALOG_INTERFACES, groupId)) {
            scheduleTranslation(groupId);
            return;
        }

        if (contains(MAIN_TEXT_INTERFACES, groupId)) {
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableQuestJournal() && contains(QUEST_INTERFACES, groupId)) {
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableItems() && contains(ITEM_INTERFACES, groupId)) {
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableSkillGuide() && groupId == SKILL_GUIDE) {
            skillGuideOpen = true;
            skillGuideNeedsTranslation = true;
            translateInterface(SKILL_GUIDE);
        }
    }

    private boolean contains(int[] values, int target) {
        for (int value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    private void scheduleTranslation(int interfaceId) {
        QuestHelperCompat.scheduleDialogTranslation(clientThread, interfaceId, () -> translateInterface(interfaceId));
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!skillGuideOpen || !config.enableSkillGuide()) {
            return;
        }

        String option = event.getMenuOption();
        if ("View".equals(option) || "Visualizar".equals(option)) {
            skillGuideNeedsTranslation = true;
            translateInterface(SKILL_GUIDE);
        }
    }

    @Subscribe
    public void onBeforeRender(BeforeRender event) {
        if (!skillGuideOpen || !config.enableSkillGuide()) {
            return;
        }

        Widget root = client.getWidget(SKILL_GUIDE, 0);
        if (root == null || root.isHidden()) {
            skillGuideOpen = false;
            skillGuideNeedsTranslation = false;
            return;
        }

        if (skillGuideNeedsTranslation) {
            skillGuideNeedsTranslation = false;
            translateInterface(SKILL_GUIDE);
        }
    }

    private void translateInterface(int interfaceId) {
        for (int i = 0; i < 100; i++) {
            Widget widget = client.getWidget(interfaceId, i);
            if (widget != null) {
                translateWidget(widget, interfaceId);
            }
        }
    }

    private void translateWidget(Widget widget, int interfaceId) {
        if (widget == null) {
            return;
        }

        String text = widget.getText();
        if (text != null && !text.isEmpty()) {
            String translated = translateWidgetText(text, interfaceId);
            if (translated != null) {
                widget.setText(translated);
                return;
            }
        }

        Widget[] children = widget.getChildren();
        if (children != null) {
            for (Widget child : children) {
                translateWidget(child, interfaceId);
            }
        }

        Widget[] dynamicChildren = widget.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                translateWidget(child, interfaceId);
            }
        }
    }

    private String translateWidgetText(String originalText, int interfaceId) {
        String cleanText = normalizeLookupText(cleanText(originalText));
        if (cleanText.isEmpty()) {
            return null;
        }

        String numberingPrefix = QuestHelperCompat.extractNumberingPrefix(cleanText);
        String lookup = numberingPrefix.isEmpty() ? cleanText : QuestHelperCompat.stripNumberingPrefix(cleanText);

        if (isHansTimePlayedMessage(lookup)) {
            return preserveStartTags(originalText) + numberingPrefix + translateHans(lookup);
        }

        TranslationDomain domain = domainForInterface(interfaceId);
        if (domain.values.contains(lookup)) {
            return null;
        }

        String translation = findTranslation(domain.translations, lookup);
        if (translation == null && domain.useRegexFallback) {
            translation = findRegexTranslation(lookup);
        }

        return translation == null ? null : preserveStartTags(originalText) + numberingPrefix + translation;
    }

    private TranslationDomain domainForInterface(int interfaceId) {
        if (interfaceId == SKILL_GUIDE) {
            return new TranslationDomain(translationsSkills, translationSkillsValues, false);
        }
        if (interfaceId == QUEST_JOURNAL_MINIMAP || interfaceId == QUEST_JOURNAL) {
            return new TranslationDomain(translationsQuests, translationQuestsValues, false);
        }
        if (interfaceId == ITEM_PREVIEW || interfaceId == BOOKS_NOTES) {
            return new TranslationDomain(translationsItems, translationItemsValues, false);
        }
        return new TranslationDomain(translations, translationValues, true);
    }

    @Subscribe
    public void onOverheadTextChanged(OverheadTextChanged event) {
        if (!config.enableOverhead()) {
            return;
        }

        Actor actor = event.getActor();
        if (!(actor instanceof NPC)) {
            return;
        }

        String clean = cleanText(event.getOverheadText());
        if (clean.length() <= 3) {
            return;
        }

        String translation = translationsOverhead.get(clean);
        if (translation != null) {
            actor.setOverheadText(translation);
        }
    }

    @Subscribe
    public void onMenuEntryAdded(net.runelite.api.events.MenuEntryAdded event) {
        if (!config.enableMenuEntries()) {
            return;
        }

        net.runelite.api.MenuEntry entry = event.getMenuEntry();
        if (entry == null) {
            return;
        }

        String option = entry.getOption();
        if (option == null || option.isEmpty()) {
            return;
        }

        String cleanOption = HTML_TAG.matcher(option).replaceAll("").trim();
        String translation = translationsMenu.get(cleanOption);
        if (translation != null && !translation.equals(cleanOption)) {
            entry.setOption(option.replaceAll(Pattern.quote(cleanOption), Matcher.quoteReplacement(translation)));
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!config.enableGameMessages()) {
            return;
        }

        String clean = cleanText(event.getMessage());
        if (clean.isEmpty() || translationGameMessageValues.contains(clean)) {
            return;
        }

        String translation = findTranslation(translationsGameMessage, clean);
        if (translation == null) {
            return;
        }

        MessageNode messageNode = event.getMessageNode();
        if (messageNode != null) {
            messageNode.setValue(translation);
            messageNode.setRuneLiteFormatMessage(translation);
        }
        event.setMessage(translation);
    }

    private String findTranslation(Map<String, String> source, String lookup) {
        String id = textToId(lookup);
        String translation = id == null ? null : source.get(id);
        return translation == null ? source.get(lookup) : translation;
    }

    private String findRegexTranslation(String lookup) {
        for (PatternEntry entry : regexTranslations) {
            Matcher matcher = entry.pattern.matcher(lookup);
            if (!matcher.matches()) {
                continue;
            }

            String result = entry.translation;
            for (int i = 1; i <= matcher.groupCount(); i++) {
                result = result.replaceFirst("\\[[^\\]]+\\]", Matcher.quoteReplacement(matcher.group(i)));
            }
            return result;
        }
        return null;
    }

    private String cleanText(String text) {
        if (text == null) {
            return "";
        }

        String clean = BR_TAG.matcher(text).replaceAll(" ");
        clean = HTML_TAG.matcher(clean).replaceAll(" ");
        return clean.replaceAll("\\s+", " ").trim();
    }

    private String normalizeLookupText(String text) {
        return text
            .replaceAll("\\s+([.,!?])", "$1")
            .replaceAll("([Hh])ardcore Ironmen ", "$1ardcore Ironman ")
            .replaceAll("standard Ironmen ", "standard Ironman ");
    }

    private boolean isHansTimePlayedMessage(String lookup) {
        return lookup.startsWith("You've spent")
            && lookup.contains("in the world since you arrived")
            && lookup.endsWith("ago.");
    }

    private String preserveStartTags(String text) {
        Matcher matcher = START_TAGS.matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    private String translateHans(String clean) {
        Matcher matcher = HANS_PATTERN.matcher(clean);
        if (matcher.matches()) {
            return "Voce passou " + matcher.group(1) + " dias, "
                + matcher.group(2) + " horas, " + matcher.group(3)
                + " minutos no mundo desde que chegou "
                + matcher.group(4) + " dias atras.";
        }

        Matcher fallback = NUMBER_PATTERN.matcher(clean);
        String daysInWorld = findNext(fallback);
        String hoursInWorld = findNext(fallback);
        String minutesInWorld = findNext(fallback);
        String daysSinceArrival = findNext(fallback);

        return "Voce passou " + daysInWorld + " dias, "
            + hoursInWorld + " horas, " + minutesInWorld
            + " minutos no mundo desde que chegou "
            + daysSinceArrival + " dias atras.";
    }

    private String findNext(Matcher matcher) {
        return matcher.find() ? matcher.group(1) : "";
    }

    private String textToId(String text) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hash = messageDigest.digest(text.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                id.append(String.format("%02x", hash[i]));
            }
            return id.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    @Provides
    OsrsTranslateConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(OsrsTranslateConfig.class);
    }
}
