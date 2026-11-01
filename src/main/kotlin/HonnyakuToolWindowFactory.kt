package honnyaku

import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.ComboBox
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.ui.JBColor
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.ui.content.ContentFactory
import java.awt.FlowLayout
import java.awt.BorderLayout
import javax.swing.ListCellRenderer
import javax.swing.JLabel
import javax.swing.JPanel

class HonnyakuToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(HonnyakuTranslationPanel(
                project.service<HonnyakuSettings>().viewModel,
                project.service<HonnyakuSettings>().state,
            ) {
                LspServerManager.getInstance(project).stopAndRestartIfNeeded(LanguageServerProvider::class.java)
            }, null, false).apply {
                setDisposer(component as HonnyakuTranslationPanel)
            }
        )
        project.service<TranslationSessionController>().syncVisibility()
    }
}

class HonnyakuTranslationPanel(
    private val viewModel: HonnyakuViewModel,
    modelSettings: HonnyakuSettings.SettingsState,
    restartServer: () -> Unit,
) : JPanel(BorderLayout()), Disposable {
    private val unsubscribe: () -> Unit
    private val usageLabel = TokenUsageLabel()
    private val modelSelector = TranslationModelPanel(modelSettings) {
        viewModel.clearUsage()
        restartServer()
    }
    private val text = JBTextArea("Edit a Markdown file and save it to translate changed paragraphs into English.").apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        add(JBScrollPane(text).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(6, 10),
            )
            add(modelSelector, BorderLayout.WEST)
            add(usageLabel, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        unsubscribe = viewModel.subscribe { refreshSelectedFile() }
        refreshSelectedFile()
    }

    override fun dispose() { unsubscribe() }

    private fun refreshSelectedFile() {
        val uri = viewModel.selectedUri
        usageLabel.updateUsage(viewModel.usage(uri))
        modelSelector.updateCatalog(viewModel.modelCatalog)
        modelSelector.updateReasoningEffort(viewModel.reasoningEffort(uri))
        val update = viewModel.translation(uri)
        text.text = if (update == null) {
            if (uri == null) "Open a Markdown file to see its translation."
            else "Edit this Markdown file and save it to translate changed paragraphs into English."
        } else buildString {
            appendLine(update.uri)
            appendLine("→ ${update.targetLanguage}")
            update.translations.forEach {
                appendLine()
                appendLine(it.error?.let { error -> "Translation failed: $error" } ?: it.text.orEmpty())
            }
        }
        text.caretPosition = 0
    }
}

internal class TranslationModelPanel(
    private val settings: HonnyakuSettings.SettingsState,
    private val onChange: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)) {
    private var catalog: TranslationModelCatalog? = null
    private var reasoningEffort: String? = null
    private var updating = false
    private val selector = ComboBox(arrayOf(settings.model)).apply { isEnabled = false }

    init {
        val standardRenderer = selector.renderer
        selector.renderer = ListCellRenderer { list, model, index, selected, focused ->
            val available = availableModel(model)
            val name = available?.label() ?: when {
                catalog == null -> "Loading models…"
                catalog?.error != null -> "Models unavailable"
                !model.isNullOrEmpty() -> "Unavailable: $model"
                else -> "No models support low reasoning"
            }
            val effort = if (model == settings.model) reasoningEffort ?: available?.let { "low" }
                else available?.let { "low" }
            val label = if (index == -1) effort?.let { "$name · $it" } ?: name else name
            standardRenderer.getListCellRendererComponent(list, name, index, selected, focused).also {
                if (it is JLabel) it.text = label
            }
        }
        selector.addActionListener {
            if (!updating) {
                val model = selector.selectedItem?.toString().orEmpty()
                if (model != settings.model && availableModel(model) != null) {
                    settings.model = model
                    reasoningEffort = null
                    refreshPresentation()
                    onChange()
                }
            }
        }
        add(selector)
        refreshPresentation()
    }

    private fun availableModel(id: String?): AvailableTranslationModel? =
        catalog?.models?.firstOrNull { it.model == id && it.supportsLow() }

    fun updateCatalog(value: TranslationModelCatalog?) {
        if (catalog == value) return
        catalog = value
        val models = value?.models.orEmpty().filter { it.supportsLow() }.distinctBy { it.model }
        val previous = settings.model
        val selected = if (previous.isNotEmpty()) previous
            else (models.firstOrNull { it.isDefault } ?: models.firstOrNull())?.model.orEmpty()
        updating = true
        try {
            selector.removeAllItems()
            models.forEach { selector.addItem(it.model) }
            if (selected.isNotEmpty() && models.none { it.model == selected }) selector.addItem(selected)
            if (selector.itemCount == 0) selector.addItem("")
            selector.selectedItem = selected
            selector.isEnabled = models.isNotEmpty()
            settings.model = selected
        } finally { updating = false }
        refreshPresentation()
        if (selected != previous) onChange()
    }

    fun updateReasoningEffort(effort: String?) {
        reasoningEffort = effort?.takeIf { it.isNotBlank() }
        refreshPresentation()
    }

    private fun refreshPresentation() {
        selector.prototypeDisplayValue = null
        selector.prototypeDisplayValue = (0 until selector.itemCount).map { selector.getItemAt(it) }
            .maxByOrNull { availableModel(it)?.label()?.length ?: it.length }
        selector.toolTipText = catalog?.error ?: when {
            reasoningEffort != null -> "Reasoning: $reasoningEffort · Reported by Codex"
            availableModel(settings.model) != null -> "Reasoning: low · Supported by Codex and configured for translation"
            catalog == null -> "Loading available models from Codex"
            else -> "Select an available model supporting low reasoning"
        }
        selector.accessibleContext.accessibleName = "Translation model: ${availableModel(settings.model)?.label() ?: settings.model}"
        selector.revalidate()
        selector.repaint()
    }
}

internal class TokenUsageLabel : JBLabel() {
    init {
        foreground = JBColor.GRAY
        font = JBUI.Fonts.smallFont()
        updateUsage(null)
    }

    fun updateUsage(usage: TranslationTokenUsage?) {
        text = "Total: ${usage?.summary() ?: "— tokens"}"
        toolTipText = usage?.let { "File thread · ${it.details()}" } ?: "No token usage yet"
        getAccessibleContext().accessibleName = "Total token usage: ${usage?.summary() ?: "unavailable"}"
    }
}
