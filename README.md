# Honnyaku for IntelliJ

Translate changed Markdown paragraphs on save, with results in the Honnyaku tool window.
The plugin uses the IntelliJ Platform built-in LSP API and bundles [Honnyaku](https://github.com/seachicken/honnyaku).

## Requirements

- IntelliJ-based IDE 2026.1 or later.
- An IDE distribution with the built-in LSP module (`com.intellij.modules.lsp`).
- Codex CLI installed on the IDE process's PATH and authenticated.

Open a Markdown file, edit it, and save to translate changed paragraphs into English.
View translations and token usage in the Honnyaku tool window.
