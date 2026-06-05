package com.osrstranslate;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class CorpusCollectorTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(
            CorpusCollectorPlugin.class,
            OsrsTranslatePlugin.class
        );
        RuneLite.main(args);
    }
}
