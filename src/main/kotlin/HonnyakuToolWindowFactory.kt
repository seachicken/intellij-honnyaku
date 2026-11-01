package honnyaku

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.Point
import java.awt.event.HierarchyEvent
import java.net.URI
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.Timer

class HonnyakuToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(HonnyakuTranslationPanel(
                project,
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
    private val project: Project,
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
    private val viewer = EditorFactory.getInstance().createViewer(
        EditorFactory.getInstance().createDocument(""), project,
    )
    private val loadingDecoration = TranslationLoadingDecoration(viewer)
    private val loadingIcon = AsyncProcessIcon("Translation").apply { suspend() }
    private val loadingLabel = JBLabel()
    private val loadingStatus = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isVisible = false
        border = JBUI.Borders.emptyBottom(6)
        add(loadingIcon, BorderLayout.WEST)
        add(loadingLabel, BorderLayout.CENTER)
    }
    private var projection = TranslationDocument("", emptyList())
    private var sourceDocument: com.intellij.openapi.editor.Document? = null
    private var displayedUri: String? = null
    private var disposed = false
    private var refreshPending = false

    init {
        viewer.contentComponent.accessibleContext.accessibleName = "Translated document"
        add(viewer.component, BorderLayout.CENTER)
        add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(6, 10),
            )
            add(modelSelector, BorderLayout.WEST)
            add(usageLabel, BorderLayout.EAST)
            add(loadingStatus, BorderLayout.NORTH)
        }, BorderLayout.SOUTH)
        unsubscribe = viewModel.subscribe { refreshSelectedFile() }
        EditorFactory.getInstance().eventMulticaster.addVisibleAreaListener(object : VisibleAreaListener {
            override fun visibleAreaChanged(event: VisibleAreaEvent) {
                syncWithEditor(event.editor)
            }
        }, this)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.document === sourceDocument && !refreshPending) {
                    refreshPending = true
                    SwingUtilities.invokeLater {
                        refreshPending = false
                        if (!disposed && !project.isDisposed) refreshSelectedFile()
                    }
                }
            }
        }, this)
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !isShowing) {
                loadingIcon.suspend()
            }
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) {
                SwingUtilities.invokeLater {
                    if (!disposed && !project.isDisposed) refreshSelectedFile()
                }
            }
        }
        refreshSelectedFile()
    }

    override fun dispose() {
        disposed = true
        unsubscribe()
        sourceDocument = null
        loadingDecoration.dispose()
        Disposer.dispose(loadingIcon)
        EditorFactory.getInstance().releaseEditor(viewer)
    }

    private fun refreshSelectedFile() {
        if (disposed || project.isDisposed) return
        val uri = viewModel.selectedUri
        usageLabel.updateUsage(viewModel.usage(uri))
        modelSelector.updateCatalog(viewModel.modelCatalog)
        modelSelector.updateReasoningEffort(viewModel.reasoningEffort(uri))
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
            ?.takeIf { editorMatchesUri(it, uri) }
        val file = uri?.let {
            runCatching { LocalFileSystem.getInstance().findFileByNioFile(java.nio.file.Path.of(URI(it))) }.getOrNull()
        }
        sourceDocument = editor?.document ?: file?.let { FileDocumentManager.getInstance().getDocument(it) }
        if (uri != displayedUri) {
            displayedUri = uri
            if (file != null) {
                (viewer as EditorEx).highlighter = EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(project, file)
            }
        }
        if (editor != null) {
            (viewer as EditorEx).setColorsScheme(editor.colorsScheme)
            viewer.settings.isUseSoftWraps = editor.settings.isUseSoftWraps
            viewer.settings.isLineNumbersShown = editor.settings.isLineNumbersShown
            viewer.settings.isWhitespacesShown = editor.settings.isWhitespacesShown
            viewer.settings.isRightMarginShown = editor.settings.isRightMarginShown
            viewer.settings.isFoldingOutlineShown = false
        }
        projection = translationDocument(sourceDocument?.text.orEmpty(), viewModel.history(uri))
        updateViewerText(projection.text)
        val source = sourceDocument?.text.orEmpty()
        loadingDecoration.update(viewModel.pendingBlocks(uri, source).map {
            projection.displayOffset(it.start) to projection.displayOffset(it.end)
        })
        val pending = viewModel.pendingCount(uri)
        val errors = viewModel.translation(uri)?.translations.orEmpty().mapNotNull { it.error }
        loadingStatus.isVisible = pending > 0 || errors.isNotEmpty()
        loadingIcon.isVisible = pending > 0
        if (pending > 0 && isShowing) loadingIcon.resume() else loadingIcon.suspend()
        loadingLabel.text = if (pending > 0) "Translating $pending line${if (pending == 1) "" else "s"}…"
            else "Translation failed · Original text retained"
        loadingLabel.toolTipText = if (pending > 0) "Saved lines are waiting for translation results" else errors.joinToString("\n")
        if (editor != null) syncWithEditor(editor)
    }

    private fun updateViewerText(value: String) {
        val document = viewer.document
        val previous = document.text
        if (previous == value) return
        // Retain the unchanged prefix and suffix so a translation updates only its changed region.
        val prefix = previous.commonPrefixWith(value).length
        val suffix = previous.substring(prefix).commonSuffixWith(value.substring(prefix)).length
        CommandProcessor.getInstance().runUndoTransparentAction {
            ApplicationManager.getApplication().runWriteAction {
                document.replaceString(prefix, previous.length - suffix, value.substring(prefix, value.length - suffix))
            }
        }
    }

    private fun editorMatchesUri(editor: Editor, uri: String?): Boolean {
        if (uri == null || editor.isDisposed) return false
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        return VfsUtilCore.virtualToIoFile(file).toURI().normalize() == URI(uri).normalize()
    }

    private fun syncWithEditor(editor: Editor) {
        if (disposed || project.isDisposed || editor.isDisposed || !isShowing ||
            editor !== FileEditorManager.getInstance(project).selectedTextEditor ||
            editor.document !== sourceDocument) return
        val visible = editor.scrollingModel.visibleArea
        val offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(Point(0, visible.y)))
        val target = projection.displayOffset(offset).coerceIn(0, viewer.document.textLength)
        val withinLine = visible.y - editor.offsetToXY(offset).y
        val y = viewer.offsetToXY(target).y + withinLine * viewer.lineHeight / editor.lineHeight.coerceAtLeast(1)
        viewer.scrollingModel.disableAnimation()
        try {
            viewer.scrollingModel.scrollVertically(y.coerceAtLeast(0))
        } finally {
            viewer.scrollingModel.enableAnimation()
        }
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

