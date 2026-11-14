package honnyaku

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.ex.EditorEventMulticasterEx
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
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
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.LinearGradientPaint
import java.awt.Point
import java.awt.event.HierarchyEvent
import java.net.URI
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.Timer

internal class TranslationScrollListener(
    private val preview: Editor,
    private val isActive: () -> Boolean,
    private val isSyncing: () -> Boolean,
    private val onPreviewScroll: () -> Unit,
    private val onSourceScroll: (Editor) -> Unit,
) : VisibleAreaListener {
    override fun visibleAreaChanged(event: VisibleAreaEvent) {
        if (!isActive()) return
        if (event.editor === preview) {
            if (!isSyncing() && event.oldRectangle != null && event.oldRectangle != event.newRectangle) onPreviewScroll()
        } else {
            onSourceScroll(event.editor)
        }
    }
}

class HonnyakuToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HonnyakuTranslationPanel(
            project,
            project.service<HonnyakuSettings>().viewModel,
            project.service<HonnyakuSettings>().preferences,
        ) {
            LspServerManager.getInstance(project).stopAndRestartIfNeeded(LanguageServerProvider::class.java)
        }
        toolWindow.setTitleActions(panel.titleActions)
        toolWindow.component.putClientProperty(
            com.intellij.openapi.wm.impl.content.ToolWindowContentUi.DONT_HIDE_TOOLBAR_IN_HEADER, true,
        )
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(panel, null, false).apply {
                setDisposer(panel)
            }
        )
        project.service<TranslationSessionController>().syncVisibility()
    }
}

