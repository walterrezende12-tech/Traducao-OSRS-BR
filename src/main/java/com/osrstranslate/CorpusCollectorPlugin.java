package com.osrstranslate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@PluginDescriptor(
    name = "OSRS Corpus Collector",
    description = "Coleta textos ainda nao cobertos pelo corpus PT-BR",
    tags = {"corpus", "translate", "portuguese", "ptbr", "collect"}
)
public class CorpusCollectorPlugin extends Plugin
{
    private static final int DIALOG_MESSAGE = 229;
    private static final int ITEM_PREVIEW = 203;
    private static final int SKILL_GUIDE = 214;
    private static final int BOOKS_NOTES = 680;
    private static final int QUEST_JOURNAL_MINIMAP = 782;
    private static final int QUEST_JOURNAL = 119;
    private static final int WELCOME_SCREEN = 378;

    private static final int[] SKILL_GUIDE_INTERFACES = {
        SKILL_GUIDE,
        860
    };

    private static final String[] TRANSLATION_RESOURCES = {
        "/com/osrstranslate/translations.json",
        "/com/osrstranslate/translations_skills.json",
        "/com/osrstranslate/translations_quests.json",
        "/com/osrstranslate/translations_items.json",
        "/com/osrstranslate/translations_menu.json",
        "/com/osrstranslate/translations_overhead.json",
        "/com/osrstranslate/translations_game_message.json",
        "/com/osrstranslate/translations_welcome.json"
    };

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private CorpusCollectorConfig config;

    private String currentNpcName = null;
    private String lastResolvedNpcName = null;
    private int conversationLevel = 0;
    private String lastOptionChosen = null;
    private boolean skillGuideCaptured = false;

    private final Set<String> existingIds = new HashSet<>();
    private final Set<String> translatedKeys = new HashSet<>();
    private final Set<String> translatedDialogueExactTexts = new HashSet<>();
    private final Set<String> translatedOutputTexts = new HashSet<>();
    private final Set<String> translatedIds = new HashSet<>();
    private final Map<String, String> translatedDialogMap = new HashMap<>();
    private List<TranslationLookupHelper.PatternEntry> translatedDialogRegex = Collections.emptyList();
    private final List<JsonObject> buffer = new ArrayList<>();

    private final Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    @Override
    protected void startUp()
    {
        loadExistingCorpus();
        loadTranslations();
        pruneCollectedCorpus();
        log.info("[CorpusCollector] Iniciado. {} IDs ja coletados, {} traducoes, {} outputTexts",
            existingIds.size(), translatedKeys.size(), translatedOutputTexts.size());
    }

    @Override
    protected void shutDown()
    {
        flush();
    }

