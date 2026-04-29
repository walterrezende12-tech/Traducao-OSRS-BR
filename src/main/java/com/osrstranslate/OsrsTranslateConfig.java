package com.osrstranslate;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("osrstranslate")
public interface OsrsTranslateConfig extends Config
{
    @ConfigItem(
        keyName = "enabled",
        name = "Traducao ativa",
        description = "Ativa ou desativa a traducao dos dialogos"
    )
    default boolean enabled()
    {
        return true;
    }
}