class HonnyakuTranslationPanel(
    private val project: Project,
    private val viewModel: HonnyakuViewModel,
    private val modelSettings: TranslationPreferences,
    private val restartServer: () -> Unit,
) : JPanel(BorderLayout()), Disposable {
    private val unsubscribe: () -> Unit
    private val usageLabel = TokenUsageLabel()
    private val modelSelector = TranslationModelPanel(modelSettings) {
        viewModel.resetRuntimeState()
        restartServer()
    }
    private val viewer = EditorFactory.getInstance().createViewer(
        EditorFactory.getInstance().createDocument(""), project,
    )
    private val loadingDecoration = TranslationLoadingDecoration(viewer)
    private val loadingIcon = AsyncProcessIcon("Translation").apply { suspend() }
    private var translationErrorDetails = ""
    private val loadingLabel = JBLabel().apply {
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (translationErrorDetails.isNotBlank()) {
                    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(
                        java.awt.datatransfer.StringSelection(translationErrorDetails),
                    )
                }
            }
        })
    }
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
    private var activeLanguage: String? = null
    private var syncingScroll = false
    private var previewScrolledManually = false
    private var clearingCache = false
    private val languageControls = TranslationLanguageControls(modelSettings, { refreshSelectedFile() }, {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, HonnyakuConfigurable::class.java)
    }) {
        val uri = displayedUri
        val language = activeLanguage
        if (uri != null && language != null && !clearingCache) {
            clearingCache = true
            refreshSelectedFile()
            project.service<TranslationSessionController>().clearTranslationCache(uri, language) {
                clearingCache = false
                refreshSelectedFile()
            }
        }
    }
    val titleActions: List<AnAction> get() = languageControls.actions

    init {
        viewer.contentComponent.accessibleContext.accessibleName = "Translated document"
        viewer.settings.isAdditionalPageAtBottom = true
        add(viewer.component, BorderLayout.CENTER)
        add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(6, 10),
            )
            add(OverflowToolbar(JBUI.scale(12)).apply {
                addItem(modelSelector) { modelSelector.overflowMenu() }
                addItem(usageLabel, alignRight = true) {
                    object : DumbAwareAction(usageLabel.text) {
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT
                        override fun update(event: AnActionEvent) {
                            event.presentation.isEnabled = viewModel.usageSessions(viewModel.selectedUri).isNotEmpty()
                        }
                        override fun actionPerformed(event: AnActionEvent) {
                            usageLabel.showSessions(this@apply)
                        }
                    }
                }
            }, BorderLayout.CENTER)
            add(loadingStatus, BorderLayout.NORTH)
        }, BorderLayout.SOUTH)
        unsubscribe = viewModel.subscribe { refreshSelectedFile() }
        project.service<TranslationSources>().subscribe(this) { refreshSelectedFile() }
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(PreferredLanguageListener.TOPIC, PreferredLanguageListener { refreshSelectedFile() })
        project.service<TranslationSessionController>().registerPreviewViewport(this) {
            if (disposed || !isShowing || !previewScrolledManually) null else {
                sourceDocument?.let { source ->
                    val area = viewer.scrollingModel.visibleArea
                    if (area.height <= 0 || area.width <= 0) null else {
                        fun sourceLine(y: Int): Int {
                            val offset = viewer.logicalPositionToOffset(viewer.xyToLogicalPosition(Point(area.x, y)))
                            return source.getLineNumber(projection.sourceOffset(offset).coerceIn(0, source.textLength))
                        }
                        TranslationViewport(source, sourceLine(area.y), sourceLine(area.y + area.height - 1))
                    }
                }
            }
        }
        EditorFactory.getInstance().eventMulticaster.addVisibleAreaListener(TranslationScrollListener(
            viewer,
            isActive = { !disposed && !project.isDisposed },
            isSyncing = { syncingScroll },
            onPreviewScroll = {
                previewScrolledManually = true
                project.service<TranslationSessionController>().queueVisibleTranslation()
            },
            onSourceScroll = ::syncWithEditor,
        ), this)
        viewer.component.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(event: java.awt.event.ComponentEvent) {
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && !project.isDisposed && !previewScrolledManually) {
                        project.service<TranslationSources>().current?.editor?.let { syncWithEditor(it) }
                    }
                }
            }
        })
        (EditorFactory.getInstance().eventMulticaster as EditorEventMulticasterEx)
            .addPropertyChangeListener({ event ->
                if (event.propertyName == EditorEx.PROP_FONT_SIZE ||
                    event.propertyName == EditorEx.PROP_FONT_SIZE_2D) {
                    ApplicationManager.getApplication().invokeLater {
                        (event.source as? Editor)?.takeIf {
                            !disposed && !project.isDisposed && !it.isDisposed &&
                                it === project.service<TranslationSources>().current?.editor &&
                                project.service<TranslationSources>().current?.document === sourceDocument
                        }?.let(::syncWithEditor)
                    }
                }
            }, this)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.document === sourceDocument) refreshSelectedFile()
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
        if (refreshPending || disposed || project.isDisposed) return
        refreshPending = true
        // Updating the preview document needs both model locks and a write-safe
        // transaction context; acquiring the lock alone is insufficient.
        ApplicationManager.getApplication().invokeLater({
            refreshPending = false
            WriteIntentReadAction.run { refreshSelectedFileUnderLock() }
        }, com.intellij.openapi.application.ModalityState.nonModal())
    }

    private fun refreshSelectedFileUnderLock() {
        if (disposed || project.isDisposed) return
        val uri = viewModel.selectedUri
        usageLabel.updateUsage(viewModel.usage(uri), viewModel.usageSessions(uri))
        modelSelector.updateCatalog(viewModel.modelCatalog)
        modelSelector.updateReasoningEffort(viewModel.reasoningEffort(uri))
        val selectedSource = project.service<TranslationSources>().current
        val editor = selectedSource?.editor?.takeIf { editorMatchesUri(it, uri) }
        val file = uri?.let {
            runCatching { LocalFileSystem.getInstance().findFileByNioFile(java.nio.file.Path.of(URI(it))) }.getOrNull()
        }
        sourceDocument = selectedSource?.takeIf { it.editor === editor }?.document
            ?: file?.let { FileDocumentManager.getInstance().getDocument(it) }
        if (uri != displayedUri) {
            previewScrolledManually = false
            displayedUri = uri
            if (file != null) {
                (viewer as EditorEx).highlighter = EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(project, file)
            }
        }
        if (editor != null) {
            // An editor's scheme is bound to that editor; sharing it also shares its font cache.
            (viewer as EditorEx).setColorsScheme(viewer.createBoundColorSchemeDelegate(editor.colorsScheme))
            syncFontSize(editor)
            viewer.settings.isUseSoftWraps = editor.settings.isUseSoftWraps
            viewer.settings.isLineNumbersShown = editor.settings.isLineNumbersShown
            viewer.settings.isWhitespacesShown = editor.settings.isWhitespacesShown
            viewer.settings.isRightMarginShown = editor.settings.isRightMarginShown
            viewer.settings.isFoldingOutlineShown = false
        }
        val language = modelSettings.previewLanguage(sourceDocument?.text.orEmpty())
        val previousLanguage = activeLanguage
        activeLanguage = language
        languageControls.updateTarget(modelSettings.resolvedTarget(sourceDocument?.text.orEmpty()))
        if (previousLanguage != null && previousLanguage != language) {
            viewModel.resetRuntimeState()
            restartServer()
        }
        languageControls.updateCacheAction(language, !clearingCache && viewModel.pendingCount(uri) == 0 && sourceDocument != null &&
            viewModel.history(uri).any { it.targetLanguage == language })
        projection = translationDocument(sourceDocument?.text.orEmpty(), viewModel.previewTranslations(uri).filter {
            it.targetLanguage == language
        })
        updateViewerText(projection.text)
        val source = sourceDocument?.text.orEmpty()
        loadingDecoration.update(viewModel.pendingBlocks(uri, source).map {
            projection.displayOffset(it.start) to projection.displayOffset(it.end)
        })
        val pending = viewModel.pendingCount(uri)
        val errors = viewModel.translation(uri)?.takeIf { it.targetLanguage == language }?.translations.orEmpty().mapNotNull { it.error }
        loadingStatus.isVisible = pending > 0 || errors.isNotEmpty()
        loadingIcon.isVisible = pending > 0
        if (pending > 0 && isShowing) loadingIcon.resume() else loadingIcon.suspend()
        loadingLabel.text = if (pending > 0) "Translating $pending line${if (pending == 1) "" else "s"}…"
            else "Translation failed · Click to copy error"
        translationErrorDetails = if (pending > 0) "" else errors.distinct().joinToString("\n")
        loadingLabel.cursor = java.awt.Cursor.getPredefinedCursor(
            if (translationErrorDetails.isNotBlank()) java.awt.Cursor.HAND_CURSOR else java.awt.Cursor.DEFAULT_CURSOR,
        )
        loadingLabel.toolTipText = if (pending > 0) "Translating…" else translationErrorDetails
        if (editor != null && !previewScrolledManually) syncWithEditor(editor)
        if (previewScrolledManually) project.service<TranslationSessionController>().queueVisibleTranslation()
    }

    private fun updateViewerText(value: String) {
        val document = viewer.document
        val previous = document.text
        if (previous == value) return
        // Retain the unchanged prefix and suffix so a translation updates only its changed region.
        val prefix = previous.commonPrefixWith(value).length
        val suffix = previous.substring(prefix).commonSuffixWith(value.substring(prefix)).length
        val previousSync = syncingScroll
        syncingScroll = true
        try {
            CommandProcessor.getInstance().runUndoTransparentAction {
                ApplicationManager.getApplication().runWriteAction {
                    document.replaceString(prefix, previous.length - suffix, value.substring(prefix, value.length - suffix))
                }
            }
        } finally {
            syncingScroll = previousSync
        }
    }

    private fun editorMatchesUri(editor: Editor, uri: String?): Boolean {
        if (uri == null || editor.isDisposed) return false
        val file = project.service<TranslationSources>().current?.takeIf { it.editor === editor }?.file ?: return false
        return VfsUtilCore.virtualToIoFile(file).toURI().normalize() == URI(uri).normalize()
    }

    private fun syncWithEditor(editor: Editor) {
        if (disposed || project.isDisposed || editor.isDisposed || !isShowing) return
        if (!ApplicationManager.getApplication().isReadAccessAllowed) {
            ApplicationManager.getApplication().invokeLater {
                WriteIntentReadAction.run { syncWithEditor(editor) }
            }
            return
        }
        val source = project.service<TranslationSources>().current ?: return
        if (editor !== source.editor || source.document !== sourceDocument) return
        syncFontSize(editor)
        previewScrolledManually = false
        // Match the line at the preview viewport's physical top, including variable-height headers.
        val sourceY = SwingUtilities.convertPoint(
            viewer.contentComponent, Point(0, viewer.scrollingModel.visibleArea.y), editor.contentComponent,
        ).y
        val offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(Point(0, sourceY)))
        val sourceOffset = source.sourceOffset(offset)
        val target = projection.displayOffset(sourceOffset).coerceIn(0, viewer.document.textLength)
        val withinLine = sourceY - editor.offsetToXY(offset).y
        val y = viewer.offsetToXY(target).y + withinLine * viewer.lineHeight / editor.lineHeight.coerceAtLeast(1)
        viewer.scrollingModel.disableAnimation()
        syncingScroll = true
        try {
            // Allow short documents to align with the source editor below its header.
            viewer.settings.additionalLinesCount =
                viewer.scrollingModel.visibleArea.height / viewer.lineHeight.coerceAtLeast(1) + 1
            viewer.scrollingModel.scrollVertically(y.coerceAtLeast(0))
        } finally {
            syncingScroll = false
            viewer.scrollingModel.enableAnimation()
        }
    }

    private fun syncFontSize(editor: Editor) {
        val fontSize = editor.colorsScheme.editorFontSize2D
        // The delegated size can change before the preview's cached rendering font does.
        if (viewer.colorsScheme.getFont(EditorFontType.PLAIN).size2D != fontSize) {
            (viewer as EditorEx).setFontSize(fontSize)
        }
    }
}

