package honnyaku

import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.pom.PsiDeclaredTarget
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import javax.swing.Timer
import java.awt.Point
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Disposer

@Service(Service.Level.PROJECT)
class TranslationSessionController(private val project: Project) : Disposable {
    private val processes = mutableSetOf<OSProcessHandler>()
    private data class Attempt(val server: LspServer, val params: VisibleTranslationParams)
    private var lastAttempt: Attempt? = null
    private val retries = TranslationRetryBudget<Attempt>()
    private class Request(val server: LspServer, val cancelled: () -> Unit = {})
    private var activeRequest: Request? = null
    private var pendingCacheClear: (() -> Unit)? = null
    private var previewViewport: (() -> TranslationViewport?)? = null
    private val viewportTimer = translationViewportTimer { translateVisible() }
    private val visibility = TranslationVisibilityGate(
        onShow = { LspServerManager.getInstance(project).startServersIfNeeded(LanguageServerProvider::class.java) },
        onHide = {
            viewportTimer.stop()
            cancelActiveRequest()
            val pending = pendingCacheClear
            pendingCacheClear = null
            pending?.invoke()
            lastAttempt = null
            retries.reset()
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) project.service<HonnyakuSettings>().viewModel.clearActivity()
            }
            // Cancel inference immediately, including the child Codex process.
            synchronized(processes) {
                processes.forEach { it.destroyProcess() }
                processes.clear()
            }
            if (!project.isDisposed) {
                LspServerManager.getInstance(project).stopServers(LanguageServerProvider::class.java)
            }
        },
    )
    val isVisible: Boolean get() = visibility.isVisible

    init {
        val editors = EditorFactory.getInstance().eventMulticaster
        editors.addVisibleAreaListener(object : VisibleAreaListener {
            override fun visibleAreaChanged(event: VisibleAreaEvent) {
                if (event.editor === FileEditorManager.getInstance(project).selectedTextEditor) queueVisibleTranslation()
            }
        }, this)
        editors.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.document === FileEditorManager.getInstance(project).selectedTextEditor?.document) queueVisibleTranslation()
            }
        }, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) { queueVisibleTranslation() }
            })
        project.messageBus.connect(this).subscribe(ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) { syncVisibility() }
            })
    }

    fun syncVisibility() {
        visibility.update(ToolWindowManager.getInstance(project).getToolWindow("Honnyaku")?.isVisible == true)
    }

    fun queueVisibleTranslation(force: Boolean = false) {
        if (project.isDisposed || !isVisible) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { queueVisibleTranslation(force) }
            return
        }
        if (force) {
            lastAttempt = null
            retries.reset()
        }
        viewportTimer.restart()
    }

    internal fun registerPreviewViewport(parent: Disposable, provider: () -> TranslationViewport?) {
        previewViewport = provider
        Disposer.register(parent, Disposable { if (previewViewport === provider) previewViewport = null })
    }

    private fun translateVisible() {
        if (project.isDisposed || !isVisible) return
        releaseStoppedRequest()
        if (activeRequest != null) return
        val server = LspServerManager.getInstance(project).getServersForProvider(LanguageServerProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running } ?: return
        val pending = pendingCacheClear
        if (pending != null) {
            pendingCacheClear = null
            pending()
            return
        }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!isProjectTranslationFile(project, file)) return
        val preview = previewViewport?.invoke()?.takeIf { it.document === document }
        val area = editor.scrollingModel.visibleArea
        if (preview == null && (area.height <= 0 || area.width <= 0)) return
        val first = preview?.firstLine ?: editor.xyToLogicalPosition(Point(area.x, area.y)).line
        val last = preview?.lastLine ?: editor.xyToLogicalPosition(Point(area.x, area.y + area.height - 1)).line
        val window = translationLineWindow(first, last, document.lineCount)
        if (!supportsViewportTranslation(server.initializeResult?.capabilities?.experimental)) return
        if (!isWholeDocumentTranslation(file) &&
            !supportsRangeTranslation(server.initializeResult?.capabilities?.experimental)) return
        val ranges = if (isWholeDocumentTranslation(file)) null else {
            textTranslationRanges(project, document) ?: run {
                com.intellij.psi.PsiDocumentManager.getInstance(project).performForCommittedDocument(document) {
                    queueVisibleTranslation()
                }
                return
            }
        }
        val params = VisibleTranslationParams(
            VisibleTranslationDocument(server.getDocumentIdentifier(file).uri, server.getDocumentVersion(document), document.text),
            window.first, window.second, ranges,
        )
        val attempt = Attempt(server, params)
        if (lastAttempt == attempt || !retries.canAttempt(attempt)) return
        lastAttempt = attempt
        val request = Request(server)
        activeRequest = request
        // Only one request is outstanding. Once it completes, recompute the viewport
        // rather than draining a queue of positions passed during a fast scroll.
        ApplicationManager.getApplication().executeOnPooledThread {
            var failed = false
            try {
                server.sendRequestSync(150_000) { (it as HonnyakuLanguageServer).translateVisible(params) }
            } catch (exception: Exception) {
                failed = true
                if (!project.isDisposed && server.state == LspServerState.Running) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(TranslationSessionController::class.java)
                        .warn("Visible translation failed", exception)
                }
            } finally {
                ApplicationManager.getApplication().invokeLater {
                    // A stopped server's completion must not finish a newer server's request.
                    if (activeRequest !== request) return@invokeLater
                    activeRequest = null
                    if (failed && lastAttempt == attempt) {
                        lastAttempt = null
                        retries.recordFailure(attempt)
                    }
                    if (!project.isDisposed) {
                        queueVisibleTranslation()
                    }
                }
            }
        }
    }

    fun clearTranslationCache(uri: String, language: String, completed: () -> Unit) {
        if (project.isDisposed || !isVisible) { completed(); return }
        releaseStoppedRequest()
        if (activeRequest != null) {
            pendingCacheClear = { clearTranslationCache(uri, language, completed) }
            viewportTimer.stop()
            return
        }
        val server = LspServerManager.getInstance(project).getServersForProvider(LanguageServerProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running }
        if (server == null) {
            com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("Honnyaku")
                .createNotification("Translation server unavailable", "Try again when the server is ready.",
                    com.intellij.notification.NotificationType.WARNING).notify(project)
            completed()
            queueVisibleTranslation()
            return
        }
        if (!supportsCacheDeletion(server.initializeResult?.capabilities?.experimental)) {
            com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("Honnyaku")
                .createNotification("Server update required",
                    "Update Honnyaku to clear the cache.",
                    com.intellij.notification.NotificationType.WARNING).notify(project)
            completed()
            queueVisibleTranslation()
            return
        }
        val request = Request(server, completed)
        activeRequest = request
        viewportTimer.stop()
        ApplicationManager.getApplication().executeOnPooledThread {
            var failure: Exception? = null
            try {
                server.sendRequestSync(150_000) {
                    (it as HonnyakuLanguageServer).clearTranslationCache(ClearTranslationCacheParams(uri, language))
                }
            } catch (exception: Exception) { failure = exception }
            ApplicationManager.getApplication().invokeLater {
                if (activeRequest !== request) return@invokeLater
                activeRequest = null
                if (!project.isDisposed) {
                    if (failure == null) {
                        project.service<HonnyakuSettings>().viewModel.clearTranslationCache(uri, language)
                    } else {
                        com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("Honnyaku")
                            .createNotification("Could not clear translation cache",
                                failure?.message ?: "Please try again.",
                                com.intellij.notification.NotificationType.ERROR).notify(project)
                    }
                    completed()
                    queueVisibleTranslation(force = failure == null)
                }
            }
        }
    }

    internal fun serverStopped() {
        releaseStoppedRequest()
    }

    private fun releaseStoppedRequest() {
        if (activeRequest?.server?.state != LspServerState.Running) cancelActiveRequest()
    }

    private fun cancelActiveRequest() {
        val request = activeRequest
        activeRequest = null
        request?.cancelled?.invoke()
    }

    fun track(process: OSProcessHandler) {
        process.setShouldDestroyProcessRecursively(true)
        synchronized(processes) {
            processes.removeAll { it.isProcessTerminated }
            if (isVisible) processes.add(process) else process.destroyProcess()
        }
    }

    override fun dispose() {
        viewportTimer.stop()
        visibility.update(false)
    }
}