    private void loadExistingCorpus()
    {
        File file = new File(config.outputPath());
        if (!file.exists()) return;

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))
        {
            Type type = new TypeToken<List<JsonObject>>(){}.getType();
            List<JsonObject> existing = gson.fromJson(reader, type);
            if (existing == null) return;

            for (JsonObject obj : existing)
            {
                if (obj.has("id"))
                {
                    existingIds.add(obj.get("id").getAsString());
                }
            }
        }
        catch (Exception e)
        {
            log.error("[CorpusCollector] Erro ao carregar corpus existente", e);
        }
    }

    private void loadTranslations()
    {
        translatedKeys.clear();
        translatedDialogueExactTexts.clear();
        translatedOutputTexts.clear();
        translatedIds.clear();
        translatedDialogMap.clear();
        translatedDialogRegex = Collections.emptyList();

        for (String resourcePath : TRANSLATION_RESOURCES)
        {
            loadTranslationResource(resourcePath);
        }

        log.info("[CorpusCollector] Traducoes carregadas dos JSONs: translatedKeys={}, translatedOutputTexts={}",
            translatedKeys.size(), translatedOutputTexts.size());
    }

    private void loadTranslationResource(String resourcePath)
    {
        try
        {
            Map<String, String> map = TranslationLookupHelper.loadMap(getClass(), resourcePath);
            if (map == null || map.isEmpty())
            {
                return;
            }

            boolean isDialogueResource = resourcePath.endsWith("/translations.json");
            if (isDialogueResource)
            {
                translatedDialogRegex = TranslationLookupHelper.compileRegexTranslations(map);
            }
            for (Map.Entry<String, String> entry : map.entrySet())
            {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || key.isEmpty() || value == null || value.trim().isEmpty())
                {
                    continue;
                }

                if (!isHashKey(key))
                {
                    String canonicalKey = canonicalizeForFilter(key);
                    if (!canonicalKey.isEmpty())
                    {
                        translatedKeys.add(canonicalKey);
                    }

                    String keyId = textToId(key);
                    if (keyId != null)
                    {
                        translatedKeys.add(keyId);
                        translatedIds.add(keyId);
                    }

                    if (isDialogueResource)
                    {
                        String dialogKey = normalizeLookupText(cleanText(key));
                        translatedDialogueExactTexts.add(dialogKey);
                        translatedDialogMap.put(dialogKey, value);
                        if (keyId != null)
                        {
                            translatedDialogMap.put(keyId, value);
                        }
                    }
                }
                else
                {
                    translatedKeys.add(key);
                    translatedIds.add(key);
                }

                String canonicalValue = canonicalizeForFilter(value);
                if (!canonicalValue.isEmpty())
                {
                    translatedOutputTexts.add(canonicalValue);
                }
            }
        }
        catch (Exception e)
        {
            log.warn("[CorpusCollector] Falha ao carregar recurso de traducao {}", resourcePath, e);
        }
    }

    private void pruneCollectedCorpus()
    {
        File file = new File(config.outputPath());
        if (!file.exists()) return;

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))
        {
            Type type = new TypeToken<List<JsonObject>>(){}.getType();
            List<JsonObject> existing = gson.fromJson(reader, type);
            if (existing == null || existing.isEmpty()) return;

            List<JsonObject> kept = new ArrayList<>();
            int removed = 0;

            for (JsonObject obj : existing)
            {
                if (shouldKeepCollectedEntry(obj))
                {
                    kept.add(obj);
                }
                else
                {
                    removed++;
                }
            }

            if (removed == 0) return;

            existingIds.clear();
            for (JsonObject obj : kept)
            {
                if (obj.has("id"))
                {
                    existingIds.add(obj.get("id").getAsString());
                }
            }

            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))
            {
                gson.toJson(kept, writer);
            }

            log.info("[CorpusCollector] Corpus limpo na inicializacao: -{} entradas ja cobertas por traducao/regras.",
                removed);
        }
        catch (Exception e)
        {
            log.error("[CorpusCollector] Erro ao limpar corpus coletado", e);
        }
    }

    private boolean shouldKeepCollectedEntry(JsonObject obj)
    {
        if (obj == null || !obj.has("texto_original"))
        {
            return true;
        }

        String text = normalizeLookupText(cleanText(obj.get("texto_original").getAsString()));
        if (text.isEmpty())
        {
            return false;
        }

        String kind = "";
        String canonicalText = canonicalizeForFilter(text);
        String id = obj.has("id") ? obj.get("id").getAsString() : textToId(text);

        if (isLikelyPortugueseTextSafe(text))
        {
            return false;
        }

        if (obj.has("context") && obj.get("context").isJsonObject())
        {
            JsonObject context = obj.getAsJsonObject("context");
            kind = context.has("kind") ? context.get("kind").getAsString() : "";
            if ("welcome".equals(kind) && (isDynamicWelcomeText(text) || isDynamicWelcomeTextPtBr(text)))
            {
                return false;
            }
            if ("welcome".equals(kind) && isWelcomeStaticNoise(text))
            {
                return false;
            }
        }

        return !hasKnownTranslation(text, canonicalText, id, kind);
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (isTalkToOption(event.getMenuOption()))
        {
            int npcIndex = event.getId();
            for (NPC npc : client.getNpcs())
            {
                if (npc.getIndex() == npcIndex)
                {
                    currentNpcName = npc.getName();
                    lastResolvedNpcName = currentNpcName;
                    conversationLevel = 0;
                    lastOptionChosen = null;
                    log.debug("[CorpusCollector] Conversa iniciada com: {}", currentNpcName);
                    break;
                }
            }
        }

        Widget widget = event.getMenuEntry().getWidget();
        if (widget != null && (widget.getId() >> 16) == InterfaceID.DIALOG_OPTION)
        {
            String optionText = normalizeLookupText(cleanText(widget.getText()));
            if (!optionText.isEmpty())
            {
                lastOptionChosen = optionText;
            }
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (!config.enabled()) return;

        int groupId = event.getGroupId();
        if (groupId == InterfaceID.DIALOG_NPC
            || groupId == InterfaceID.DIALOG_PLAYER
            || groupId == InterfaceID.DIALOG_OPTION
            || groupId == InterfaceID.DIALOG_SPRITE
            || groupId == InterfaceID.LEVEL_UP
            || groupId == DIALOG_MESSAGE
            || groupId == QUEST_JOURNAL_MINIMAP
            || groupId == QUEST_JOURNAL
            || groupId == ITEM_PREVIEW
            || groupId == BOOKS_NOTES
            || groupId == WELCOME_SCREEN)
        {
            conversationLevel++;
            final int interfaceId = groupId;
            clientThread.invokeLater(() ->
                clientThread.invokeLater(() -> captureInterfaceTexts(interfaceId))
            );
        }

        if (isSkillGuideInterface(groupId) && !skillGuideCaptured)
        {
            skillGuideCaptured = true;
            log.info("[CorpusCollector] Skill Guide detectado (WidgetLoaded, groupId={}), capturando textos...", groupId);
            clientThread.invokeLater(this::captureSkillGuide);
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGIN_SCREEN
            || event.getGameState() == GameState.HOPPING)
        {
            flush();
            skillGuideCaptured = false;
            currentNpcName = null;
            lastResolvedNpcName = null;
            lastOptionChosen = null;
            conversationLevel = 0;
        }
    }

    @Subscribe
    public void onOverheadTextChanged(OverheadTextChanged event)
    {
        if (!config.enabled()) return;

        Actor actor = event.getActor();
        if (!(actor instanceof NPC)) return;

        String text = normalizeLookupText(cleanText(event.getOverheadText()));
        if (text.length() <= 3 || isUiNoise(text)) return;

        NPC npc = (NPC) actor;
        String npcName = npc.getName();
        if (npcName == null || npcName.isEmpty()) return;

        if (addCollectedText(text, "InGame:" + npcName, "npc", Collections.singletonList(npcName),
            npcName, "overhead", "Overhead speech", 0, null))
        {
            log.info("[CorpusCollector] Overhead coletado [{}]: {}",
                npcName, text.substring(0, Math.min(60, text.length())));
            flush();
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!config.enabled()) return;
        if (!shouldCollectChatMessage(event.getType())) return;

        String text = normalizeLookupText(cleanText(event.getMessage()));
        if (text.isEmpty() || isUiNoise(text)) return;

        ChatCaptureSpec spec = specForChatMessage(event.getType());
        if (addCollectedText(text, spec.source, spec.type, Collections.singletonList(spec.npcName),
            spec.speaker, spec.kind, spec.section, 0, null))
        {
            log.info("[CorpusCollector] Chat coletado [{}]: {}",
                event.getType(), text.substring(0, Math.min(60, text.length())));
            flush();
        }
    }

    private void captureInterfaceTexts(int interfaceId)
    {
        InterfaceCaptureSpec spec = specForInterface(interfaceId);
        String playerName = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;

        List<CollectedText> texts = new ArrayList<>();
        for (int i = 0; i < 100; i++)
        {
            Widget widget = client.getWidget(interfaceId, i);
            if (widget != null)
            {
                collectTexts(widget, texts);
            }
        }

        log.debug("[CorpusCollector] Interface detectada: groupId={} kind={} textos={}",
            interfaceId, spec.kind, texts.size());

        if (shouldSkipTranslatedInterface(spec, texts))
        {
            log.debug("[CorpusCollector] Interface ignorada por traducao visivel: groupId={} kind={}",
                interfaceId, spec.kind);
            return;
        }

        String npcName = resolveNpcName(interfaceId, texts);

        for (CollectedText text : texts)
        {
            if (text.clean.equals(npcName)) continue;
            if (playerName != null && text.clean.equals(playerName)) continue;

            List<String> npcs = spec.includeNpcName && npcName != null && !npcName.isEmpty()
                ? Collections.singletonList(npcName)
                : spec.npcs;
            String source = spec.appendNpcToSource && npcName != null && !npcName.isEmpty()
                ? spec.sourcePrefix + npcName
                : spec.sourcePrefix;
            String speaker = "player".equals(spec.type)
                ? "Player"
                : (spec.fixedSpeaker != null ? spec.fixedSpeaker : npcName);

            if (addCollectedText(text.marked, source, spec.type, npcs, speaker, spec.kind, spec.section,
                spec.useConversationLevel ? conversationLevel : 0,
                spec.capturePrecedingOption ? lastOptionChosen : null))
            {
                log.debug("[CorpusCollector] Coletado [{}]: {}",
                    npcName, text.clean.substring(0, Math.min(60, text.clean.length())));
            }
        }

        if (!buffer.isEmpty()) flush();
    }

    private void collectTexts(Widget widget, List<CollectedText> out)
    {
        if (widget == null) return;

        String text = widget.getText();
        if (text != null && !text.isEmpty())
        {
            String clean = normalizeLookupText(cleanText(text));
            if (!clean.isEmpty() && containsLetters(clean) && !isUiNoise(clean))
            {
                out.add(new CollectedText(clean, normalizeMarkedText(text)));
            }
        }

        Widget[] children = widget.getChildren();
        if (children != null)
        {
            for (Widget child : children)
            {
                collectTexts(child, out);
            }
        }

        Widget[] dynamicChildren = widget.getDynamicChildren();
        if (dynamicChildren != null)
        {
            for (Widget child : dynamicChildren)
            {
                collectTexts(child, out);
            }
        }
    }

    private boolean isUiNoise(String text)
    {
        String lower = text.toLowerCase();
        return lower.equals("click here to continue")
            || lower.equals("please wait...")
            || lower.equals("select an option")
            || lower.startsWith("choose option");
    }

    private String cleanText(String text)
    {
        if (text == null)
        {
            return "";
        }

        return text
            .replaceAll("(?i)<br\\s*/?>", " ")
            .replaceAll("<[^>]+>", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private String normalizeLookupText(String text)
    {
        return text
            .replaceAll("\\s+([.,!?])", "$1")
            .replaceAll("([Hh])ardcore Ironmen ", "$1ardcore Ironman ")
            .replaceAll("standard Ironmen ", "standard Ironman ");
    }

    private String canonicalizeForFilter(String text)
    {
        return normalizeUnicode(normalizeLookupText(cleanText(text))).toLowerCase(Locale.ROOT);
    }

    private String normalizeUnicode(String text)
    {
        if (text == null) return "";
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    private String normalizeMarkedText(String text)
    {
        if (text == null)
        {
            return "";
        }

        return text
            .replaceAll("(?i)<br\\s*/?>", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private boolean containsLetters(String text)
    {
        for (int i = 0; i < text.length(); i++)
        {
            if (Character.isLetter(text.charAt(i)))
            {
                return true;
            }
        }
        return false;
    }

    private boolean isLikelyPortugueseText(String text)
    {
        String lower = text.toLowerCase(Locale.ROOT);

        if (lower.matches(".*[áàâãéêíóôõúç].*")) return true;

        String[] ptWords = {
            "sobre", "combate", "corpo", "estilo", "faz", "fale",
            "interessante", "gostaria", "posso", "você", "nao",
            "voce", "tambem", "alem", "então", "porque", "quando",
            "como", "pode", "onde", "qual", "quer", "bom", "mal",
            "muito", "pouco", "já", "vai", "vem", "tem", "fazer"
        };

        for (String word : ptWords)
        {
            if (lower.contains(" " + word + " ") || lower.startsWith(word + " ") || lower.endsWith(" " + word))
            {
                return true;
            }
        }

        return lower.contains(" você ")
            || lower.startsWith("você ")
            || lower.contains(" não ")
            || lower.contains(" olá")
            || lower.contains(" sauda")
            || lower.contains(" aventureiro")
            || lower.contains(" gostaria de")
            || lower.contains(" tem algo que eu possa")
            || lower.contains(" eu já ")
            || lower.contains(" mensagens não lidas")
            || lower.contains(" boas-vindas ");
    }

    private boolean isLikelyPortugueseTextSafe(String text)
    {
        String originalLower = text.toLowerCase(Locale.ROOT);
        String lower = normalizeUnicode(text).toLowerCase(Locale.ROOT);

        if (!text.equals(normalizeUnicode(text)))
        {
            return true;
        }

        String[] ptWords = {
            "sobre", "combate", "corpo", "estilo", "faz", "fale",
            "interessante", "gostaria", "posso", "voce", "nao",
            "tambem", "alem", "entao", "porque", "quando",
            "como", "pode", "onde", "qual", "quer", "bom", "mal",
            "muito", "pouco", "ja", "vai", "vem", "tem", "fazer"
        };

        for (String word : ptWords)
        {
            if (lower.contains(" " + word + " ") || lower.startsWith(word + " ") || lower.endsWith(" " + word))
            {
                return true;
            }
        }

        return lower.contains(" voce ")
            || lower.startsWith("voce ")
            || lower.contains(" nao ")
            || lower.contains(" ola")
            || lower.contains(" sauda")
            || lower.contains(" aventureiro")
            || lower.contains(" gostaria de")
            || lower.contains(" tem algo que eu possa")
            || lower.contains(" eu ja ")
            || lower.contains(" mensagens nao lidas")
            || lower.contains(" boas-vindas ")
            || originalLower.contains("ç")
            || originalLower.contains("ã")
            || originalLower.contains("á")
            || originalLower.contains("é")
            || originalLower.contains("í")
            || originalLower.contains("ó")
            || originalLower.contains("ú");
    }

    private boolean shouldSkipTranslatedInterface(InterfaceCaptureSpec spec, List<CollectedText> texts)
    {
        if (!spec.skipWhenTranslatedVisible)
        {
            return false;
        }

        for (CollectedText text : texts)
        {
            String canonical = canonicalizeForFilter(text.clean);
            if (translatedOutputTexts.contains(canonical))
            {
                return true;
            }
            String id = textToId(text.clean);
            if (id != null && translatedKeys.contains(id))
            {
                return true;
            }
        }

        return false;
    }

    private boolean isHashKey(String text)
    {
        return text.matches("^[0-9a-f]{16}$");
    }

    private String resolveNpcName(int interfaceId, List<CollectedText> texts)
    {
        if (currentNpcName != null && !currentNpcName.isEmpty())
        {
            lastResolvedNpcName = currentNpcName;
            return currentNpcName;
        }

        if (interfaceId != InterfaceID.DIALOG_NPC
            && interfaceId != InterfaceID.DIALOG_SPRITE
            && interfaceId != InterfaceID.DIALOG_PLAYER
            && interfaceId != InterfaceID.DIALOG_OPTION)
        {
            return "Unknown";
        }

        Set<String> npcNames = new HashSet<>();
        for (NPC npc : client.getNpcs())
        {
            String name = npc.getName();
            if (name != null && !name.isEmpty())
            {
                npcNames.add(normalizeLookupText(cleanText(name)));
            }
        }

        for (CollectedText text : texts)
        {
            if (npcNames.contains(text.clean))
            {
                lastResolvedNpcName = text.clean;
                return text.clean;
            }
        }

        if (lastResolvedNpcName != null && !lastResolvedNpcName.isEmpty())
        {
            return lastResolvedNpcName;
        }

        return "Unknown";
    }

    private boolean isTalkToOption(String option)
    {
        if (option == null)
        {
            return false;
        }

        String clean = normalizeLookupText(cleanText(option));
        return "Talk-to".equalsIgnoreCase(clean)
            || "Talk to".equalsIgnoreCase(clean)
            || "Fale com".equalsIgnoreCase(clean);
    }

    private String textToId(String text)
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

    private boolean addCollectedText(
        String originalText,
        String source,
        String type,
        List<String> npcs,
        String speaker,
        String kind,
        String section,
        int level,
        String precedingOption)
    {
        String text = normalizeLookupText(cleanText(originalText));
        if (text.isEmpty()) return false;
        String markedText = normalizeMarkedText(originalText);
        String canonicalText = canonicalizeForFilter(text);

        if (isLikelyPortugueseTextSafe(text))
        {
            return false;
        }

        if ("welcome".equals(kind) && (isDynamicWelcomeText(text) || isDynamicWelcomeTextPtBr(text)))
        {
            return false;
        }
        if ("welcome".equals(kind) && isWelcomeStaticNoise(text))
        {
            return false;
        }

        String id = textToId(text);
        if (id == null) return false;
        if (existingIds.contains(id)) return false;
        if (hasKnownTranslation(text, canonicalText, id, kind))
        {
            log.debug("[CorpusCollector] Ignorado (ja no dicionario): '{}' id={} kind={}", text, id, kind);
            return false;
        }

        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("texto_original", text);
        obj.addProperty("text_marcado", markedText.isEmpty() ? text : markedText);
        obj.addProperty("pt-BR", "");

        JsonObject ctx = new JsonObject();
        ctx.addProperty("source", source);
        ctx.addProperty("type", type);

        JsonArray npcArray = new JsonArray();
        for (String npc : npcs)
        {
            if (npc != null && !npc.isEmpty())
            {
                npcArray.add(npc);
            }
        }
        ctx.add("npcs", npcArray);

        ctx.addProperty("speaker", speaker != null ? speaker : "System");
        ctx.addProperty("kind", kind);
        ctx.addProperty("section", section);
        ctx.add("sub", JsonNull.INSTANCE);
        ctx.addProperty("level", level);
        ctx.add("preceding_condition", JsonNull.INSTANCE);

        if (precedingOption != null && !precedingOption.isEmpty())
        {
            ctx.addProperty("preceding_option", precedingOption);
        }
        else
        {
            ctx.add("preceding_option", JsonNull.INSTANCE);
        }

        obj.add("context", ctx);
        buffer.add(obj);
        existingIds.add(id);
        return true;
    }

    private boolean hasKnownTranslation(String text, String canonicalText, String id, String kind)
    {
        if (isDialogueKind(kind))
        {
            if (translatedDialogueExactTexts.contains(text))
            {
                return true;
            }

            return canonicalText != null && translatedOutputTexts.contains(canonicalText);
        }

        if (canonicalText != null)
        {
            if (translatedOutputTexts.contains(canonicalText) || translatedKeys.contains(canonicalText))
            {
                return true;
            }
        }

        if (id != null && translatedIds.contains(id))
        {
            return true;
        }

        return false;
    }

    private void flush()
    {
        if (buffer.isEmpty()) return;

        File file = new File(config.outputPath());
        List<JsonObject> all = new ArrayList<>();

        if (file.exists())
        {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))
            {
                Type type = new TypeToken<List<JsonObject>>(){}.getType();
                List<JsonObject> existing = gson.fromJson(reader, type);
                if (existing != null)
                {
                    all.addAll(existing);
                }
            }
            catch (Exception e)
            {
                log.error("[CorpusCollector] Erro ao ler corpus para merge", e);
            }
        }

        all.addAll(buffer);
        List<JsonObject> filtered = new ArrayList<>();
        int removed = 0;
        for (JsonObject obj : all)
        {
            if (shouldKeepCollectedEntry(obj))
            {
                filtered.add(obj);
            }
            else
            {
                removed++;
            }
        }

        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))
        {
            gson.toJson(filtered, writer);
            log.info("[CorpusCollector] Corpus salvo: +{} novos -> {} total em {}{}",
                buffer.size(), filtered.size(), file.getAbsolutePath(),
                removed > 0 ? " (filtradas " + removed + " entradas indevidas)" : "");
            buffer.clear();
        }
        catch (Exception e)
        {
            log.error("[CorpusCollector] Erro ao salvar corpus", e);
        }
    }

    @Subscribe
    public void onBeforeRender(BeforeRender event)
    {
        if (!config.enabled()) return;
        if (skillGuideCaptured) return;

        Integer visibleSkillGuideInterface = getVisibleSkillGuideInterface();
        if (visibleSkillGuideInterface != null)
        {
            skillGuideCaptured = true;
            log.info("[CorpusCollector] Skill Guide detectado (BeforeRender, groupId={}), capturando textos...",
                visibleSkillGuideInterface);
            clientThread.invokeLater(this::captureSkillGuide);
        }
    }

    private void captureSkillGuide()
    {
        Integer visibleSkillGuideInterface = getVisibleSkillGuideInterface();
        if (visibleSkillGuideInterface == null) return;

        Widget root = client.getWidget(visibleSkillGuideInterface, 0);
        if (root == null) return;

        List<CollectedText> texts = new ArrayList<>();
        collectTexts(root, texts);

        for (CollectedText text : texts)
        {
            if (addCollectedText(text.marked, "InGame:SkillGuide", "skill_guide", Collections.singletonList("SkillGuide"),
                "System", "skill_description", "Skill Guide", 0, null))
            {
                log.info("[CorpusCollector] Skill Guide coletado (groupId={}): {}", visibleSkillGuideInterface, text.clean);
            }
        }

        if (!buffer.isEmpty()) flush();
    }

    private boolean isSkillGuideInterface(int groupId)
    {
        for (int interfaceId : SKILL_GUIDE_INTERFACES)
        {
            if (interfaceId == groupId) return true;
        }
        return false;
    }

    private boolean isDynamicWelcomeText(String text)
    {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.matches("^you last logged in \\d+ .+ ago\\.$")
            || lower.matches("^you have \\d+ unread messages? in your inbox\\.$")
            || lower.matches("^you have \\d+ days? of membership remaining\\.$")
            || lower.matches("^you have \\d+ new messages? in your message centre\\.$")
            || lower.matches("^there (?:is|are) \\d+ items? waiting in your collection box\\.$")
            || lower.matches("^your account has (?:not )?set a recovery email address\\.?$")
            || lower.matches("^you have not yet set up two-factor authentication\\.?$");
    }

    private boolean isDynamicWelcomeTextPtBr(String text)
    {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.matches("^sua última sessão foi há .+\\.$")
            || lower.matches("^você tem \\d+ mensagens? não lidas na sua caixa de entrada\\.$")
            || lower.matches("^você tem \\d+ dias? de assinatura restantes\\.$")
            || lower.matches("^há \\d+ itens? esperando na sua caixa de coleta\\.$");
    }

    private boolean isWelcomeStaticNoise(String text)
    {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.equals("welcome to old school runescape")
            || lower.equals("boas-vindas ao old school runescape")
            || lower.equals("click to play")
            || lower.equals("clique para jogar")
            || lower.startsWith("you are not a member")
            || lower.startsWith("you are a member")
            || lower.startsWith("vocÃª nÃ£o Ã© membro")
            || lower.startsWith("vocÃª Ã© membro");
    }

    private Integer getVisibleSkillGuideInterface()
    {
        for (int interfaceId : SKILL_GUIDE_INTERFACES)
        {
            Widget root = client.getWidget(interfaceId, 0);
            if (root != null && !root.isHidden()) return interfaceId;
        }
        return null;
    }

    private boolean shouldCollectChatMessage(ChatMessageType type)
    {
        if (type == null)
        {
            return false;
        }

        switch (type)
        {
            case GAMEMESSAGE:
            case ENGINE:
            case SPAM:
            case MESBOX:
            case CONSOLE:
            case WELCOME:
            case DIDYOUKNOW:
            case LOGINLOGOUTNOTIFICATION:
            case ITEM_EXAMINE:
            case NPC_EXAMINE:
            case OBJECT_EXAMINE:
                return true;
            default:
                return false;
        }
    }

    private ChatCaptureSpec specForChatMessage(ChatMessageType type)
    {
        if (type == null)
        {
            return new ChatCaptureSpec("InGame:ChatMessage", "system", "GameMessage", "System",
                "game_message_chat", "Game Chat");
        }

        switch (type)
        {
            case ITEM_EXAMINE:
                return new ChatCaptureSpec("InGame:ItemExamine", "system", "ItemExamine", "System",
                    "item_examine", "Item Examine");
            case NPC_EXAMINE:
                return new ChatCaptureSpec("InGame:NpcExamine", "system", "NpcExamine", "System",
                    "npc_examine", "NPC Examine");
            case OBJECT_EXAMINE:
                return new ChatCaptureSpec("InGame:ObjectExamine", "system", "ObjectExamine", "System",
                    "object_examine", "Object Examine");
            default:
                return new ChatCaptureSpec("InGame:ChatMessage", "system", "GameMessage", "System",
                    "game_message_chat", "Game Chat");
        }
    }

    private boolean isDialogueKind(String kind)
    {
        return "npc_line".equals(kind)
            || "player_line".equals(kind)
            || "option".equals(kind)
            || "sprite".equals(kind);
    }

    private InterfaceCaptureSpec specForInterface(int interfaceId)
    {
        switch (interfaceId)
        {
            case InterfaceID.DIALOG_NPC:
                return new InterfaceCaptureSpec("npc_line", "npc", "Standard dialogue", "InGame:",
                    true, true, true, false, null, Collections.emptyList());
            case InterfaceID.DIALOG_PLAYER:
                return new InterfaceCaptureSpec("player_line", "player", "Standard dialogue", "InGame:",
                    true, true, true, false, null, Collections.emptyList());
            case InterfaceID.DIALOG_OPTION:
                return new InterfaceCaptureSpec("option", "player", "Standard dialogue", "InGame:",
                    true, true, true, false, null, Collections.emptyList());
            case InterfaceID.DIALOG_SPRITE:
                return new InterfaceCaptureSpec("sprite", "npc", "Standard dialogue", "InGame:",
                    true, true, true, false, null, Collections.emptyList());
            case InterfaceID.LEVEL_UP:
                return new InterfaceCaptureSpec("level_up", "system", "Level Up", "InGame:LevelUp",
                    false, false, false, false, "System", Collections.singletonList("LevelUp"));
            case DIALOG_MESSAGE:
                return new InterfaceCaptureSpec("game_message", "system", "Game Message", "InGame:GameMessage",
                    false, false, false, false, "System", Collections.singletonList("GameMessage"));
            case QUEST_JOURNAL:
            case QUEST_JOURNAL_MINIMAP:
                return new InterfaceCaptureSpec("quest_journal", "system", "Quest Journal", "InGame:QuestJournal",
                    false, false, false, false, "System", Collections.singletonList("QuestJournal"));
            case ITEM_PREVIEW:
                return new InterfaceCaptureSpec("item_preview", "system", "Item Preview", "InGame:ItemPreview",
                    false, false, false, false, "System", Collections.singletonList("ItemPreview"));
            case BOOKS_NOTES:
                return new InterfaceCaptureSpec("book", "system", "Book", "InGame:Books",
                    false, false, false, false, "System", Collections.singletonList("Books"));
            case WELCOME_SCREEN:
                return new InterfaceCaptureSpec("welcome", "system", "Welcome Screen", "InGame:Welcome",
                    false, false, false, false, "System", Collections.singletonList("WelcomeScreen"));
            default:
                return new InterfaceCaptureSpec("unknown", "system", "Unknown", "InGame:Unknown",
                    false, false, false, false, "System", Collections.singletonList("Unknown"));
        }
    }

    @Provides
    CorpusCollectorConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(CorpusCollectorConfig.class);
    }

    private static class InterfaceCaptureSpec
    {
        private final String kind;
        private final String type;
        private final String section;
        private final String sourcePrefix;
        private final boolean appendNpcToSource;
        private final boolean includeNpcName;
        private final boolean capturePrecedingOption;
        private final boolean skipWhenTranslatedVisible;
        private final String fixedSpeaker;
        private final List<String> npcs;
        private final boolean useConversationLevel;

        private InterfaceCaptureSpec(
            String kind,
            String type,
            String section,
            String sourcePrefix,
            boolean appendNpcToSource,
            boolean includeNpcName,
            boolean capturePrecedingOption,
            boolean skipWhenTranslatedVisible,
            String fixedSpeaker,
            List<String> npcs)
        {
            this.kind = kind;
            this.type = type;
            this.section = section;
            this.sourcePrefix = sourcePrefix;
            this.appendNpcToSource = appendNpcToSource;
            this.includeNpcName = includeNpcName;
            this.capturePrecedingOption = capturePrecedingOption;
            this.skipWhenTranslatedVisible = skipWhenTranslatedVisible;
            this.fixedSpeaker = fixedSpeaker;
            this.npcs = npcs;
            this.useConversationLevel = appendNpcToSource;
        }
    }

    private static class CollectedText
    {
        private final String clean;
        private final String marked;

        private CollectedText(String clean, String marked)
        {
            this.clean = clean;
            this.marked = marked;
        }
    }

    private static class ChatCaptureSpec
    {
        private final String source;
        private final String type;
        private final String npcName;
        private final String speaker;
        private final String kind;
        private final String section;

        private ChatCaptureSpec(String source, String type, String npcName, String speaker, String kind, String section)
        {
            this.source = source;
            this.type = type;
            this.npcName = npcName;
            this.speaker = speaker;
            this.kind = kind;
            this.section = section;
        }
    }

    /**
     * Debug: mantido para inspecoes futuras do Quest Journal.
     */
    private void logQuestJournalDebug(int interfaceId)
    {
        for (int i = 0; i < 50; i++)
        {
            Widget widget = client.getWidget(interfaceId, i);
            if (widget != null && widget.getType() == WidgetType.TEXT)
            {
                String text = widget.getText();
                if (text != null && !text.isEmpty() && text.length() > 3)
                {
                    boolean hasStr = text.contains("<str>");
                    int color = widget.getTextColor();
                    int opacity = widget.getOpacity();
                    int contentType = widget.getContentType();
                    log.info("[QuestDebug] i={} hasStr={} text='{}' color={} opacity={} contentType={}",
                        i, hasStr, text.substring(0, Math.min(30, text.length())), color, opacity, contentType);
                }
            }
        }
    }
}
