package com.osrstranslate;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MessageNode;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
    name = "OSRS Translate PT-BR",
    description = "Traduz dialogos do OSRS para Portugues Brasileiro",
    tags = {"translate", "portuguese", "ptbr"}
)
public class OsrsTranslatePlugin extends Plugin {
    private static final int CHATBOX_UNIVERSE = 162;
    private static final int DIALOG_MESSAGE = 229;
    private static final int CHAT_OVERLAY = 263;
    private static final int PLAYER_DESIGN = 679;
    private static final int TUTORIAL_PLAYER_EXPERIENCE = 929;
    private static final int TUTORIAL_DISPLAY_NAME = 558;
    private static final int ITEM_PREVIEW = 203;
    private static final int SKILL_GUIDE = 214;
    private static final int SKILL_GUIDE_ALTERNATE = 860;
    private static final int BOOKS_NOTES = 680;
    private static final int QUEST_SCROLL = 153;
    private static final int QUEST_JOURNAL_MINIMAP = 782;
    private static final int QUEST_JOURNAL = 119;
    private static final int WELCOME_SCREEN = 378;
    private static final int SETTINGS = 134;
    private static final int BANK_PIN = 213;
    private static final int MACRO_MIME_EMOTES = 188;
    private static final int CRAFTING_GOLD = 446;
    private static final int TOPLEVEL_OSRS_STRETCH = 161;
    private static final long LOGIN_INSPECTION_WINDOW_MS = 30_000L;
    private static final int LOGIN_GROUP_SCAN_LIMIT = 900;
    private static final int LOGIN_CHILD_SCAN_LIMIT = 200;
    private static final String REMOTE_MANIFEST_URL = RemoteTranslationService.DEFAULT_MANIFEST_URL;
    private static final int REMOTE_UPDATE_INTERVAL_MINUTES = 60;

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
        QUEST_SCROLL,
        QUEST_JOURNAL_MINIMAP,
        QUEST_JOURNAL,
    };

    private static final int[] ITEM_INTERFACES = {
        ITEM_PREVIEW,
        BOOKS_NOTES,
    };

    private static final int[] SKILL_GUIDE_INTERFACES = {
        SKILL_GUIDE,
        SKILL_GUIDE_ALTERNATE,
    };

    private static final int[] SETTINGS_INTERFACES = {
        SETTINGS,
        BANK_PIN,
        TOPLEVEL_OSRS_STRETCH,
    };

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern START_TAGS = Pattern.compile("^(<str>|<col=[^>]+>)+");
    private static final Set<String> TRIVIAL_MENU_OPTIONS = Set.of(
        "Walk here",
        "Cancel",
        "Continue"
    );
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private OsrsTranslateConfig config;
    @Inject private Gson gson;
    @Inject private OkHttpClient httpClient;
    @Inject private EventBus eventBus;
    private final ExaminePluginBridge examinePluginBridge = new ExaminePluginBridge();
    private final TranslationRepository translationRepository = new TranslationRepository();
    private final WelcomeTranslationService welcomeTranslationService = new WelcomeTranslationService();
    private volatile TranslationState translationState = TranslationState.empty();

    private boolean skillGuideOpen;
    private boolean skillGuideNeedsTranslation;
    private int skillGuideInterfaceId = SKILL_GUIDE;
    private String lastTutorialPlayerExperienceSnapshot = "";
    private String lastChatboxUniverseSnapshot = "";
    private String lastChatOverlaySnapshot = "";
    private ScheduledExecutorService remoteUpdateScheduler;
    private RemoteTranslationService remoteTranslationService;
    private EventBus.Subscriber menuOpenedTranslationSubscriber;
    private long loginInspectionDeadline;
    private final Set<Integer> inspectedLoginGroups = new HashSet<>();
    private final Set<Integer> loginInterfaceGroups = new HashSet<>();

    @Override
    protected void startUp() {
        log.info("[OsrsTranslatePlugin] startUp() chamado!");
        OsrsTranslateConfigLocalization.start(config.translationLanguage());
        File translationCacheRoot = new File(
            RuneLite.RUNELITE_DIR,
            "osrs-translate" + File.separator + "translations"
        );
        log.info("Cache de traducoes remotas: {}", translationCacheRoot.getAbsolutePath());
        remoteTranslationService = new RemoteTranslationService(
            httpClient,
            gson,
            translationCacheRoot
        );
        translationRepository.configureRemoteCacheDirectory(
            remoteTranslationService.getActiveDirectory(selectedLanguageFolder())
        );
        translationState = translationRepository.loadState();
        menuOpenedTranslationSubscriber = eventBus.register(
            MenuOpened.class,
            event -> {
                if (config.enableMenuEntries()) {
                    // Run after Menu Entry Swapper has created its entries and
                    // submenus, but before the client renders the menu box.
                    translateMenu(client.getMenu());
                }
            },
            -1000f
        );
        startRemoteTranslationUpdater();
    }

    @Override
    protected void shutDown() {
        log.info("[OsrsTranslatePlugin] shutDown() chamado!");
        OsrsTranslateConfigLocalization.stop();
        if (remoteUpdateScheduler != null) {
            remoteUpdateScheduler.shutdownNow();
            remoteUpdateScheduler = null;
        }
        eventBus.unregister(menuOpenedTranslationSubscriber);
        menuOpenedTranslationSubscriber = null;
        remoteTranslationService = null;
        translationState = TranslationState.empty();
        skillGuideOpen = false;
        skillGuideNeedsTranslation = false;
        skillGuideInterfaceId = SKILL_GUIDE;
        lastTutorialPlayerExperienceSnapshot = "";
        lastChatboxUniverseSnapshot = "";
        lastChatOverlaySnapshot = "";
        loginInspectionDeadline = 0L;
        inspectedLoginGroups.clear();
        loginInterfaceGroups.clear();
    }

    private void startRemoteTranslationUpdater() {
        if (remoteTranslationService == null) {
            return;
        }

        if (remoteUpdateScheduler != null) {
            remoteUpdateScheduler.shutdownNow();
        }

        remoteUpdateScheduler = Executors.newSingleThreadScheduledExecutor();
        remoteUpdateScheduler.execute(this::checkRemoteTranslationUpdate);
        remoteUpdateScheduler.scheduleWithFixedDelay(
            this::checkRemoteTranslationUpdate,
            REMOTE_UPDATE_INTERVAL_MINUTES,
            REMOTE_UPDATE_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        );
    }

    private void checkRemoteTranslationUpdate() {
        try {
            String languageFolder = selectedLanguageFolder();
            RemoteTranslationService.UpdateResult result = remoteTranslationService.update(
                REMOTE_MANIFEST_URL,
                languageFolder
            );
            if (!languageFolder.equals(selectedLanguageFolder())) {
                return;
            }
            boolean sourceChanged = translationRepository.configureRemoteCacheDirectory(
                result.getActiveDirectory()
            );
            if (result.isChanged() || sourceChanged) {
                translationState = translationRepository.loadState();
                if (skillGuideOpen) {
                    skillGuideNeedsTranslation = true;
                }
                log.info("Traducoes remotas atualizadas para a versao {}", result.getVersion());
            } else {
                log.debug("Traducoes remotas ja estao na versao {}", result.getVersion());
            }
        } catch (Exception e) {
            log.warn("Nao foi possivel atualizar as traducoes remotas; mantendo o cache atual", e);
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!"osrstranslate".equals(event.getGroup())) {
            return;
        }

        OsrsTranslateConfig.TranslationLanguage changedLanguage = languageFromConfigValue(
            event.getNewValue()
        );
        OsrsTranslateConfigLocalization.localize(
            changedLanguage == null ? config.translationLanguage() : changedLanguage
        );
        if (!"translationLanguage".equals(event.getKey()) || remoteTranslationService == null) {
            return;
        }

        translationRepository.configureRemoteCacheDirectory(
            remoteTranslationService.getActiveDirectory(selectedLanguageFolder())
        );
        translationState = translationRepository.loadState();
        if (remoteUpdateScheduler != null && !remoteUpdateScheduler.isShutdown()) {
            remoteUpdateScheduler.execute(this::checkRemoteTranslationUpdate);
        }
    }

    private OsrsTranslateConfig.TranslationLanguage languageFromConfigValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        for (OsrsTranslateConfig.TranslationLanguage language
            : OsrsTranslateConfig.TranslationLanguage.values()) {
            if (value.equals(language.name())
                || value.equals(language.toString())
                || value.equals(language.getRepositoryFolder())) {
                return language;
            }
        }
        return null;
    }

    private String selectedLanguageFolder() {
        return config.translationLanguage().getRepositoryFolder();
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        int groupId = event.getGroupId();
        log.info("[WidgetLoaded] groupId={}", groupId);

        if (config.enableWelcome() && isLoginScreenState()) {
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

        if (config.enableGameMessages() && groupId == CHAT_OVERLAY) {
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableMenuEntries()
            && (groupId == PLAYER_DESIGN
                || groupId == TUTORIAL_PLAYER_EXPERIENCE
                || groupId == TUTORIAL_DISPLAY_NAME
                || groupId == MACRO_MIME_EMOTES
                || groupId == CRAFTING_GOLD)) {
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

        if (config.enableWelcome() && groupId == WELCOME_SCREEN) {
            scheduleTranslation(groupId);
            return;
        }

        if (config.enableSkillGuide() && contains(SKILL_GUIDE_INTERFACES, groupId)) {
            skillGuideOpen = true;
            skillGuideNeedsTranslation = true;
            skillGuideInterfaceId = groupId;
            translateInterface(groupId);
        }

        if (config.enableSettings() && contains(SETTINGS_INTERFACES, groupId)) {
            scheduleTranslation(groupId);
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
        log.debug("[LoginInspect] groupId={} textos={}", groupId, texts.size());
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        GameState state = event.getGameState();
        if (!config.enableWelcome() || !isLoginScreenState()) {
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
        if (!config.enableWelcome() || !isLoginScreenState()) {
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

        Widget[] nestedChildren = widget.getNestedChildren();
        if (nestedChildren != null) {
            for (Widget child : nestedChildren) {
                collectWidgetTexts(child, texts);
            }
        }
    }

    private String buildWidgetTextSnapshot(int interfaceId) {
        Widget root = client.getWidget(interfaceId, 0);
        if (root == null || root.isHidden()) {
            return "";
        }

        Map<Integer, String> texts = new LinkedHashMap<>();
        collectWidgetTexts(root, texts);
        if (texts.isEmpty()) {
            return "";
        }

        StringBuilder snapshot = new StringBuilder();
        for (Map.Entry<Integer, String> entry : texts.entrySet()) {
            snapshot.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return snapshot.toString();
    }

    private boolean containsLetters(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    @Subscribe(priority = 1000f)
    public void onMenuOptionClicked(MenuOptionClicked event) {
        examinePluginBridge.restoreEnglishOption(event, this);

        if (config.enableSettings()) {
            Widget bankPinRoot = client.getWidget(BANK_PIN, 0);
            if (bankPinRoot != null && !bankPinRoot.isHidden()) {
                clientThread.invokeLater(() -> clientThread.invokeLater(() -> translateInterface(BANK_PIN)));
            }
        }

        if (!skillGuideOpen || !config.enableSkillGuide()) {
            return;
        }

        String option = event.getMenuOption();
        log.info("[DEBUG MenuOptionClicked] option='{}' target='{}' action={}",
            option, event.getMenuTarget(), event.getMenuAction());
        String englishOption = reverseTranslateMenuOption(option);
        if ("View".equals(option) || "View".equals(englishOption)) {
            skillGuideNeedsTranslation = true;
            translateInterface(skillGuideInterfaceId);
        }
    }

    @Subscribe
    public void onBeforeRender(BeforeRender event) {
        suppressMouseHighlightTooltipForTranslatedMenuOptions();
        translateDynamicInterfaces();

        if (!skillGuideOpen || !config.enableSkillGuide()) {
            return;
        }

        int visibleSkillGuide = findVisibleInterface(SKILL_GUIDE_INTERFACES);
        if (visibleSkillGuide < 0) {
            skillGuideOpen = false;
            skillGuideNeedsTranslation = false;
            skillGuideInterfaceId = SKILL_GUIDE;
            return;
        }
        skillGuideInterfaceId = visibleSkillGuide;

        if (skillGuideNeedsTranslation) {
            skillGuideNeedsTranslation = false;
            translateInterface(skillGuideInterfaceId);
        }
    }

    private int findVisibleInterface(int[] interfaceIds) {
        for (int interfaceId : interfaceIds) {
            Widget root = client.getWidget(interfaceId, 0);
            if (root != null && !root.isHidden()) {
                return interfaceId;
            }
        }
        return -1;
    }

    private void translateDynamicInterfaces() {
        if (config.enableMenuEntries()) {
            String tutorialSnapshot = buildWidgetTextSnapshot(TUTORIAL_PLAYER_EXPERIENCE);
            String displayNameSnapshot = buildWidgetTextSnapshot(TUTORIAL_DISPLAY_NAME);
            String snapshot = !displayNameSnapshot.isEmpty() ? displayNameSnapshot : tutorialSnapshot;

            if (!snapshot.isEmpty() && !snapshot.equals(lastTutorialPlayerExperienceSnapshot)) {
                lastTutorialPlayerExperienceSnapshot = snapshot;
                if (!displayNameSnapshot.isEmpty()) {
                    translateInterface(TUTORIAL_DISPLAY_NAME);
                }
                if (!tutorialSnapshot.isEmpty()) {
                    translateInterface(TUTORIAL_PLAYER_EXPERIENCE);
                }
            }

            Widget macroMimeRoot = client.getWidget(MACRO_MIME_EMOTES, 0);
            if (macroMimeRoot != null && !macroMimeRoot.isHidden()) {
                translateInterface(MACRO_MIME_EMOTES);
            }

            Widget craftingGoldRoot = client.getWidget(CRAFTING_GOLD, 0);
            if (craftingGoldRoot != null && !craftingGoldRoot.isHidden()) {
                translateInterface(CRAFTING_GOLD);
            }
        }

        if (config.enableGameMessages()) {
            String overlaySnapshot = buildWidgetTextSnapshot(CHAT_OVERLAY);
            if (!overlaySnapshot.isEmpty() && !overlaySnapshot.equals(lastChatOverlaySnapshot)) {
                lastChatOverlaySnapshot = overlaySnapshot;
                translateInterface(CHAT_OVERLAY);
            }
        }

        if (config.enableWelcome()) {
            Widget welcomeRoot = client.getWidget(WELCOME_SCREEN, 0);
            if (welcomeRoot != null && !welcomeRoot.isHidden()) {
                translateInterface(WELCOME_SCREEN);
            }

            if (isLoginScreenState()) {
                for (int groupId : loginInterfaceGroups) {
                    if (groupId != WELCOME_SCREEN) {
                        translateInterface(groupId);
                    }
                }
            }
        }
    }

    private void suppressMouseHighlightTooltipForTranslatedMenuOptions() {
        if (!config.enableMenuEntries() || client.isMenuOpen()) {
            return;
        }

        MenuEntry[] menuEntries = client.getMenuEntries();
        if (menuEntries == null || menuEntries.length == 0) {
            return;
        }

        MenuEntry lastEntry = menuEntries[menuEntries.length - 1];
        if (lastEntry == null) {
            return;
        }

        String englishOption = reverseTranslateMenuOption(lastEntry.getOption());
        if (englishOption == null || !TRIVIAL_MENU_OPTIONS.contains(englishOption)) {
            return;
        }

        int currentCycle = client.getGameCycle();
        int tooltipTimeout = client.getVarcIntValue(VarClientID.TOOLTIP_TIME);
        if (tooltipTimeout <= currentCycle) {
            client.setVarcIntValue(VarClientID.TOOLTIP_TIME, currentCycle + 1);
        }
    }

    private void translateInterface(int interfaceId) {
        if (interfaceId == ITEM_PREVIEW) {
            log.info("[ItemPreview] Traduzindo interface 203");
        }
        if (contains(SKILL_GUIDE_INTERFACES, interfaceId)) {
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

            int wBefore = widget.getWidth();
            int hBefore = widget.getHeight();
            int xBefore = widget.getRelativeX();
            int yBefore = widget.getRelativeY();
            int idBefore = widget.getId();
            int parentId = widget.getParentId();
            int lineHeightAntes = widget.getLineHeight();
            int linesAntes = countLines(text);

            applyLineHeightFix(widget, interfaceId, lineHeightAntes);

            String translated = translateWidgetText(text, interfaceId);
            if (translated != null) {
                int canvasW = client.getCanvasWidth();
                int canvasH = client.getCanvasHeight();
                log.debug(
                    "[WidgetDebug] id={} parentId={} pos=({},{}) size=({},{}) "
                        + "canvas=({},{}) textLen={} transLen={}",
                    idBefore, parentId, xBefore, yBefore, wBefore, hBefore,
                    canvasW, canvasH, text.length(), translated.length()
                );
                log.debug("[WidgetDebug] text='{}'", text.length() > 80 ? text.substring(0, 80) + "..." : text);
                log.debug(
                    "[WidgetDebug] trans='{}'",
                    translated.length() > 80 ? translated.substring(0, 80) + "..." : translated
                );
                log.debug(
                    "[LineHeightDebug] ANTES: id={} lineHeight={} linhas={}",
                    idBefore, lineHeightAntes, linesAntes
                );

                widget.setText(translated);

                int lineHeightDepois = widget.getLineHeight();
                int linesDepois = countLines(translated);
                log.debug(
                    "[LineHeightDebug] DEPOIS: id={} lineHeight={} linhas={}",
                    idBefore, lineHeightDepois, linesDepois
                );
                if (lineHeightAntes != lineHeightDepois) {
                    log.debug("[LineHeightDebug] MUDOU! id={} {} -> {}", idBefore, lineHeightAntes, lineHeightDepois);
                }

                applyLineHeightFix(widget, interfaceId, Math.max(lineHeightAntes, lineHeightDepois));

                int wAfter = widget.getWidth();
                int hAfter = widget.getHeight();
                if (wAfter != wBefore || hAfter != hBefore) {
                    log.debug(
                        "[WidgetDebug] CHANGED! id={} {}x{} -> {}x{}",
                        idBefore, wBefore, hBefore, wAfter, hAfter
                    );
                }
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

        Widget[] nestedChildren = widget.getNestedChildren();
        if (nestedChildren != null) {
            for (Widget child : nestedChildren) {
                translateWidget(child, interfaceId);
            }
        }
    }

    private void applyLineHeightFix(Widget widget, int interfaceId, int referenceLineHeight) {
        if (!config.enableTextWrapFix() || widget == null || referenceLineHeight <= 18) {
            return;
        }

        // Chat/game chat widgets use their own spacing and get visually squashed if we force line height.
        if (interfaceId == CHATBOX_UNIVERSE || interfaceId == CHAT_OVERLAY) {
            return;
        }

        if (widget.getLineHeight() == 18) {
            return;
        }

        widget.setLineHeight(18);
        log.info("[LineHeightFix] id={} {} -> 18", widget.getId(), referenceLineHeight);
    }

    private int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private String translateWidgetText(String originalText, int interfaceId) {
        String cleanText = normalizeLookupText(cleanText(originalText));
        if (cleanText.isEmpty()) {
            return null;
        }

        String numberingPrefix = QuestHelperCompat.extractNumberingPrefix(cleanText);
        String lookup = numberingPrefix.isEmpty() ? cleanText : QuestHelperCompat.stripNumberingPrefix(cleanText);

        String specialTranslation = welcomeTranslationService.translateSpecialText(
            lookup,
            interfaceId,
            loginInterfaceGroups,
            translationState.translationsWelcome
        );
        if (specialTranslation != null) {
            return preserveStartTags(originalText) + numberingPrefix + specialTranslation;
        }

        TranslationState state = translationState;
        TranslationDomain domain = domainForInterface(interfaceId, state);
        if (domain.values.contains(lookup)) {
            return null;
        }

        String translation = TranslationLookupHelper.findTranslation(domain.translations, domain.regexPatterns, lookup);

        if (translation != null && interfaceId == CHAT_OVERLAY) {
            translation = normalizeChatOverlayTags(translation);
        }

        return translation == null ? null : preserveStartTags(originalText) + numberingPrefix + translation;
    }

    private String normalizeChatOverlayTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        return BR_TAG.matcher(text)
            .replaceAll("\n")
            .replace("</col>", "<col=000000>");
    }

    private TranslationDomain domainForInterface(int interfaceId, TranslationState state) {
        if (contains(SKILL_GUIDE_INTERFACES, interfaceId)) {
            return new TranslationDomain(
                state.translationsSkills,
                state.translationSkillsValues,
                Collections.emptyList()
            );
        }
        if (interfaceId == QUEST_JOURNAL_MINIMAP || interfaceId == QUEST_JOURNAL) {
            return new TranslationDomain(
                state.translationsQuests,
                state.translationQuestsValues,
                Collections.emptyList()
            );
        }
        if (interfaceId == ITEM_PREVIEW || interfaceId == BOOKS_NOTES) {
            return new TranslationDomain(
                state.translationsItems,
                state.translationItemsValues,
                Collections.emptyList()
            );
        }
        if (interfaceId == PLAYER_DESIGN
            || interfaceId == TUTORIAL_PLAYER_EXPERIENCE
            || interfaceId == TUTORIAL_DISPLAY_NAME
            || interfaceId == MACRO_MIME_EMOTES
            || interfaceId == CRAFTING_GOLD) {
            return new TranslationDomain(state.translationsMenu, state.translationMenuValues, Collections.emptyList());
        }
        if (interfaceId == CHAT_OVERLAY) {
            return new TranslationDomain(
                state.translationsGameMessage,
                state.translationGameMessageValues,
                state.regexGameMessageTranslations
            );
        }
        if (interfaceId == WELCOME_SCREEN) {
            return new TranslationDomain(
                state.translationsWelcome,
                state.translationWelcomeValues,
                state.regexWelcomeTranslations
            );
        }
        if (loginInterfaceGroups.contains(interfaceId)) {
            return new TranslationDomain(
                state.translationsWelcome,
                state.translationWelcomeValues,
                state.regexWelcomeTranslations
            );
        }
        if (interfaceId == SETTINGS || interfaceId == BANK_PIN) {
            return new TranslationDomain(
                state.translationsSettings,
                state.translationSettingsValues,
                Collections.emptyList()
            );
        }
        return new TranslationDomain(state.translations, state.translationValues, state.regexTranslations);
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

        String clean = normalizeLookupText(cleanText(event.getOverheadText()));
        if (clean.length() <= 3) {
            return;
        }

        String translation = TranslationLookupHelper.findTranslation(
            translationState.translationsOverhead,
            translationState.regexOverheadTranslations,
            clean
        );
        if (translation != null) {
            actor.setOverheadText(translation);
            log.debug("[OverheadTranslate] '{}' -> '{}'", clean, translation);
        }
    }

    // Menu Entry Swapper also handles PostMenuSort and matches the original
    // English option/target text. Translate only after it has completed all
    // built-in, custom, item, NPC, object, UI, and submenu swaps.
    @Subscribe(priority = -1000f)
    public void onPostMenuSort(PostMenuSort event) {
        if (!config.enableMenuEntries()) {
            return;
        }

        translateMenu(client.getMenu());
    }

    @Subscribe(priority = 1000f)
    public void onMenuOpened(MenuOpened event) {
        if (!config.enableMenuEntries()) {
            return;
        }

        // Menu Entry Swapper identifies original entries such as Examine when
        // it creates its Swap left-click/shift-click submenus. Restore those
        // labels before its MenuOpened handler runs.
        restoreEnglishMenu(client.getMenu());
    }

    private void translateMenu(Menu menu) {
        if (menu == null) {
            return;
        }

        for (MenuEntry entry : menu.getMenuEntries()) {
            translateMenuEntry(entry);

            Menu subMenu = entry.getSubMenu();
            if (subMenu != null) {
                translateMenu(subMenu);
            }
        }
    }

    private void translateMenuEntry(MenuEntry entry) {
        if (entry == null) {
            return;
        }

        String option = entry.getOption();
        if (option == null || option.isEmpty()) {
            return;
        }

        String cleanOption = HTML_TAG.matcher(option).replaceAll("").trim();
        String translation = translationState.translationsMenu.get(cleanOption);
        if (translation != null && !translation.equals(cleanOption)) {
            entry.setOption(option.replaceAll(Pattern.quote(cleanOption), Matcher.quoteReplacement(translation)));
        }
    }

    private void restoreEnglishMenu(Menu menu) {
        if (menu == null) {
            return;
        }

        for (MenuEntry entry : menu.getMenuEntries()) {
            restoreEnglishMenuEntry(entry);

            Menu subMenu = entry.getSubMenu();
            if (subMenu != null) {
                restoreEnglishMenu(subMenu);
            }
        }
    }

    private void restoreEnglishMenuEntry(MenuEntry entry) {
        if (entry == null) {
            return;
        }

        String englishOption = reverseTranslateMenuOption(entry.getOption());
        if (englishOption != null) {
            entry.setOption(englishOption);
        }
    }

    boolean isMenuTranslationEnabled() {
        return config.enableMenuEntries();
    }

    String reverseTranslateMenuOption(String option) {
        if (option == null || option.isEmpty()) {
            return null;
        }

        String normalized = normalizeMenuLookup(option);
        if (normalized.isEmpty()) {
            return null;
        }

        String english = translationState.reverseTranslationsMenu.get(normalized);
        if (english == null || english.equals(normalized)) {
            return null;
        }

        return english;
    }

    private String stripMenuText(String text) {
        return HTML_TAG.matcher(text).replaceAll("").trim();
    }

    private String normalizeMenuLookup(String text) {
        return stripMenuText(text).toLowerCase(Locale.ROOT);
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
        TranslationState state = translationState;
        if (clean.isEmpty() || state.translationGameMessageValues.contains(clean)) {
            return;
        }

        String translation = TranslationLookupHelper.findTranslation(
            state.translationsGameMessage,
            state.regexGameMessageTranslations,
            clean
        );
        if (translation == null) {
            return;
        }

        if (shouldDeferRichChatOverlayTranslation(translation)) {
            translateInterface(CHAT_OVERLAY);
            return;
        }

        translation = normalizeChatOverlayTags(translation);

        log.debug("[ChatTranslate] type={} translatedLen={}", event.getType(), translation.length());

        MessageNode messageNode = event.getMessageNode();
        if (messageNode != null) {
            messageNode.setValue(translation);
            messageNode.setRuneLiteFormatMessage(translation);
        }
        event.setMessage(translation);
        client.refreshChat();
    }

    private boolean shouldDeferRichChatOverlayTranslation(String translation) {
        if (translation == null) {
            return false;
        }

        if (!containsRichOverlayFormatting(translation)) {
            return false;
        }

        return !buildWidgetTextSnapshot(CHAT_OVERLAY).isEmpty();
    }

    private boolean containsRichOverlayFormatting(String text) {
        return text.contains("<br") || text.contains("</col>") || text.contains("\n");
    }

    private boolean shouldTranslateChatMessage(ChatMessageType type) {
        if (type == null) {
            return false;
        }

        switch (type) {
            case GAMEMESSAGE:
            case LEVELUPMESSAGE:
            case ENGINE:
            case SPAM:
            case MESBOX:
            case CONSOLE:
            case DIDYOUKNOW:
            case LOGINLOGOUTNOTIFICATION:
            case ITEM_EXAMINE:
            case NPC_EXAMINE:
            case OBJECT_EXAMINE:
                return true;
            case WELCOME:
                return config.enableWelcome();
            default:
                return false;
        }
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

    private String preserveStartTags(String text) {
        Matcher matcher = START_TAGS.matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    private static final class TranslationDomain {
        private final Map<String, String> translations;
        private final Set<String> values;
        private final List<TranslationLookupHelper.PatternEntry> regexPatterns;

        private TranslationDomain(
            Map<String, String> translations,
            Set<String> values,
            List<TranslationLookupHelper.PatternEntry> regexPatterns
        ) {
            this.translations = translations;
            this.values = values;
            this.regexPatterns = regexPatterns;
        }
    }

    private static final class TranslationState {
        private final Map<String, String> translations;
        private final Map<String, String> translationsSkills;
        private final Map<String, String> translationsQuests;
        private final Map<String, String> translationsItems;
        private final Map<String, String> translationsMenu;
        private final Map<String, String> translationsOverhead;
        private final Map<String, String> translationsGameMessage;
        private final Map<String, String> translationsWelcome;
        private final Map<String, String> translationsSettings;
        private final Set<String> translationValues;
        private final Set<String> translationSkillsValues;
        private final Set<String> translationQuestsValues;
        private final Set<String> translationItemsValues;
        private final Set<String> translationMenuValues;
        private final Set<String> translationGameMessageValues;
        private final Set<String> translationWelcomeValues;
        private final Set<String> translationSettingsValues;
        private final List<TranslationLookupHelper.PatternEntry> regexTranslations;
        private final List<TranslationLookupHelper.PatternEntry> regexOverheadTranslations;
        private final List<TranslationLookupHelper.PatternEntry> regexGameMessageTranslations;
        private final List<TranslationLookupHelper.PatternEntry> regexWelcomeTranslations;
        private final Map<String, String> reverseTranslationsMenu;

        private TranslationState(
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

        private static TranslationState empty() {
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

    private static final class TranslationRepository {
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

        private boolean configureRemoteCacheDirectory(File directory) {
            File previous = remoteCacheDirectory;
            remoteCacheDirectory = directory;
            return previous == null ? directory != null : !previous.equals(directory);
        }

        private TranslationState loadState() {
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
                    + "overhead={} gameMessages={} welcome={} settings={} regex={} overheadRegex={} welcomeRegex={}",
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

    @Provides
    OsrsTranslateConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(OsrsTranslateConfig.class);
    }

    /**
     * Faz quebra de linha justificada: distribui palavras pra cada linha ter tamanho similar.
     * @param text texto a quebrar
     * @param maxCharsPorLinha número máximo de caracteres por linha
     * @return texto com quebras de linha (\n) balanceadas
     */
    }