internal data class TranslationViewport(val document: Document, val firstLine: Int, val lastLine: Int)

/** Swing timers have no model locks or write-safe transaction context. */
internal fun translationViewportTimer(translate: () -> Unit): Timer = Timer(1_000) {
    ApplicationManager.getApplication().invokeLater(
        { translate() },
        com.intellij.openapi.application.ModalityState.nonModal(),
    )
}.apply { isRepeats = false }

/** Inclusive visible endpoints become a padded, half-open source line window. */
internal fun translationLineWindow(first: Int, last: Int, lineCount: Int): Pair<Int, Int> =
    (first - 5).coerceIn(0, lineCount) to (last + 6).coerceIn(0, lineCount)

internal class TranslationVisibilityGate(
    private val onShow: () -> Unit,
    private val onHide: () -> Unit,
) {
    @Volatile var isVisible = false
        private set

    fun update(visible: Boolean) {
        if (visible == isVisible) return
        isVisible = visible
        if (visible) onShow() else onHide()
    }
}

/** Bound automatic retries for an unchanged snapshot; a new snapshot gets a fresh budget. */
internal class TranslationRetryBudget<T>(private val maxAttempts: Int = 3) {
    private var failedAttempt: T? = null
    private var failures = 0

    fun canAttempt(attempt: T): Boolean = failedAttempt != attempt || failures < maxAttempts

