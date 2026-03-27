package com.osrstranslate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.NpcComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plugin utilitario: exporta NPCs e Itens do cache do cliente para pipeline2/game_cache/.
 *
 * Como usar:
 *   1. Habilite este plugin no RuneLite
 *   2. Faca login no jogo (qualquer mundo)
 *   3. Arquivos gerados em pipeline2/game_cache/:
 *        npcs_f2p.json    — nomes de NPCs F2P (lista de strings)
 *        npcs_p2p.json    — nomes de NPCs P2P/Members (lista de strings)
 *        npcs_all.json    — todos os NPCs com detalhes (id, name, members, combat)
 *        items_f2p.json   — nomes de Itens F2P (lista de strings)
 *        items_p2p.json   — nomes de Itens P2P/Members (lista de strings)
 *        items_all.json   — todos os Itens com detalhes (id, name, members, noted, stackable)
 *   4. Desabilite o plugin apos exportar
 *
 * Os arquivos *_f2p.json e *_p2p.json tem o mesmo formato do .nouns_cache:
 *   ["Nome 1", "Nome 2", ...]
 *
 * Use 00b_filter_cache.py para filtrar e mesclar com proper_nouns.json
 */
@Slf4j
@PluginDescriptor(
    name = "Cache Exporter (NPCs + Items)",
    description = "Utilitario: exporta NPCs e Itens do cache para pipeline2/game_cache/",
    tags = {"util", "export", "cache", "npc", "item"},
    enabledByDefault = false
)
public class F2PNpcExporterPlugin extends Plugin
{
    private static final int MAX_NPC_ID  = 15000;
    private static final int MAX_ITEM_ID = 30000;

    @Inject
    private Client client;

    private boolean exported = false;

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN && !exported)
        {
            Path cacheDir = resolveGameCacheDir();
            exportNpcs(cacheDir);
            exportItems(cacheDir);
            exported = true;
            notificar("Exportacao concluida → pipeline2/game_cache/");
        }
    }

    @Override
    protected void shutDown()
    {
        exported = false;
    }

    // ── NPCs ────────────────────────────────────────────────────

    private void exportNpcs(Path cacheDir)
    {
        log.info("CacheExporter: exportando NPCs...");

        List<Map<String, Object>> todos  = new ArrayList<>();
        Set<String> nomes_f2p = new LinkedHashSet<>();
        Set<String> nomes_p2p = new LinkedHashSet<>();

        for (int id = 0; id < MAX_NPC_ID; id++)
        {
            NpcComposition npc = client.getNpcDefinition(id);
            if (npc == null) continue;

            String nome = npc.getName();
            if (nome == null || nome.equals("null") || nome.trim().isEmpty()) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",           id);
            entry.put("name",         nome);
            entry.put("members",      npc.isMembers());
            entry.put("combat_level", npc.getCombatLevel());
            todos.add(entry);

            if (npc.isMembers()) nomes_p2p.add(nome);
            else                 nomes_f2p.add(nome);
        }

        // Lista simples (formato .nouns_cache)
        salvar(cacheDir.resolve("npcs_f2p.json"),  new ArrayList<>(nomes_f2p));
        salvar(cacheDir.resolve("npcs_p2p.json"),  new ArrayList<>(nomes_p2p));
        // Detalhe completo
        salvar(cacheDir.resolve("npcs_all.json"),  todos);

        log.info("CacheExporter: NPCs → f2p={} p2p={} total={}", nomes_f2p.size(), nomes_p2p.size(), todos.size());
        notificar("NPCs exportados: " + nomes_f2p.size() + " F2P, " + nomes_p2p.size() + " P2P");
    }

    // ── Itens ───────────────────────────────────────────────────

    private void exportItems(Path cacheDir)
    {
        log.info("CacheExporter: exportando Itens...");

        List<Map<String, Object>> todos  = new ArrayList<>();
        Set<String> nomes_f2p = new LinkedHashSet<>();
        Set<String> nomes_p2p = new LinkedHashSet<>();

        for (int id = 0; id < MAX_ITEM_ID; id++)
        {
            ItemComposition item = client.getItemDefinition(id);
            if (item == null) continue;

            String nome = item.getName();
            if (nome == null || nome.equals("null") || nome.trim().isEmpty()) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",        id);
            entry.put("name",      nome);
            entry.put("members",   item.isMembers());
            entry.put("noted",     item.getNote() != -1);
            entry.put("stackable", item.isStackable());
            todos.add(entry);

            if (item.isMembers()) nomes_p2p.add(nome);
            else                  nomes_f2p.add(nome);
        }

        // Lista simples (formato .nouns_cache)
        salvar(cacheDir.resolve("items_f2p.json"), new ArrayList<>(nomes_f2p));
        salvar(cacheDir.resolve("items_p2p.json"), new ArrayList<>(nomes_p2p));
        // Detalhe completo
        salvar(cacheDir.resolve("items_all.json"), todos);

        log.info("CacheExporter: Itens → f2p={} p2p={} total={}", nomes_f2p.size(), nomes_p2p.size(), todos.size());
        notificar("Itens exportados: " + nomes_f2p.size() + " F2P, " + nomes_p2p.size() + " P2P");
    }

    // ── Utilitarios ─────────────────────────────────────────────

    private void salvar(Path path, Object data)
    {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (FileWriter writer = new FileWriter(path.toFile()))
        {
            gson.toJson(data, writer);
            log.info("CacheExporter: salvo → {}", path);
        }
        catch (IOException e)
        {
            log.error("CacheExporter: erro ao salvar {}", path, e);
        }
    }

    private void notificar(String msg)
    {
        try
        {
            client.addChatMessage(
                net.runelite.api.ChatMessageType.GAMEMESSAGE, "",
                "[Cache Exporter] " + msg, null
            );
        }
        catch (Exception ignored) {}
    }

    /**
     * Resolve pipeline2/game_cache/ relativo ao diretorio de trabalho.
     * Fallback: home do usuario.
     */
    private Path resolveGameCacheDir()
    {
        Path dir = Paths.get("pipeline2", "game_cache");
        if (dir.toFile().exists())
        {
            return dir;
        }
        // Fallback: home
        Path fallback = Paths.get(System.getProperty("user.home"), "game_cache");
        try { Files.createDirectories(fallback); } catch (IOException ignored) {}
        return fallback;
    }
}
