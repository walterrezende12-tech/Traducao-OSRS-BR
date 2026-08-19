package com.osrstranslate;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ContainerEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class OsrsTranslateConfigLocalization {
    private static final Map<String, UiText> TEXTS = buildTexts();
    private static final AWTEventListener CONFIG_PANEL_LISTENER =
        OsrsTranslateConfigLocalization::onAwtEvent;
    private static volatile OsrsTranslateConfig.TranslationLanguage selectedLanguage;
    private static boolean listenerInstalled;
    private static boolean localizationPending;

    private OsrsTranslateConfigLocalization() {
    }

    static synchronized void start(OsrsTranslateConfig.TranslationLanguage language) {
        selectedLanguage = language;
        if (!listenerInstalled) {
            Toolkit.getDefaultToolkit().addAWTEventListener(
                CONFIG_PANEL_LISTENER,
                AWTEvent.CONTAINER_EVENT_MASK
            );
            listenerInstalled = true;
        }
        localize(language);
    }

    static synchronized void stop() {
        if (listenerInstalled) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(CONFIG_PANEL_LISTENER);
            listenerInstalled = false;
        }
        selectedLanguage = null;
    }

    static void localize(OsrsTranslateConfig.TranslationLanguage language) {
        selectedLanguage = language;
        if (SwingUtilities.isEventDispatchThread()) {
            localizeNow(language);
            return;
        }

        SwingUtilities.invokeLater(() -> localizeNow(language));
    }

    private static void localizeNow(OsrsTranslateConfig.TranslationLanguage language) {
        for (Window window : Window.getWindows()) {
            Container configPanel = findConfigPanel(window);
            if (configPanel != null && isTranslationConfigPanel(configPanel)) {
                localizeComponentTree(configPanel, language);
            }
        }
    }

    private static void onAwtEvent(AWTEvent event) {
        if (!(event instanceof ContainerEvent)
            || event.getID() != ContainerEvent.COMPONENT_ADDED) {
            return;
        }

        ContainerEvent containerEvent = (ContainerEvent) event;
        if (findConfigPanelAncestor(containerEvent.getContainer()) == null
            && findConfigPanel(containerEvent.getChild()) == null) {
            return;
        }

        scheduleLocalization();
    }

    private static void scheduleLocalization() {
        if (localizationPending) {
            return;
        }

        localizationPending = true;
        SwingUtilities.invokeLater(() -> {
            localizationPending = false;
            OsrsTranslateConfig.TranslationLanguage language = selectedLanguage;
            if (language != null) {
                localizeNow(language);
            }
        });
    }

    private static Container findConfigPanelAncestor(Component component) {
        Component current = component;
        while (current != null) {
            if (current instanceof Container
                && current.getClass().getName().endsWith("plugins.config.ConfigPanel")) {
                return (Container) current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static Container findConfigPanel(Component component) {
        if (component instanceof Container
            && component.getClass().getName().endsWith("plugins.config.ConfigPanel")) {
            return (Container) component;
        }

        if (!(component instanceof Container)) {
            return null;
        }

        for (Component child : ((Container) component).getComponents()) {
            Container configPanel = findConfigPanel(child);
            if (configPanel != null) {
                return configPanel;
            }
        }
        return null;
    }

    static boolean isTranslationConfigPanel(Component component) {
        if (component instanceof JComboBox) {
            JComboBox<?> comboBox = (JComboBox<?>) component;
            for (int index = 0; index < comboBox.getItemCount(); index++) {
                if (comboBox.getItemAt(index) instanceof OsrsTranslateConfig.TranslationLanguage) {
                    return true;
                }
            }
        }

        if (!(component instanceof Container)) {
            return false;
        }

        for (Component child : ((Container) component).getComponents()) {
            if (isTranslationConfigPanel(child)) {
                return true;
            }
        }
        return false;
    }

    static void localizeComponentTree(
        Component component,
        OsrsTranslateConfig.TranslationLanguage language
    ) {
        localizeComponent(component, language);
        if (!(component instanceof Container)) {
            return;
        }

        for (Component child : ((Container) component).getComponents()) {
            localizeComponentTree(child, language);
        }
    }

    private static void localizeComponent(
        Component component,
        OsrsTranslateConfig.TranslationLanguage language
    ) {
        String text = null;
        String localizedText = null;
        if (component instanceof JLabel) {
            text = ((JLabel) component).getText();
        } else if (component instanceof AbstractButton) {
            text = ((AbstractButton) component).getText();
        }

        if (text != null) {
            UiText uiText = TEXTS.get(text);
            if (uiText != null) {
                localizedText = uiText.forLanguage(language);
                if (component instanceof JLabel) {
                    ((JLabel) component).setText(localizedText);
                } else {
                    ((AbstractButton) component).setText(localizedText);
                }
            }
        }

        if (component instanceof JComponent) {
            JComponent swingComponent = (JComponent) component;
            String tooltip = swingComponent.getToolTipText();
            if (tooltip != null) {
                String localizedTooltip = localizeTooltip(tooltip, localizedText, language);
                if (localizedTooltip != null) {
                    swingComponent.setToolTipText(localizedTooltip);
                }
            }
        }
    }

    private static String localizeTooltip(
        String tooltip,
        String localizedTitle,
        OsrsTranslateConfig.TranslationLanguage language
    ) {
        UiText directText = TEXTS.get(tooltip);
        if (directText != null) {
            return directText.forLanguage(language);
        }

        int separator = tooltip.indexOf(": ");
        if (separator >= 0) {
            UiText title = TEXTS.get(tooltip.substring(0, separator));
            UiText description = TEXTS.get(tooltip.substring(separator + 2));
            if (title != null && description != null) {
                return title.forLanguage(language) + ": " + description.forLanguage(language);
            }
        }

        List<String> variants = new ArrayList<>(TEXTS.keySet());
        variants.sort((left, right) -> Integer.compare(right.length(), left.length()));
        String localizedTooltip = tooltip;
        boolean changed = false;
        for (String variant : variants) {
            UiText variantText = TEXTS.get(variant);
            String localizedVariant = variantText.forLanguage(language);
            if (localizedTooltip.contains(variant) && !variant.equals(localizedVariant)) {
                localizedTooltip = localizedTooltip.replace(variant, localizedVariant);
                changed = true;
            }
        }

        if (changed && localizedTitle != null) {
            int localizedSeparator = localizedTooltip.indexOf(": ");
            if (localizedSeparator >= 0) {
                localizedTooltip = localizedTitle + localizedTooltip.substring(localizedSeparator);
            }
        }

        return changed ? localizedTooltip : null;
    }

    private static Map<String, UiText> buildTexts() {
        Map<String, UiText> texts = new HashMap<>();
        add(texts, "Idioma", "Idioma", "Idioma");
        add(texts, "Seleciona o idioma das traduções", "Seleciona o idioma das traduções", "Selecciona el idioma de las traducciones");
        add(texts, "Seleciona o idioma baixado do repositório oficial", "Seleciona o idioma baixado do repositório oficial", "Selecciona el idioma descargado del repositorio oficial");
        add(texts, "Traduções Estáticas", "Traduções Estáticas", "Traducciones estáticas");
        add(texts, "Configurações de traduções para interfaces do jogo", "Configurações de traduções para interfaces do jogo", "Configuración de traducciones para las interfaces del juego");
        add(texts, "Traduzir diálogos", "Traduzir diálogos", "Traducir diálogos");
        add(texts, "Traduz diálogos de NPCs, opções, sprites e ações", "Traduz diálogos de NPCs, opções, sprites e ações", "Traduce diálogos de NPC, opciones, sprites y acciones");
        add(texts, "Traduzir Skill Guide", "Traduzir Skill Guide", "Traducir guía de habilidades");
        add(texts, "Traduz textos do Skill Guide", "Traduz textos do Skill Guide", "Traduce textos de la guía de habilidades");
        add(texts, "Traduzir Quest Journal", "Traduzir Quest Journal", "Traducir diario de misiones");
        add(texts, "Traduz textos do Quest Journal", "Traduz textos do Quest Journal", "Traduce textos del diario de misiones");
        add(texts, "Traduzir livros", "Traduzir livros", "Traducir libros");
        add(texts, "Traduz textos de livros e notas no jogo", "Traduz textos de livros e notas no jogo", "Traduce textos de libros y notas del juego");
        add(texts, "Traduzir opções de menu", "Traduzir opções de menu", "Traducir opciones de menú");
        add(texts, "Traduz opções de menu dos npcs, objetos e clique direito", "Traduz opções de menu dos npcs, objetos e clique direito", "Traduce opciones de menú de NPC, objetos y clic derecho");
        add(texts, "Traduzir falas acima da cabeça", "Traduzir falas acima da cabeça", "Traducir textos sobre la cabeza");
        add(texts, "Traduz textos que aparecem acima da cabeça dos NPCs", "Traduz textos que aparecem acima da cabeça dos NPCs", "Traduce textos que aparecen sobre la cabeza de los NPC");
        add(texts, "Traduzir mensagens do jogo", "Traduzir mensagens do jogo", "Traducir mensajes del juego");
        add(texts, "Traduz mensagens do jogo como examinar, ações que aparecem no chat do jogo", "Traduz mensagens do jogo como examinar, ações que aparecem no chat do jogo", "Traduce mensajes del juego, como examinar y acciones que aparecen en el chat");
        add(texts, "Traduzir boas-vindas", "Traduzir boas-vindas", "Traducir bienvenida");
        add(texts, "Traduz a tela e mensagens de boas-vindas/login", "Traduz a tela e mensagens de boas-vindas/login", "Traduce la pantalla y los mensajes de bienvenida/inicio de sesión");
        add(texts, "Traduzir Settings", "Traduzir Settings", "Traducir configuración");
        add(texts, "Traduz textos da interface de configuracoes", "Traduz textos da interface de configuracoes", "Traduce los textos de la interfaz de configuración");
        add(texts, "Correções Visuais", "Correções Visuais", "Correcciones visuales");
        add(texts, "Ajustes de layout e apresentação", "Ajustes de layout e apresentação", "Ajustes de diseño y presentación");
        add(texts, "Espaçamento entre linhas", "Espaçamento entre linhas", "Espaciado entre líneas");
        add(texts, "Ajusta automaticamente a altura das linhas quando o texto traduzido é maior", "Ajusta automaticamente a altura das linhas quando o texto traduzido é maior", "Ajusta automáticamente la altura de las líneas cuando el texto traducido es más largo");
        add(texts, "Reset", "Redefinir", "Restablecer");
        add(texts, "Back", "Voltar", "Atrás");
        add(texts, "Expand", "Expandir", "Expandir");
        add(texts, "Retract", "Recolher", "Contraer");
        return texts;
    }

    private static void add(Map<String, UiText> texts, String key, String portuguese, String spanish) {
        UiText uiText = new UiText(portuguese, spanish);
        texts.put(key, uiText);
        texts.put(portuguese, uiText);
        texts.put(spanish, uiText);
    }

    private static final class UiText {
        private final String portuguese;
        private final String spanish;

        private UiText(String portuguese, String spanish) {
            this.portuguese = portuguese;
            this.spanish = spanish;
        }

        private String forLanguage(OsrsTranslateConfig.TranslationLanguage language) {
            return language == OsrsTranslateConfig.TranslationLanguage.SPANISH
                ? spanish
                : portuguese;
        }
    }
}