    fun recordFailure(attempt: T) {
        failures = if (failedAttempt == attempt) failures + 1 else 1
        failedAttempt = attempt
    }

    fun reset() {
        failedAttempt = null
        failures = 0
    }
}

/**
 * Positions refer to the committed snapshot sent with the request.
 * Null means PSI is still catching up; an empty list means there is no translatable text.
 */
internal fun textTranslationRanges(project: Project, document: Document): List<Range>? {
    val manager = PsiDocumentManager.getInstance(project)
    // The IDE commits edits asynchronously. Never force a commit from a UI callback.
    if (!manager.isCommitted(document)) return null
    val psi = manager.getPsiFile(document) ?: return emptyList()
    val ranges = mutableListOf<Range>()
    fun position(offset: Int): Position {
        val line = document.getLineNumber(offset)
        return Position(line, offset - document.getLineStartOffset(line))
    }
    psi.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            val range = when {
                element is PsiComment -> element.textRange
                element is PsiLanguageInjectionHost && element.isValidHost -> {
                    val content = element.createLiteralTextEscaper().relevantTextRange
                        .shiftRight(element.textRange.startOffset)
                    // YAML keys and other declaration names are structural text.
                    val isName = generateSequence(element.parent) { it.parent }.any { parent ->
                        (parent as? PsiDeclaredTarget)?.nameIdentifierRange
                            ?.shiftRight(parent.navigationElement.textRange.startOffset)
                            ?.contains(content) == true
                    }
                    content.takeUnless { isName }
                }
                else -> null
            }
            if (range != null && !range.isEmpty && !range.subSequence(document.charsSequence).isBlank()) {
                ranges.add(Range(position(range.startOffset), position(range.endOffset)))
                // A host may contain other hosts or comments; do not send overlapping ranges.
                return
            }
            super.visitElement(element)
        }
    })
    return ranges
}
