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

        if (!"Examine".equalsIgnoreCase(englishOption))
        {
            return;
        }

        entry.setOption("Examine");
    }
}