/** Paint behind the existing text; loading never changes document contents or line heights. */
internal class TranslationLoadingDecoration(private val editor: Editor) : Disposable {
    private val highlighters = mutableListOf<RangeHighlighter>()
    private val timer = Timer(40) { editor.contentComponent.repaint() }
    private val hierarchyListener = java.awt.event.HierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) updateAnimation()
    }
    private val renderer = object : CustomHighlighterRenderer {
        override fun paint(editor: Editor, highlighter: RangeHighlighter, graphics: Graphics) {
            if (!highlighter.isValid) return
            val visible = editor.scrollingModel.visibleArea
            val top = editor.offsetToXY(highlighter.startOffset).y.coerceAtLeast(visible.y)
            val bottom = (editor.offsetToXY(highlighter.endOffset).y + editor.lineHeight)
                .coerceAtMost(visible.y + visible.height)
            if (bottom <= top || visible.width <= 0) return
            val tint = JBColor(Color(85, 130, 210), Color(120, 165, 235))
            val g = graphics.create() as Graphics2D
            try {
                g.color = Color(tint.red, tint.green, tint.blue, 22)
                g.fillRect(visible.x, top, visible.width, bottom - top)
                val band = (visible.width / 2).coerceAtLeast(1)
                val phase = Math.floorMod(System.nanoTime(), 1_600_000_000L).toFloat() / 1_600_000_000L
                val x = visible.x + phase * (visible.width + band) - band
                val clear = Color(tint.red, tint.green, tint.blue, 0)
                g.paint = LinearGradientPaint(x, 0f, x + band, 0f,
                    floatArrayOf(0f, 0.5f, 1f), arrayOf(clear, Color(tint.red, tint.green, tint.blue, 48), clear))
                g.fillRect(visible.x, top, visible.width, bottom - top)
            } finally { g.dispose() }
        }
    }

    init { editor.contentComponent.addHierarchyListener(hierarchyListener) }

    fun update(ranges: List<Pair<Int, Int>>) {
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
        ranges.forEach { (start, end) ->
            if (end > start) {
                highlighters.add(editor.markupModel.addRangeHighlighter(start, end,
                    HighlighterLayer.ADDITIONAL_SYNTAX, null, HighlighterTargetArea.EXACT_RANGE).apply {
                    customRenderer = renderer
                    setErrorStripeMarkColor(JBColor.BLUE)
                    errorStripeTooltip = "Waiting for translation"
                })
            }
        }
        updateAnimation()
        editor.contentComponent.repaint()
    }

    private fun updateAnimation() {
        if (highlighters.isNotEmpty() && editor.contentComponent.isShowing) timer.start() else timer.stop()
    }

    override fun dispose() {
        timer.stop()
        editor.contentComponent.removeHierarchyListener(hierarchyListener)
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()
    }
}