internal data class TranslationLanguage(val code: String, val nativeName: String) {
    val displayCode: String get() = code.uppercase(java.util.Locale.ROOT)
    val label: String get() = "$nativeName ($displayCode)"
}

internal val translationLanguageOptions = listOf(
    TranslationLanguage("ar", "العربية"),
    TranslationLanguage("de", "Deutsch"),
    TranslationLanguage("en", "English"),
    TranslationLanguage("es", "Español"),
    TranslationLanguage("fr", "Français"),
    TranslationLanguage("it", "Italiano"),
    TranslationLanguage("ja", "日本語"),
    TranslationLanguage("ko", "한국어"),
    TranslationLanguage("pt", "Português"),
    TranslationLanguage("ru", "Русский"),
    TranslationLanguage("zh-Hans", "简体中文"),
    TranslationLanguage("zh-Hant", "繁體中文"),
)
internal val translationLanguages = translationLanguageOptions.map { it.code }.toTypedArray()
internal fun languageOption(value: String?) = translationLanguageOptions.find { it.code == value }
internal fun languageLabel(value: String?) = languageOption(value)?.label ?: value.orEmpty()

/** Script detection deliberately falls back to English for ambiguous Latin text. */
internal fun automaticTargetLanguage(source: String, preferred: String): String {
    val scripts = source.codePoints().toArray().map { Character.UnicodeScript.of(it) }
    val candidates = listOf(
        "ja" to scripts.count { it == Character.UnicodeScript.HIRAGANA || it == Character.UnicodeScript.KATAKANA },
        "ko" to scripts.count { it == Character.UnicodeScript.HANGUL },
        "zh-Hans" to scripts.count { it == Character.UnicodeScript.HAN },
        "ru" to scripts.count { it == Character.UnicodeScript.CYRILLIC },
        "ar" to scripts.count { it == Character.UnicodeScript.ARABIC },
    )
    // Kanji in Japanese should not turn a Japanese author's document into a Chinese target.
    val japanese = candidates.first().second > 0
    return candidates.filter { it.first != preferred && it.second > 0 && !(japanese && it.first == "zh-Hans") }
        .maxByOrNull { it.second }?.first ?: "en"
}

