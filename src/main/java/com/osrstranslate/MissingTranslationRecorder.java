package com.osrstranslate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Salva em um arquivo local os textos que o jogo exibiu e que não estão no dicionário,
 * para que possam ser traduzidos depois. Uma linha JSON por texto, sem repetições
 * (inclusive entre sessões).
 *
 * Arquivo: ~/.runelite/osrs-translate/sem-traducao/<idioma>.jsonl
 * Nada é enviado pela internet.
 */
@Slf4j
final class MissingTranslationRecorder
{
    private static final int MAX_PER_SESSION = 20000;
    private static final int MAX_TEXT_LENGTH = 2000;
    /** Pelo menos uma palavra de 3 letras: descarta números, nomes curtos, ícones etc. */
    private static final Pattern HAS_WORD = Pattern.compile("[A-Za-z]{3,}");
    /** Só números, sinais e unidades ("1,234", "50%", "x3", "12:30"). */
    private static final Pattern ONLY_NUMBERS = Pattern.compile("^[\\d\\s.,:;/%()+\\-x*#kmKM]+$");

    private final File file;
    private final Gson gson;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "osrs-translate-missing-recorder");
        t.setDaemon(true);
        return t;
    });
    private volatile int recordedThisSession;

    MissingTranslationRecorder(File directory, String languageFolder, Gson gson)
    {
        this.file = new File(directory, languageFolder + ".jsonl");
        this.gson = gson;
        writer.execute(this::loadExisting);
    }

    File getFile()
    {
        return file;
    }

    /**
     * @param fileName dicionário onde o texto deveria estar (ex.: translations.json)
     * @param source   de onde veio: interface, overhead ou tipo de mensagem do chat
     * @param text     texto em inglês, exatamente como foi procurado no dicionário
     */
    void record(String fileName, String source, String text)
    {
        if (text == null || fileName == null)
        {
            return;
        }
        String clean = text.trim();
        if (clean.length() < 4 || clean.length() > MAX_TEXT_LENGTH
            || !HAS_WORD.matcher(clean).find() || ONLY_NUMBERS.matcher(clean).matches())
        {
            return;
        }
        if (recordedThisSession >= MAX_PER_SESSION || !seen.add(fileName + '\u0000' + clean))
        {
            return;
        }
        recordedThisSession++;

        JsonObject line = new JsonObject();
        line.addProperty("arquivo", fileName);
        line.addProperty("origem", source);
        line.addProperty("texto", clean);
        line.addProperty("data", Instant.now().toString());
        String json = gson.toJson(line);
        writer.execute(() -> append(json));
    }

    void shutDown()
    {
        writer.shutdown();
        try
        {
            writer.awaitTermination(2, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void loadExisting()
    {
        if (!file.isFile())
        {
            return;
        }
        try (BufferedReader in = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = in.readLine()) != null)
            {
                try
                {
                    JsonObject o = gson.fromJson(line, JsonObject.class);
                    seen.add(o.get("arquivo").getAsString() + '\u0000' + o.get("texto").getAsString());
                }
                catch (Exception ignored)
                {
                    // linha corrompida: ignorada
                }
            }
        }
        catch (IOException e)
        {
            log.warn("Nao foi possivel ler o registro de textos sem traducao: {}", file, e);
        }
    }

    private void append(String json)
    {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
        {
            log.warn("Nao foi possivel criar a pasta {}", parent);
            return;
        }
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))
        {
            out.write(json);
            out.write('\n');
        }
        catch (IOException e)
        {
            log.warn("Nao foi possivel escrever em {}", file, e);
        }
    }
}
