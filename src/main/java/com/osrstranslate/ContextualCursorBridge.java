package com.osrstranslate;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Slf4j
final class ContextualCursorBridge
{
    private static final String PLUGIN_CLASS_NAME = "io.hydrox.contextualcursor.ContextualCursorPlugin";
    private static final String CURSOR_CLASS_NAME = "io.hydrox.contextualcursor.ContextualCursor";
    private static final String SPRITE_CLASS_NAME = "com.github.ldavid432.contextualcursor.sprite.Sprite";
    private static final int POINTER_OFFSET_X = -5;
    private static final int POINTER_OFFSET_Y = 0;
    private static final int FULL_POINTER_OFFSET_X = -3;
    private static final int FULL_POINTER_OFFSET_Y = 0;
    private static final int CENTRAL_POINT_X = 16;
    private static final int CENTRAL_POINT_Y = 18;

    private boolean unavailable;
    private Method contextualCursorGetMethod;
    private Method spriteGetTypeMethod;
    private Method spriteGetImageMethod;
    private Object blankCursorSprite;
    private Plugin contextualCursorPlugin;

    boolean isAvailable(PluginManager pluginManager)
    {
        return resolve(pluginManager) != null;
    }

    Object resolveSprite(PluginManager pluginManager, MenuEntry menuEntry, String englishOption)
    {
        Plugin plugin = resolve(pluginManager);
        if (plugin == null || englishOption == null || englishOption.isEmpty())
        {
            return null;
        }

        String originalOption = menuEntry.getOption();
        try
        {
            menuEntry.setOption(englishOption);
            return contextualCursorGetMethod.invoke(null, menuEntry);
        }
        catch (Exception e)
        {
            unavailable = true;
            log.warn("Falha ao consultar sprite do Contextual Cursor", e);
            return null;
        }
        finally
        {
            menuEntry.setOption(originalOption);
        }
    }

    void draw(Graphics2D graphics, Client client, SpriteManager spriteManager, Object sprite)
    {
        if (sprite == null || contextualCursorPlugin == null)
        {
            return;
        }

        try
        {
            BufferedImage image = (BufferedImage) spriteGetImageMethod.invoke(
                sprite,
                client,
                spriteManager,
                contextualCursorPlugin
            );
            if (image == null)
            {
                return;
            }

            String type = String.valueOf(spriteGetTypeMethod.invoke(sprite));
            net.runelite.api.Point mousePos = client.getMouseCanvasPosition();

            switch (type)
            {
                case "CONTEXTUAL_FULL":
                    graphics.drawImage(image, mousePos.getX() + FULL_POINTER_OFFSET_X, mousePos.getY() + FULL_POINTER_OFFSET_Y, null);
                    break;
                case "CONTEXTUAL":
                    BufferedImage blank = (BufferedImage) spriteGetImageMethod.invoke(
                        blankCursorSprite,
                        client,
                        spriteManager,
                        contextualCursorPlugin
                    );
                    if (blank != null)
                    {
                        graphics.drawImage(blank, mousePos.getX() + POINTER_OFFSET_X, mousePos.getY() + POINTER_OFFSET_Y, null);
                    }

                    int spriteX = POINTER_OFFSET_X + CENTRAL_POINT_X - image.getWidth() / 2;
                    int spriteY = POINTER_OFFSET_Y + CENTRAL_POINT_Y - image.getHeight() / 2;
                    graphics.drawImage(image, mousePos.getX() + spriteX, mousePos.getY() + spriteY, null);
                    break;
                case "DEFAULT":
                    graphics.drawImage(image, mousePos.getX(), mousePos.getY(), null);
                    break;
                default:
                    break;
            }
        }
        catch (Exception e)
        {
            unavailable = true;
            log.warn("Falha ao desenhar sprite do Contextual Cursor", e);
        }
    }

    private Plugin resolve(PluginManager pluginManager)
    {
        if (unavailable)
        {
            return null;
        }

        if (contextualCursorPlugin != null && pluginManager.isPluginActive(contextualCursorPlugin))
        {
            return contextualCursorPlugin;
        }

        for (Plugin plugin : pluginManager.getPlugins())
        {
            if (!PLUGIN_CLASS_NAME.equals(plugin.getClass().getName()) || !pluginManager.isPluginActive(plugin))
            {
                continue;
            }

            try
            {
                contextualCursorPlugin = plugin;
                ClassLoader classLoader = plugin.getClass().getClassLoader();
                Class<?> contextualCursorClass = Class.forName(CURSOR_CLASS_NAME, true, classLoader);
                Class<?> spriteClass = Class.forName(SPRITE_CLASS_NAME, true, classLoader);

                contextualCursorGetMethod = contextualCursorClass.getMethod("get", MenuEntry.class);
                spriteGetTypeMethod = spriteClass.getMethod("getType");
                spriteGetImageMethod = spriteClass.getMethod("getImage", Client.class, net.runelite.client.game.SpriteManager.class, plugin.getClass());

                Field blankCursorField = contextualCursorClass.getDeclaredField("BLANK_CURSOR");
                blankCursorField.setAccessible(true);
                blankCursorSprite = blankCursorField.get(null);
                return contextualCursorPlugin;
            }
            catch (Exception e)
            {
                unavailable = true;
                log.warn("Falha ao inicializar ponte com Contextual Cursor", e);
                return null;
            }
        }

        return null;
    }
}
