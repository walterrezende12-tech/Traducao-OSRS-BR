package com.osrstranslate;

import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;

final class ContextualCursorOverlay extends Overlay
{
    private static final int MENU_OPTION_HEIGHT = 15;
    private static final int MENU_EXTRA_TOP = 4;

    private final Client client;
    private final OsrsTranslatePlugin plugin;
    private final ContextualCursorBridge contextualCursorBridge = new ContextualCursorBridge();

    @Inject
    private ContextualCursorOverlay(Client client, OsrsTranslatePlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPriority(2f);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!plugin.isContextualCursorCompatEnabled())
        {
            return null;
        }

        if (!contextualCursorBridge.isAvailable(plugin.getPluginManager()))
        {
            return null;
        }

        MenuEntry entry = getActiveMenuEntry();
        if (entry == null)
        {
            return null;
        }

        String englishOption = plugin.reverseTranslateMenuOption(entry.getOption());
        if (englishOption == null)
        {
            return null;
        }

        Object sprite = contextualCursorBridge.resolveSprite(plugin.getPluginManager(), entry, englishOption);
        if (sprite == null)
        {
            return null;
        }

        contextualCursorBridge.draw(graphics, client, plugin.getSpriteManager(), sprite);
        return null;
    }

    private MenuEntry getActiveMenuEntry()
    {
        if (client.isMenuOpen())
        {
            return getHoveredMenuEntry(client.getMenu());
        }

        MenuEntry[] menuEntries = client.getMenu().getMenuEntries();
        int last = menuEntries.length - 1;
        return last < 0 ? null : menuEntries[last];
    }

    private MenuEntry getHoveredMenuEntry(Menu menu)
    {
        if (isCursorOutsideMenu(menu))
        {
            return null;
        }

        MenuEntry[] menuEntries = menu.getMenuEntries();
        int fromTop = (client.getMouseCanvasPosition().getY() - MENU_EXTRA_TOP) - menu.getMenuY();
        int index = menuEntries.length - (fromTop / MENU_OPTION_HEIGHT);
        if (index < 0 || index >= menuEntries.length)
        {
            return null;
        }

        return menuEntries[index];
    }

    private boolean isCursorOutsideMenu(Menu menu)
    {
        return menu.getMenuX() > client.getMouseCanvasPosition().getX()
            || menu.getMenuX() + menu.getMenuWidth() < client.getMouseCanvasPosition().getX();
    }
}
