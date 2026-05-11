package com.osrstranslate;

import net.runelite.api.widgets.InterfaceID;
import net.runelite.client.callback.ClientThread;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QuestHelperCompat
{
    private static final Pattern NUMBERING_PREFIX = Pattern.compile("^\\[\\d+\\]\\s+");

    private QuestHelperCompat()
    {
    }

    static void scheduleDialogTranslation(ClientThread clientThread, int interfaceId, Runnable task)
    {
        if (interfaceId == InterfaceID.DIALOG_OPTION)
        {
            clientThread.invokeLater(() -> clientThread.invokeLater(task));
        }
        else
        {
            clientThread.invokeLater(task);
        }
    }

    static String extractNumberingPrefix(String clean)
    {
        Matcher matcher = NUMBERING_PREFIX.matcher(clean);
        return matcher.find() ? matcher.group() : "";
    }

    static String stripNumberingPrefix(String clean)
    {
        return NUMBERING_PREFIX.matcher(clean).replaceFirst("");
    }
}
