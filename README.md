# OSRS Translate PT-BR

RuneLite plugin that translates Old School RuneScape NPC dialogs to Brazilian Portuguese in real time.

Author: Walter Rezende

Discord: https://discord.gg/dJNNTgs5Q
## Update summary

This update publishes the validated Plugin Hub source for `traducao-osrs-br`.

- Fixes Plugin Hub metadata encoding by keeping public metadata ASCII-safe.
- Fixes runtime translation loading with duplicate-key tolerant JSON parsing.
- Fixes Hans play-time dialog handling.
- Publishes the full translation dataset used during local RuneLite testing.
- Uses the standard Plugin Hub build path with `build=standard`.

## Translation data

The plugin embeds its dictionary at `src/main/resources/com/osrstranslate/translations.json`.

- Loaded translations: 116,857 unique entries
- Static translations: 112,814 entries
- Dynamic placeholder translations: 4,043 entries

## Installation

1. Clone this repository.
2. Build with `./gradlew build`.
3. Load the plugin with RuneLite Developer Tools.

## Usage

The plugin translates NPC dialogs, dialog options, and supported in-game messages automatically.

## Resumo em portugues

Esta atualizacao corrige o plugin publicado no Plugin Hub e envia a base completa de traducoes validada localmente no RuneLite.

- Corrige nomes e descricoes quebrados por problema de encoding.
- Corrige o carregamento das traducoes em tempo de execucao.
- Corrige o dialogo especial do Hans.
- Publica 116.857 traducoes unicas carregadas pelo plugin.
- Mantem o build no padrao `build=standard` do Plugin Hub.

## License

BSD 2-Clause
