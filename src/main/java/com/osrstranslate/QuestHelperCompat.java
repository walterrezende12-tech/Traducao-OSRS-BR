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

    // Agenda a traducao para rodar depois do destaque do Quest Helper.
    //
    // Para DIALOG_OPTION (grupo 219, a unica interface destacada pelo QH) encadeamos
    // invokeLater: o outer adiciona o inner ao final da fila durante o drain, entao
    // o inner roda depois de tudo que ja estava na fila — incluindo o highlightChoice
    // do QH. Funciona independente da ordem de registro dos plugins.
    //
    // Demais interfaces (NPC/PLAYER/SPRITE): o QH nao mexe nelas, um invokeLater
    // simples basta.
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

    // Retorna o prefixo "[N] " que o QH adiciona a uma opcao destacada, ou "" se nao houver.
    static String extractNumberingPrefix(String clean)
    {
        Matcher m = NUMBERING_PREFIX.matcher(clean);
        return m.find() ? m.group() : "";
    }

    // Retorna o texto sem o prefixo "[N] " do QH no inicio, se presente.
    static String stripNumberingPrefix(String clean)
    {
        return NUMBERING_PREFIX.matcher(clean).replaceFirst("");
    }
}