internal fun TranslationPreferences.resolvedTarget(source: String): String =
    when (targetLanguage) {
        "Auto" -> automaticTargetLanguage(source, preferredLanguage)
        "Preferred" -> preferredLanguage
        else -> targetLanguage
    }

internal fun TranslationPreferences.previewLanguage(source: String): String =
    resolvedTarget(source)

/** Native title-bar actions keep translation controls outside the document viewport. */
internal class TranslationLanguageControls(
    private val settings: TranslationPreferences,
    private val onChange: () -> Unit,
    onSettings: () -> Unit,
    onClearCache: () -> Unit = {},
) {
    private var automaticLanguage = "en"
    private var canClearCache = false
    private var cacheLanguage = ""
    private val target = object : com.intellij.openapi.actionSystem.ex.ComboBoxAction(), DumbAware {
        init { isSmallVariant = true }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(event: AnActionEvent) {
            event.presentation.text = targetLabel(settings.targetLanguage, compact = true)
            event.presentation.description = "Translation target: ${targetLabel(settings.targetLanguage, compact = false)}"
        }
        override fun createPopupActionGroup(button: JComponent) = selectionMenu(
            "Target language",
            (listOf("Auto", "Preferred") + translationLanguages.filter { it != settings.preferredLanguage })
                .map { it to targetLabel(it, compact = false) },
            settings.targetLanguage,
        ) { language ->
            if (settings.targetLanguage != language) {
                settings.targetLanguage = language
                onChange()
            }
        }
    }
    private val clearCache = object : DumbAwareAction("Retranslate", "Retranslate", com.intellij.icons.AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = canClearCache
            event.presentation.description = "Retranslate in $cacheLanguage"
        }
        override fun actionPerformed(event: AnActionEvent) = onClearCache()
    }
    val actions: List<AnAction> = listOf(target, clearCache,
        object : DumbAwareAction("Honnyaku settings", "Honnyaku settings", com.intellij.icons.AllIcons.General.Settings) {
            override fun actionPerformed(event: AnActionEvent) = onSettings()
        },
    )

    private fun targetLabel(language: String?, compact: Boolean): String = when (language) {
        "Auto" -> "Auto (${languageOption(automaticLanguage)?.displayCode ?: automaticLanguage})"
        "Preferred" -> "Preferred (${languageOption(settings.preferredLanguage)?.displayCode})"
        else -> if (compact) languageOption(language)?.displayCode ?: language.orEmpty() else languageLabel(language)
    }

    fun updateCacheAction(language: String, enabled: Boolean) {
        canClearCache = enabled
        cacheLanguage = language
    }

    fun updateTarget(language: String) {
        if (settings.targetLanguage == "Auto") automaticLanguage = language
    }
}

