package com.osrstranslate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
    name = "OSRS Translate PT-BR",
    description = "Traduz dialogos do OSRS para Portugues Brasileiro",
    tags = {"translate", "portuguese", "ptbr"}
)
public class OsrsTranslatePlugin extends Plugin
{
    private static final int[] DIALOG_INTERFACES = {
        InterfaceID.DIALOG_NPC,
        InterfaceID.DIALOG_PLAYER,
        InterfaceID.DIALOG_OPTION,
        InterfaceID.DIALOG_SPRITE,
    };

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\[[^\\]]+\\]");
    private static final Pattern HANS_PATTERN = Pattern.compile(
        "^You've spent (\\d[\\d,]*) day(?:s)?, (\\d[\\d,]*) hour(?:s)?, (\\d[\\d,]*) minute(?:s)? in the world since you arrived (\\d[\\d,]*) day(?:s)? ago\\.$"
    );
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d[\\d,]*)");

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private OsrsTranslateConfig config;

    private Map<String, String> translations;
    private final List<PatternEntry> regexTranslations = new ArrayList<>();

    private static class PatternEntry
    {
        final Pattern pattern;
        final String translation;

        PatternEntry(Pattern pattern, String translation)
        {
            this.pattern = pattern;
            this.translation = translation;
        }
    }

    @Override
    protected void startUp()
    {
        try (InputStream is = getClass().getResourceAsStream("/com/osrstranslate/translations.json"))
        {
            if (is == null)
            {
                log.error("translations.json nao encontrado!");
                return;
            }

            translations = parseJsonMap(new InputStreamReader(is, StandardCharsets.UTF_8));
            regexTranslations.clear();

            for (Map.Entry<String, String> entry : translations.entrySet())
            {
                if (entry.getKey().contains("["))
                {
                    String[] parts = PLACEHOLDER_PATTERN.split(entry.getKey(), -1);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.length; i++)
                    {
                        sb.append(Pattern.quote(parts[i]));
                        if (i < parts.length - 1)
                        {
                            sb.append("(.+?)");
                        }
                    }
                    regexTranslations.add(new PatternEntry(Pattern.compile(sb.toString()), entry.getValue()));
                }
            }

            log.info("PT-BR iniciado - {} estaticos + {} dinamicos", translations.size(), regexTranslations.size());
        }
        catch (Exception e)
        {
            log.error("Erro ao carregar traducoes", e);
        }
    }

    private Map<String, String> parseJsonMap(InputStreamReader reader)
    {
        try
        {
            JsonElement element = new JsonParser().parse(reader);
            JsonObject object = element.getAsJsonObject();
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet())
            {
                if (!result.containsKey(entry.getKey()))
                {
                    result.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            return result;
        }
        catch (Exception e)
        {
            log.error("Erro ao parsear translations.json", e);
            return new LinkedHashMap<>();
        }
    }

    @Override
    protected void shutDown()
    {
        translations = null;
        regexTranslations.clear();
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (translations == null || !config.enabled())
        {
            return;
        }

        for (int interfaceId : DIALOG_INTERFACES)
        {
            if (event.getGroupId() == interfaceId)
            {
                final int id = interfaceId;
                QuestHelperCompat.scheduleDialogTranslation(clientThread, id, () -> translateInterface(id));
                return;
            }
        }
    }

    private void translateInterface(int interfaceId)
    {
        for (int i = 0; i < 20; i++)
        {
            Widget widget = client.getWidget(interfaceId, i);
            if (widget != null)
            {
                translateWidget(widget);
            }
        }
    }

    private void translateWidget(Widget widget)
    {
        if (widget == null)
        {
            return;
        }

        String text = widget.getText();
        if (text != null && !text.isEmpty())
        {
            String clean = text
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\s+", " ")
                .trim();

            String qhPrefix = QuestHelperCompat.extractNumberingPrefix(clean);
            String lookup = qhPrefix.isEmpty() ? clean : QuestHelperCompat.stripNumberingPrefix(clean);

            if (lookup.startsWith("You've spent")
                && lookup.contains("in the world since you arrived")
                && lookup.endsWith("ago."))
            {
                String ptHans = translateHans(lookup);
                if (ptHans != null)
                {
                    widget.setText(qhPrefix + ptHans);
                    return;
                }
            }

            String pt = translations.get(lookup);
            if (pt == null)
            {
                for (PatternEntry entry : regexTranslations)
                {
                    Matcher matcher = entry.pattern.matcher(lookup);
                    if (matcher.matches())
                    {
                        String result = entry.translation;
                        for (int i = 1; i <= matcher.groupCount(); i++)
                        {
                            result = result.replaceFirst("\\[[^\\]]+\\]", Matcher.quoteReplacement(matcher.group(i)));
                        }
                        pt = result;
                        break;
                    }
                }
            }

            if (pt != null)
            {
                widget.setText(qhPrefix + pt);
                return;
            }
        }

        Widget[] children = widget.getChildren();
        if (children != null)
        {
            for (Widget child : children)
            {
                translateWidget(child);
            }
        }

        Widget[] dynamicChildren = widget.getDynamicChildren();
        if (dynamicChildren != null)
        {
            for (Widget child : dynamicChildren)
            {
                translateWidget(child);
            }
        }
    }

    private String translateHans(String clean)
    {
        Matcher matcher = HANS_PATTERN.matcher(clean);
        if (matcher.matches())
        {
            String diasNoMundo = matcher.group(1);
            String horasNoMundo = matcher.group(2);
            String minutosNoMundo = matcher.group(3);
            String diasDesdeChegada = matcher.group(4);

            return "Voce passou " + diasNoMundo + " dias, "
                + horasNoMundo + " horas, " + minutosNoMundo
                + " minutos no mundo desde que chegou "
                + diasDesdeChegada + " dias atras.";
        }

        Matcher fallback = NUMBER_PATTERN.matcher(clean);
        String diasNoMundo = "";
        String horasNoMundo = "";
        String minutosNoMundo = "";
        String diasDesdeChegada = "";
        if (fallback.find())
        {
            diasNoMundo = fallback.group(1);
        }
        if (fallback.find())
        {
            horasNoMundo = fallback.group(1);
        }
        if (fallback.find())
        {
            minutosNoMundo = fallback.group(1);
        }
        if (fallback.find())
        {
            diasDesdeChegada = fallback.group(1);
        }

        return "Voce passou " + diasNoMundo + " dias, "
            + horasNoMundo + " horas, " + minutosNoMundo
            + " minutos no mundo desde que chegou "
            + diasDesdeChegada + " dias atras.";
    }

    @Provides
    OsrsTranslateConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(OsrsTranslateConfig.class);
    }
}
