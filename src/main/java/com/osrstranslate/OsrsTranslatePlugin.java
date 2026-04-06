package com.osrstranslate;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@PluginDescriptor(
    name = "Tradução OSRS BR",
    description = "Traduz diálogos do OSRS para Português Brasileiro",
    tags = {"translate", "portuguese", "ptbr", "dialogo", "traducao"}
)
public class OsrsTranslatePlugin extends Plugin
{
    // IDs das interfaces de diálogo do OSRS
    private static final int[] DIALOG_INTERFACES = {
        InterfaceID.DIALOG_NPC,       // 231 — fala de NPC
        InterfaceID.DIALOG_PLAYER,    // 217 — fala do Player
        InterfaceID.DIALOG_OPTION,    // 219 — opções de diálogo
        InterfaceID.DIALOG_SPRITE,    // 193 — caixas com item/narrativa
        229,                          // DIALOG_MESSAGE — mensagens do sistema
    };

    @Inject
    private Client client;

    @Inject
    private Gson gson;

    @Inject
    private OsrsTranslateConfig config;

    private Map<String, String> translations;

    @Override
    protected void startUp()
    {
        loadTranslations();
        log.info("Tradução OSRS BR iniciado — {} traduções carregadas", translations.size());
    }

    @Override
    protected void shutDown()
    {
        translations = null;
        log.info("Tradução OSRS BR encerrado");
    }

    private void loadTranslations()
    {
        try (InputStream is = getClass().getResourceAsStream("/com/osrstranslate/translations.json"))
        {
            if (is == null)
            {
                log.error("translations.json não encontrado nos recursos do plugin");
                return;
            }
            InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<String, String>>(){}.getType();
            translations = gson.fromJson(reader, type);
        }
        catch (Exception e)
        {
            log.error("Erro ao carregar traduções", e);
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (translations == null || !config.enabled()) return;

        for (int interfaceId : DIALOG_INTERFACES)
        {
            if (event.getGroupId() == interfaceId)
            {
                translateInterface(interfaceId);
                return;
            }
        }
    }

    private void translateInterface(int interfaceId)
    {
        Widget root = client.getWidget(interfaceId, 0);
        if (root == null) return;
        translateWidget(root);
    }

    private void translateWidget(Widget widget)
    {
        if (widget == null) return;

        // Traduz texto do widget atual
        String text = widget.getText();
        if (text != null && !text.isEmpty())
        {
            String translated = translate(text);
            if (translated != null)
            {
                widget.setText(translated);
            }
        }

        // Traduz widgets filhos recursivamente
        Widget[] children = widget.getChildren();
        if (children != null)
        {
            for (Widget child : children)
            {
                translateWidget(child);
            }
        }

        // Traduz widgets dinâmicos
        Widget[] dynamicChildren = widget.getDynamicChildren();
        if (dynamicChildren != null)
        {
            for (Widget child : dynamicChildren)
            {
                translateWidget(child);
            }
        }
    }

    private String translate(String text)
    {
        if (translations == null) return null;

        // Tentativa 1: match exato
        String pt = translations.get(text);
        if (pt != null) return pt;

        // Tentativa 2: substitui <br> por espaço antes de remover demais tags HTML
        String clean = text
            .replaceAll("(?i)<br\\s*/?>", " ")
            .replaceAll("<[^>]+>", "")
            .replaceAll("\\s+", " ")
            .trim();
        pt = translations.get(clean);
        if (pt != null) return pt;

        return null;
    }

    @Provides
    OsrsTranslateConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(OsrsTranslateConfig.class);
    }
}
