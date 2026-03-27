package com.osrstranslate;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("osrstranslate")
public interface OsrsTranslateConfig extends Config
{
    @ConfigItem(
        keyName = "enabled",
        name = "Tradução ativa",
        description = "Ativa ou desativa a tradução dos diálogos"
    )
    default boolean enabled()
    {
        return true;
    }
}
