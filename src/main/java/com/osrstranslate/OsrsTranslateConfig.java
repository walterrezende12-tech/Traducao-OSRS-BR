package com.osrstranslate;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("osrstranslate")
public interface OsrsTranslateConfig extends Config {
    @ConfigSection(
        name = "Traduções Estáticas",
        description = "Configurações de traduções para interfaces do jogo",
        position = 0
    )
    String staticTranslations = "Traduções Estáticas";

    @ConfigItem(
        keyName = "enableDialogues",
        name = "Traduzir diálogos",
        description = "Traduz diálogos de NPCs, jogadores, opções e sprites",
        section = staticTranslations,
        position = 0
    )
    default boolean enableDialogues() {
        return true;
    }

    @ConfigItem(
        keyName = "enableSkillGuide",
        name = "Traduzir Skill Guide",
        description = "Traduz textos do Skill Guide",
        section = staticTranslations,
        position = 1
    )
    default boolean enableSkillGuide() {
        return true;
    }

    @ConfigItem(
        keyName = "enableQuestJournal",
        name = "Traduzir Quest Journal",
        description = "Traduz textos do Quest Journal",
        section = staticTranslations,
        position = 2
    )
    default boolean enableQuestJournal() {
        return true;
    }

    @ConfigItem(
        keyName = "enableItems",
        name = "Traduzir livros",
        description = "Traduz textos de livros e notas no jogo",
        section = staticTranslations,
        position = 3
    )
    default boolean enableItems() {
        return true;
    }

    @ConfigItem(
        keyName = "enableMenuEntries",
        name = "Traduzir opções de menu",
        description = "Traduz opções de clique direito",
        section = staticTranslations,
        position = 4
    )
    default boolean enableMenuEntries() {
        return true;
    }

    @ConfigItem(
        keyName = "enableOverhead",
        name = "Traduzir falas acima da cabeça",
        description = "Traduz textos que aparecem acima da cabeça dos NPCs",
        section = staticTranslations,
        position = 5
    )
    default boolean enableOverhead() {
        return true;
    }

    @ConfigItem(
        keyName = "enableGameMessages",
        name = "Traduzir mensagens do jogo",
        description = "Traduz mensagens do chat do jogo",
        section = staticTranslations,
        position = 6
    )
    default boolean enableGameMessages() {
        return true;
    }
}
