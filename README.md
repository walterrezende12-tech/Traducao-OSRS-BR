# OSRS Translate PT-BR

Plugin RuneLite que traduz o Old School RuneScape para Português Brasileiro em
tempo real.

Autor: Walter Rezende

- [Plugin Hub](https://runelite.net/plugin-hub/Walter%20Rezende)
- [Discord](https://discord.gg/4eAbaj29Gt)
- [Reportar problema](https://github.com/walterrezende12-tech/Traducao-OSRS-BR/issues)

## Funcionalidades

- Tradução de diálogos, opções de resposta e falas acima da cabeça.
- Tradução de menus, mensagens do jogo, Skill Guide, Quest Journal, itens,
  livros, tela de boas-vindas e configurações.
- Seleção de idioma preparada para novos pacotes de tradução.
- Compatibilidade com o Quest Helper.
- Atualizações automáticas dos dicionários sem reinstalar o plugin.

## Traduções remotas

Os dicionários são publicados no repositório
[`osrs-translate-translations`](https://github.com/walterrezende12-tech/osrs-translate-translations).

O plugin verifica atualizações ao iniciar e, depois, uma vez por hora. Cada
arquivo é baixado por HTTPS e validado com SHA-256 antes da nova versão ser
ativada. Se a atualização falhar, o último cache válido continua em uso.

As requisições são feitas ao GitHub e enviam somente os dados técnicos normais
de uma conexão HTTPS, como endereço IP e `User-Agent`. Nenhum dado da conta,
personagem ou jogo é enviado.

O cache fica no diretório do RuneLite:

```text
~/.runelite/osrs-translate/translations/<idioma>/
```

## Instalação

Abra o RuneLite, acesse **Plugin Hub**, pesquise `OSRS Translate PT-BR` e
selecione **Install**.

## Configuração

As categorias de tradução podem ser ativadas ou desativadas separadamente no
painel do plugin. Atualmente o pacote disponível é Português (`pt-BR`).

## Licença

BSD 2-Clause