internal class TranslationModelPanel(
    private val settings: TranslationPreferences,
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

    fun overflowMenu() = selectionMenu(
        "Model: ${availableModel(settings.model)?.label() ?: settings.model}",
        (0 until selector.itemCount).map { selector.getItemAt(it).let { id ->
            id to (availableModel(id)?.label() ?: "Models unavailable")
        } },
        settings.model, selector.isEnabled,
    ) { selector.selectedItem = it }

    private fun refreshPresentation() {
        selector.prototypeDisplayValue = null
        selector.prototypeDisplayValue = (0 until selector.itemCount).map { selector.getItemAt(it) }
            .maxByOrNull { availableModel(it)?.label()?.length ?: it.length }
        selector.toolTipText = catalog?.error ?: when {
            reasoningEffort != null -> "Reasoning: $reasoningEffort"
            availableModel(settings.model) != null -> "Reasoning: low"
            catalog == null -> "Loading models…"
            else -> "Select a model supporting low reasoning"
        }
        selector.accessibleContext.accessibleName = "Translation model: ${availableModel(settings.model)?.label() ?: settings.model}"
        selector.revalidate()
        selector.repaint()
    }
}

internal class TokenUsageLabel : JBLabel() {
    private var sessions: List<TranslationUsageSession> = emptyList()
    private var hovered = false
    init {
        foreground = JBColor.GRAY
        font = JBUI.Fonts.smallFont()
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(event: java.awt.event.MouseEvent) {
                hovered = true
                updateInteraction()
            }

            override fun mouseExited(event: java.awt.event.MouseEvent) {
                hovered = false
                updateInteraction()
            }

            override fun mouseClicked(event: java.awt.event.MouseEvent) {
                if (sessions.isEmpty()) return
                showSessions(this@TokenUsageLabel)
            }
        })
        updateUsage(null)
    }

    fun showSessions(anchor: javax.swing.JComponent) {
        if (sessions.isEmpty()) return
        val dateFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
        val rows = sessions.sortedByDescending { it.startedAt }.map { session ->
            val usage = session.usage
            arrayOf<Any>(dateFormat.format(java.time.Instant.ofEpochMilli(session.startedAt)),
                languageOption(session.targetLanguage)?.displayCode ?: "—",
                session.model.ifBlank { "Default" },
                usage.inputTokens, usage.cachedInputTokens, usage.outputTokens, usage.totalTokens)
        }.toTypedArray()
        val table = com.intellij.ui.table.JBTable(object : javax.swing.table.DefaultTableModel(
            rows, arrayOf("Started", "Target", "Model", "Input", "Cached input", "Output", "Total"),
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
        })
        val numberRenderer = javax.swing.table.DefaultTableCellRenderer().apply {
            horizontalAlignment = javax.swing.SwingConstants.RIGHT
        }
        for (column in 3..6) {
            table.columnModel.getColumn(column).cellRenderer = numberRenderer
        }
        val headerRenderer = table.tableHeader.defaultRenderer
        table.tableHeader.defaultRenderer = javax.swing.table.TableCellRenderer { owner, value, selected, focused, row, column ->
            headerRenderer.getTableCellRendererComponent(owner, value, selected, focused, row, column).also { component ->
                (component as? javax.swing.JLabel)?.horizontalAlignment =
                    if (owner.convertColumnIndexToModel(column) >= 3) javax.swing.SwingConstants.RIGHT else javax.swing.SwingConstants.LEFT
            }
        }
        val content = com.intellij.ui.components.JBScrollPane(table).apply {
            preferredSize = JBUI.size(850, 240)
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, table).setTitle("Translation sessions")
            .setResizable(true).setMovable(true).setRequestFocus(true).createPopup()
            .showUnderneathOf(anchor)
    }

    fun updateUsage(usage: TranslationTokenUsage?, sessions: List<TranslationUsageSession> = emptyList()) {
        this.sessions = sessions
        text = "Total: ${usage?.summary() ?: "— tokens"}"
        toolTipText = usage?.let { "${sessions.size} sessions · Click for details" } ?: "No token usage yet"
        getAccessibleContext().accessibleName = "Total token usage: ${usage?.summary() ?: "unavailable"}"
        updateInteraction()
    }

    private fun updateInteraction() {
        val clickable = sessions.isNotEmpty()
        val highlighted = clickable && hovered
        foreground = if (highlighted) JBUI.CurrentTheme.Label.foreground() else JBColor.GRAY
        cursor = java.awt.Cursor.getPredefinedCursor(
            if (clickable) java.awt.Cursor.HAND_CURSOR else java.awt.Cursor.DEFAULT_CURSOR,
        )
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

/** Keep controls on one row; controls on the right move into the overflow menu first. */
internal class OverflowToolbar(private val gap: Int = JBUI.scale(6)) : JPanel() {
    private data class Item(val component: JComponent, val alignRight: Boolean, val menu: () -> AnAction)
    private val items = mutableListOf<Item>()
    private val overflow = JButton(com.intellij.icons.AllIcons.Actions.More).apply {
        toolTipText = "More"
        preferredSize = JBUI.size(24, 24)
        margin = JBUI.emptyInsets()
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        accessibleContext.accessibleName = "More actions"
        isVisible = false
        addActionListener {
            JBPopupFactory.getInstance().createActionGroupPopup(
                null, overflowGroup(), DataManager.getInstance().getDataContext(this@OverflowToolbar),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true,
            ).showUnderneathOf(this)
        }
    }

    init {
        isOpaque = false
        add(overflow)
        layout = object : LayoutManager {
            override fun addLayoutComponent(name: String?, component: java.awt.Component?) = Unit
            override fun removeLayoutComponent(component: java.awt.Component?) = Unit
            override fun preferredLayoutSize(parent: Container): Dimension = Dimension(
                items.sumOf { it.component.preferredSize.width } + gap * (items.size - 1).coerceAtLeast(0) +
                    insets.left + insets.right,
                rowHeight() + insets.top + insets.bottom,
            )
            override fun minimumLayoutSize(parent: Container) = Dimension(
                overflow.preferredSize.width + insets.left + insets.right,
                rowHeight() + insets.top + insets.bottom,
            )

            override fun layoutContainer(parent: Container) {
                val available = (width - insets.left - insets.right).coerceAtLeast(0)
                val visible = items.toMutableList()
                fun requiredWidth() = visible.sumOf { it.component.preferredSize.width } +
                    gap * (visible.size - 1).coerceAtLeast(0) +
                    if (visible.size < items.size) overflow.preferredSize.width +
                        (if (visible.isEmpty()) 0 else gap) else 0
                for (item in items.asReversed()) {
                    if (requiredWidth() <= available) break
                    visible.remove(item)
                }
                overflow.isVisible = visible.size < items.size
                var x = insets.left
                val height = (height - insets.top - insets.bottom).coerceAtLeast(0)
                for (item in items) {
                    val component = item.component
                    val show = item in visible
                    // Don't strand keyboard focus on a control that just moved into the menu.
                    if (!show && component.isVisible &&
                        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.let {
                            javax.swing.SwingUtilities.isDescendingFrom(it, component)
                        } == true) overflow.requestFocusInWindow()
                    component.isVisible = show
                    if (show) {
                        val size = component.preferredSize
                        if (item.alignRight) x = width - insets.right - size.width -
                            (if (overflow.isVisible) overflow.preferredSize.width + gap else 0)
                        component.setBounds(x, insets.top + (height - size.height).coerceAtLeast(0) / 2,
                            size.width, size.height.coerceAtMost(height))
                        x += size.width + gap
                    }
                }
                if (overflow.isVisible) overflow.setBounds(
                    (width - insets.right - overflow.preferredSize.width).coerceAtLeast(insets.left),
                    insets.top, overflow.preferredSize.width.coerceAtMost(available), height,
                )
            }
        }
    }

    private fun rowHeight() = (items.map { it.component.preferredSize.height } + overflow.preferredSize.height).max()

    fun addItem(component: JComponent, alignRight: Boolean = false, menu: () -> AnAction) {
        items.add(Item(component, alignRight, menu))
        add(component)
    }

    internal fun overflowGroup() = DefaultActionGroup().apply {
        items.filter { !it.component.isVisible }.forEach { add(it.menu()) }
    }
}

/** Menu choices use the same selection callback as their inline control. */
internal fun selectionMenu(
    title: String,
    choices: List<Pair<String, String>>,
    selected: String?,
    enabled: Boolean = true,
    choicesEnabled: Boolean = enabled,
    select: (String) -> Unit,
): DefaultActionGroup = object : DefaultActionGroup(title, true), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(event: AnActionEvent) { event.presentation.isEnabled = enabled }
}.apply {
    choices.forEach { (value, label) ->
        add(object : ToggleAction(label), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(event: AnActionEvent) {
                super.update(event)
                event.presentation.isEnabled = choicesEnabled
            }
            override fun isSelected(event: AnActionEvent) = value == selected
            override fun setSelected(event: AnActionEvent, state: Boolean) { if (state) select(value) }
        })
    }
}
