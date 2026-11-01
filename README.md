# Honnyaku for IntelliJ

Translate Markdown on save, with results in the Honnyaku tool window.
The plugin uses the IntelliJ Platform built-in LSP API and bundles [Honnyaku](https://github.com/seachicken/honnyaku).

## Requirements

- IntelliJ-based IDE 2026.1 or later.
- An IDE distribution with the built-in LSP module (`com.intellij.modules.lsp`).
- Codex CLI installed on the IDE process's PATH and authenticated.

Open a Markdown file within the project's content roots and save to translate untranslated text into English.

Servers supporting `honnyakuTranslateMissing` translate only missing text. Older bundled servers use their standard save-triggered translation.
Files outside the project's content roots are excluded from translation, even when opened in the editor.
View translations and token usage in the Honnyaku tool window.
Results are saved automatically and reused across IDE restarts.
