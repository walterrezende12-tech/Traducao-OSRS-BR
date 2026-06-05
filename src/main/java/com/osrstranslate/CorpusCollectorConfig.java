package com.osrstranslate;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("corpuscollector")
public interface CorpusCollectorConfig extends Config
{
    @ConfigItem(
        keyName = "enabled",
        name = "Ativo",
        description = "Ativar coleta automática de diálogos"
    )
    default boolean enabled()
    {
        return true;
    }

    @ConfigItem(
        keyName = "outputPath",
        name = "Caminho de saída",
        description = "Arquivo JSON onde os diálogos coletados serão salvos"
    )
    default String outputPath()
    {
        return "D:\\Programacao\\osrs-corpus-collector\\osrs_corpus_collected.json";
    }

    @ConfigItem(
        keyName = "filterTranslated",
        name = "Filtrar já traduzidos",
        description = "Não coletar textos que já possuem tradução no plugin"
    )
    default boolean filterTranslated()
    {
        return true;
    }
}
