package com.osrstranslate;

import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;

final class ExaminePluginBridge
{
    void restoreEnglishOption(MenuOptionClicked event, OsrsTranslatePlugin plugin)
    {
        if (event == null || plugin == null || !plugin.isMenuTranslationEnabled())
        {
            return;
        }

        MenuEntry entry = event.getMenuEntry();
        if (entry == null)
        {
            return;
        }

        String originalOption = entry.getOption();
        String englishOption = plugin.reverseTranslateMenuOption(originalOption);

        if (englishOption == null)
        {
            return;
        }

        // The visible option may be translated, but RuneLite integrations and
        // plugin callbacks consume the canonical English menu option.
        entry.setOption(englishOption);
    }
}
