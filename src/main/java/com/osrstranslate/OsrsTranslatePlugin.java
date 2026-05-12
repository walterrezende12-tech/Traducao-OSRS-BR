package com.osrstranslate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MessageNode;
import net.runelite.api.NPC;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final int WELCOME_SCREEN = 378;
    private static final long LOGIN_INSPECTION_WINDOW_MS = 30_000L;
    private static final int LOGIN_GROUP_SCAN_LIMIT = 900;
    private static final int LOGIN_CHILD_SCAN_LIMIT = 200;

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
    @Inject private OverlayManager overlayManager;
    @Inject private PluginManager pluginManager;
    @Inject private SpriteManager spriteManager;
    @Inject private ContextualCursorOverlay contextualCursorOverlay;

    private volatile Map<String, String> translations = Collections.emptyMap();
    private volatile Map<String, String> translationsSkills = Collections.emptyMap();
    private volatile Map<String, String> translationsQuests = Collections.emptyMap();
    private volatile Map<String, String> translationsItems = Collections.emptyMap();
    private volatile Map<String, String> translationsMenu = Collections.emptyMap();
    private volatile Map<String, String> translationsOverhead = Collections.emptyMap();
    private volatile Map<String, String> translationsGameMessage = Collections.emptyMap();
    private volatile Map<String, String> translationsWelcome = Collections.emptyMap();

    private volatile Set<String> translationValues = Collections.emptySet();
    private volatile Set<String> translationSkillsValues = Collections.emptySet();
    private volatile Set<String> translationQuestsValues = Collections.emptySet();
    private volatile Set<String> translationItemsValues = Collections.emptySet();
    private volatile Set<String> translationGameMessageValues = Collections.emptySet();
    private volatile Set<String> translationWelcomeValues = Collections.emptySet();
    private volatile List<PatternEntry> regexTranslations = Collections.emptyList();
    private volatile List<PatternEntry> regexGameMessageTranslations = Collections.emptyList();
    private volatile List<PatternEntry> regexWelcomeTranslations = Collections.emptyList();
    private volatile Map<String, String> reverseTranslationsMenu = Collections.emptyMap();

    private boolean skillGuideOpen;
    private boolean skillGuideNeedsTranslation;
    private ScheduledExecutorService reloadScheduler;
    private final AtomicLong lastModified = new AtomicLong(0);
    private final AtomicLong lastModifiedSkills = new AtomicLong(0);
    private final AtomicLong lastModifiedQuests = new AtomicLong(0);
    private final AtomicLong lastModifiedItems = new AtomicLong(0);
    private final AtomicLong lastModifiedMenu = new AtomicLong(0);
    private final AtomicLong lastModifiedOverhead = new AtomicLong(0);
    private final AtomicLong lastModifiedGameMessage = new AtomicLong(0);
    private final AtomicLong lastModifiedWelcome = new AtomicLong(0);
    private File hotReloadFile;
    private File hotReloadSkillsFile;
    private File hotReloadQuestsFile;
    private File hotReloadItemsFile;
    private File hotReloadMenuFile;
    private File hotReloadOverheadFile;
    private File hotReloadGameMessageFile;
    private File hotReloadWelcomeFile;
    private long loginInspectionDeadline;
    private final Set<Integer> inspectedLoginGroups = new HashSet<>();
    private final Set<Integer> loginInterfaceGroups = new HashSet<>();

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
        log.info("[OsrsTranslatePlugin] startUp() chamado!");
        configureHotReloadFiles();
        loadTranslations();
        startHotReloadWatcher();
        overlayManager.add(contextualCursorOverlay);
    }

    @Override
    protected void shutDown() {
        log.info("[OsrsTranslatePlugin] shutDown() chamado!");
        if (reloadScheduler != null) {
            reloadScheduler.shutdownNow();
            reloadScheduler = null;
        }
        translations = Collections.emptyMap();
        translationsSkills = Collections.emptyMap();
        translationsQuests = Collections.emptyMap();
        translationsItems = Collections.emptyMap();
        translationsMenu = Collections.emptyMap();
        translationsOverhead = Collections.emptyMap();
        translationsGameMessage = Collections.emptyMap();
        translationsWelcome = Collections.emptyMap();
        translationValues = Collections.emptySet();
        translationSkillsValues = Collections.emptySet();
        translationQuestsValues = Collections.emptySet();
        translationItemsValues = Collections.emptySet();
        translationGameMessageValues = Collections.emptySet();
        translationWelcomeValues = Collections.emptySet();
        regexTranslations = Collections.emptyList();
        regexGameMessageTranslations = Collections.emptyList();
        regexWelcomeTranslations = Collections.emptyList();
        reverseTranslationsMenu = Collections.emptyMap();
        skillGuideOpen = false;
        skillGuideNeedsTranslation = false;
        loginInspectionDeadline = 0L;
        inspectedLoginGroups.clear();
        loginInterfaceGroups.clear();
        overlayManager.remove(contextualCursorOverlay);
    }

    private void loadTranslations() {
        Map<String, String> newTranslations = loadMap("/com/osrstranslate/translations.json");
        Map<String, String> newSkills = loadMap("/com/osrstranslate/translations_skills.json");
        Map<String, String> newQuests = loadMap("/com/osrstranslate/translations_quests.json");
        Map<String, String> newItems = loadMap("/com/osrstranslate/translations_items.json");
        Map<String, String> newMenu = loadMap("/com/osrstranslate/translations_menu.json");
        Map<String, String> newOverhead = loadMap("/com/osrstranslate/translations_overhead.json");
        Map<String, String> newGameMessage = loadMap("/com/osrstranslate/translations_game_message.json");
        Map<String, String> newWelcome = loadMap("/com/osrstranslate/translations_welcome.json");

        translations = newTranslations;
        translationsSkills = newSkills;
        translationsQuests = newQuests;
        translationsItems = newItems;
        translationsMenu = newMenu;
        translationsOverhead = newOverhead;
        translationsGameMessage = newGameMessage;
        translationsWelcome = newWelcome;
        translationValues = new HashSet<>(newTranslations.values());
        translationSkillsValues = new HashSet<>(newSkills.values());
        translationQuestsValues = new HashSet<>(newQuests.values());
        translationItemsValues = new HashSet<>(newItems.values());
        translationGameMessageValues = new HashSet<>(newGameMessage.values());
        translationWelcomeValues = new HashSet<>(newWelcome.values());
        regexTranslations = compileRegexTranslations(newTranslations);
        regexGameMessageTranslations = compileRegexTranslations(newGameMessage);
        regexWelcomeTranslations = compileRegexTranslations(newWelcome);
        reverseTranslationsMenu = buildReverseMenuMap(newMenu);

        log.info(
            "PT-BR carregado: dialogos={} skills={} quests={} items={} menu={} overhead={} gameMessages={} welcome={} regex={} welcomeRegex={}",
            newTranslations.size(), newSkills.size(), newQuests.size(), newItems.size(), newMenu.size(),
            newOverhead.size(), newGameMessage.size(), newWelcome.size(),
            regexTranslations.size(), regexWelcomeTranslations.size()
        );
    }

    private void configureHotReloadFiles() {
        try {
            hotReloadFile = resolveHotReloadFile("/com/osrstranslate/translations.json");
            hotReloadSkillsFile = resolveHotReloadFile("/com/osrstranslate/translations_skills.json");
            hotReloadQuestsFile = resolveHotReloadFile("/com/osrstranslate/translations_quests.json");
            hotReloadItemsFile = resolveHotReloadFile("/com/osrstranslate/translations_items.json");
            hotReloadMenuFile = resolveHotReloadFile("/com/osrstranslate/translations_menu.json");
            hotReloadOverheadFile = resolveHotReloadFile("/com/osrstranslate/translations_overhead.json");
            hotReloadGameMessageFile = resolveHotReloadFile("/com/osrstranslate/translations_game_message.json");
            hotReloadWelcomeFile = resolveHotReloadFile("/com/osrstranslate/translations_welcome.json");
        } catch (Exception e) {
            log.debug("Hot-reload indisponivel", e);
        }
    }

    private File resolveHotReloadFile(String resourcePath) {
        try {
            java.net.URL url = getClass().getResource(resourcePath);
            if (url == null || !"file".equals(url.getProtocol())) {
                return null;
            }

            File buildFile = new File(url.toURI());
            File srcFile = new File(buildFile.getAbsolutePath()
                .replace(
                    File.separator + "build" + File.separator + "resources" + File.separator + "main" + File.separator,
                    File.separator + "src" + File.separator + "main" + File.separator + "resources" + File.separator
                ));

            File resolved = srcFile.exists() ? srcFile : buildFile;
            log.info("Hot-reload ativo: monitorando {}", resolved.getAbsolutePath());
            return resolved;
        } catch (Exception e) {
            log.debug("Nao foi possivel resolver hot-reload para {}", resourcePath, e);
            return null;
        }
    }

    private void startHotReloadWatcher() {
        if (reloadScheduler != null) {
            reloadScheduler.shutdownNow();
        }

        reloadScheduler = Executors.newSingleThreadScheduledExecutor();
        reloadScheduler.scheduleWithFixedDelay(() -> {
            try {
                boolean changed = false;
                changed |= checkHotReloadFile(hotReloadFile, lastModified);
                changed |= checkHotReloadFile(hotReloadSkillsFile, lastModifiedSkills);
                changed |= checkHotReloadFile(hotReloadQuestsFile, lastModifiedQuests);
                changed |= checkHotReloadFile(hotReloadItemsFile, lastModifiedItems);
                changed |= checkHotReloadFile(hotReloadMenuFile, lastModifiedMenu);
                changed |= checkHotReloadFile(hotReloadOverheadFile, lastModifiedOverhead);
                changed |= checkHotReloadFile(hotReloadGameMessageFile, lastModifiedGameMessage);
                changed |= checkHotReloadFile(hotReloadWelcomeFile, lastModifiedWelcome);

                if (changed) {
                    loadTranslations();
                    log.info("Hot-reload: traducoes recarregadas");
                    if (skillGuideOpen) {
                        skillGuideNeedsTranslation = true;
                        log.info("Hot-reload: forcando re-traducao do Skill Guide");
                    }
                }
            } catch (Exception e) {
                log.warn("Erro no hot-reload", e);
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    private boolean checkHotReloadFile(File file, AtomicLong lastModifiedRef) {
        if (file == null || !file.exists()) {
            return false;
        }

        long current = file.lastModified();
        long previous = lastModifiedRef.get();
        if (current > previous) {
            lastModifiedRef.set(current);
            return previous != 0;
        }
        return false;
    }

    private Map<String, String> loadMap(String path) {
        try (InputStream is = openTranslationStream(path)) {
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

    private InputStream openTranslationStream(String path) throws Exception {
        File hotReloadTarget = fileForResourcePath(path);
        if (hotReloadTarget != null && hotReloadTarget.exists()) {
            updateLastModified(hotReloadTarget, lastModifiedForResourcePath(path));
            return new FileInputStream(hotReloadTarget);
        }
        return getClass().getResourceAsStream(path);
    }

    private File fileForResourcePath(String path) {
        switch (path) {
            case "/com/osrstranslate/translations.json":
                return hotReloadFile;
            case "/com/osrstranslate/translations_skills.json":
                return hotReloadSkillsFile;
            case "/com/osrstranslate/translations_quests.json":
                return hotReloadQuestsFile;
            case "/com/osrstranslate/translations_items.json":
                return hotReloadItemsFile;
            case "/com/osrstranslate/translations_menu.json":
                return hotReloadMenuFile;
            case "/com/osrstranslate/translations_overhead.json":
                return hotReloadOverheadFile;
            case "/com/osrstranslate/translations_game_message.json":
                return hotReloadGameMessageFile;
            case "/com/osrstranslate/translations_welcome.json":
                return hotReloadWelcomeFile;
            default:
                return null;
        }
    }

    private AtomicLong lastModifiedForResourcePath(String path) {
        switch (path) {
            case "/com/osrstranslate/translations.json":
                return lastModified;
            case "/com/osrstranslate/translations_skills.json":
                return lastModifiedSkills;
            case "/com/osrstranslate/translations_quests.json":
                return lastModifiedQuests;
            case "/com/osrstranslate/translations_items.json":
                return lastModifiedItems;
            case "/com/osrstranslate/translations_menu.json":
                return lastModifiedMenu;
            case "/com/osrstranslate/translations_overhead.json":
                return lastModifiedOverhead;
            case "/com/osrstranslate/translations_game_message.json":
                return lastModifiedGameMessage;
            case "/com/osrstranslate/translations_welcome.json":
                return lastModifiedWelcome;
            default:
                return null;
        }
    }

    private void updateLastModified(File file, AtomicLong lastModifiedRef) {
        if (file != null && lastModifiedRef != null && file.exists()) {
            lastModifiedRef.set(file.lastModified());
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

    private Map<String, String> buildReverseMenuMap(Map<String, String> source) {
        Map<String, String> reverse = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = normalizeMenuLookup(entry.getKey());
            String value = normalizeMenuLookup(entry.getValue());
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            reverse.putIfAbsent(value, key);
        }
        return reverse;
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        int groupId = event.getGroupId();
        log.info("[WidgetLoaded] groupId={}", groupId);

        if (isLoginScreenState()) {
            loginInspectionDeadline = System.currentTimeMillis() + LOGIN_INSPECTION_WINDOW_MS;
            loginInterfaceGroups.add(groupId);
            inspectLoginGroup(groupId);
            scheduleTranslation(groupId);
            return;
        }

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

        if (groupId == WELCOME_SCREEN) {
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

    private boolean isLoginScreenState() {
        GameState state = client.getGameState();
        return state == GameState.LOGIN_SCREEN
            || state == GameState.LOGIN_SCREEN_AUTHENTICATOR
            || state == GameState.LOGGING_IN
            || state == GameState.HOPPING;
    }

    private void inspectLoginGroup(int groupId) {
        if (System.currentTimeMillis() > loginInspectionDeadline || inspectedLoginGroups.contains(groupId)) {
            return;
        }

        Map<Integer, String> texts = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            Widget widget = client.getWidget(groupId, i);
            if (widget != null) {
                collectWidgetTexts(widget, texts);
            }
        }

        if (texts.isEmpty()) {
            return;
        }

        inspectedLoginGroups.add(groupId);
        log.info("[LoginInspect] groupId={} textos={}", groupId, texts.size());
        for (Map.Entry<Integer, String> entry : texts.entrySet()) {
            log.info("[LoginInspect] groupId={} widget={} text='{}'", groupId, entry.getKey(), entry.getValue());
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState state = event.getGameState();
        if (!isLoginScreenState()) {
            return;
        }

        loginInspectionDeadline = System.currentTimeMillis() + LOGIN_INSPECTION_WINDOW_MS;
        inspectedLoginGroups.clear();
        loginInterfaceGroups.clear();
        log.info("[LoginInspect] state={}", state);
        inspectVisibleLoginGroups();
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (!isLoginScreenState()) {
            return;
        }

        if (loginInspectionDeadline == 0L || System.currentTimeMillis() > loginInspectionDeadline) {
            loginInspectionDeadline = System.currentTimeMillis() + LOGIN_INSPECTION_WINDOW_MS;
        }

        inspectVisibleLoginGroups();
    }

    private void inspectVisibleLoginGroups() {
        for (int groupId = 0; groupId <= LOGIN_GROUP_SCAN_LIMIT; groupId++) {
            if (inspectedLoginGroups.contains(groupId)) {
                continue;
            }

            boolean foundAnyWidget = false;
            for (int childId = 0; childId < LOGIN_CHILD_SCAN_LIMIT; childId++) {
                if (client.getWidget(groupId, childId) != null) {
                    foundAnyWidget = true;
                    break;
                }
            }

            if (!foundAnyWidget) {
                continue;
            }

            loginInterfaceGroups.add(groupId);
            inspectLoginGroup(groupId);
        }
    }

    private void collectWidgetTexts(Widget widget, Map<Integer, String> texts) {
        if (widget == null) {
            return;
        }

        String clean = normalizeLookupText(cleanText(widget.getText()));
        if (!clean.isEmpty() && containsLetters(clean)) {
            texts.putIfAbsent(widget.getId(), clean);
        }

        Widget[] children = widget.getChildren();
        if (children != null) {
            for (Widget child : children) {
                collectWidgetTexts(child, texts);
            }
        }

        Widget[] dynamicChildren = widget.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) {
                collectWidgetTexts(child, texts);
            }
        }
    }

    private boolean containsLetters(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        if (!skillGuideOpen || !config.enableSkillGuide()) {
            return;
        }

        String option = event.getMenuOption();
        log.info("[DEBUG MenuOptionClicked] option='{}' target='{}' action={}",
            option, event.getMenuTarget(), event.getMenuAction());
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
        if (interfaceId == ITEM_PREVIEW) {
            log.info("[ItemPreview] Traduzindo interface 203");
        }
        if (interfaceId == SKILL_GUIDE) {
            log.info("[SkillGuide] traducao iniciada");
        }

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
            if (interfaceId == ITEM_PREVIEW) {
                log.info("[ItemPreview] processando texto: {}", text);
            }
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

        String id = textToId(lookup);
        String translation = id == null ? null : domain.translations.get(id);

        if (interfaceId == ITEM_PREVIEW) {
            log.info("[ItemPreview] Buscando: '{}' id={} pt={}", lookup, id, translation);
        }
        log.info("[Dialog DEBUG] cleanText='{}' lookup='{}' id='{}' pt={}", cleanText, lookup, id, translation);
        if (id != null) {
            boolean hasId = domain.translations.containsKey(id);
            String valById = domain.translations.get(id);
            log.info("[Dialog DEBUG] hasId={} valById='{}'", hasId, valById);
        }
        boolean hasTextKey = domain.translations.containsKey(lookup);
        String valByText = domain.translations.get(lookup);
        log.info("[Dialog DEBUG] hasTextKey={} lookupLen={} valByText='{}'", hasTextKey, lookup.length(), valByText);

        if (translation == null) {
            translation = domain.translations.get(lookup);
        }
        if (translation == null && domain.useRegexFallback) {
            translation = findRegexTranslation(regexForDomain(domain.translations), lookup);
        }

        if (translation != null) {
            log.info("[Dialog] Traduzido: '{}' -> '{}'", lookup, translation);
        } else {
            log.info("[Dialog] SEM TRADUCAO: '{}'", lookup);
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
        if (interfaceId == WELCOME_SCREEN) {
            return new TranslationDomain(translationsWelcome, translationWelcomeValues, true);
        }
        if (loginInterfaceGroups.contains(interfaceId)) {
            return new TranslationDomain(translationsWelcome, translationWelcomeValues, true);
        }
        return new TranslationDomain(translations, translationValues, true);
    }

    private List<PatternEntry> regexForDomain(Map<String, String> domainTranslations) {
        if (domainTranslations == translationsWelcome) {
            return regexWelcomeTranslations;
        }
        return regexTranslations;
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

    boolean isContextualCursorCompatEnabled() {
        return config.enableMenuEntries();
    }

    PluginManager getPluginManager() {
        return pluginManager;
    }

    SpriteManager getSpriteManager() {
        return spriteManager;
    }

    String reverseTranslateMenuOption(String option) {
        if (option == null || option.isEmpty()) {
            return null;
        }

        String normalized = normalizeMenuLookup(option);
        if (normalized.isEmpty()) {
            return null;
        }

        String english = reverseTranslationsMenu.get(normalized);
        if (english == null || english.equals(normalized)) {
            return null;
        }

        return english;
    }

    private String normalizeMenuLookup(String text) {
        return HTML_TAG.matcher(text).replaceAll("").trim().toLowerCase(Locale.ROOT);
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (!config.enableGameMessages()) {
            return;
        }

        if (!shouldTranslateChatMessage(event.getType())) {
            return;
        }

        String clean = cleanText(event.getMessage());
        if (clean.isEmpty() || translationGameMessageValues.contains(clean)) {
            return;
        }

        String translation = findTranslation(translationsGameMessage, clean);
        if (translation == null) {
            translation = findRegexTranslation(regexGameMessageTranslations, clean);
        }
        if (translation == null) {
            return;
        }

        log.info("[ChatTranslate] type={} original='{}' translated='{}'", event.getType(), clean, translation);

        MessageNode messageNode = event.getMessageNode();
        if (messageNode != null) {
            messageNode.setValue(translation);
            messageNode.setRuneLiteFormatMessage(translation);
        }
        event.setMessage(translation);
    }

    private boolean shouldTranslateChatMessage(ChatMessageType type) {
        if (type == null) {
            return false;
        }

        switch (type) {
            case GAMEMESSAGE:
            case ENGINE:
            case SPAM:
            case MESBOX:
            case CONSOLE:
            case WELCOME:
            case DIDYOUKNOW:
            case LOGINLOGOUTNOTIFICATION:
            case ITEM_EXAMINE:
                return true;
            default:
                return false;
        }
    }

    private String findTranslation(Map<String, String> source, String lookup) {
        String id = textToId(lookup);
        String translation = id == null ? null : source.get(id);
        return translation == null ? source.get(lookup) : translation;
    }

    private String findRegexTranslation(List<PatternEntry> patterns, String lookup) {
        for (PatternEntry entry : patterns) {
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
