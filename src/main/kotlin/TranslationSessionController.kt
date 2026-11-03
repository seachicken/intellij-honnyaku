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
import com.intellij.platform.lsp.api.LspServerManagerListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.platform.lsp.api.LspServerState
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
    private var inFlight = false
    private var previewViewport: (() -> TranslationViewport?)? = null
    private val viewportTimer = translationViewportTimer { translateVisible() }
    private val visibility = TranslationVisibilityGate(
        onShow = { LspServerManager.getInstance(project).startServersIfNeeded(LanguageServerProvider::class.java) },
        onHide = {
            viewportTimer.stop()
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
        LspServerManager.getInstance(project).addLspServerManagerListener(object : LspServerManagerListener {
            override fun fileOpened(lspServer: LspServer, file: VirtualFile) {
                if (lspServer.providerClass != LanguageServerProvider::class.java) return
                // Wait until didOpen has registered the document before requesting translation.
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || !isVisible || !isProjectMarkdown(project, file)) return@invokeLater
                    if (FileEditorManager.getInstance(project).selectedFiles.firstOrNull() != file) return@invokeLater
                    queueVisibleTranslation(force = true)
                }
            }
        }, this)
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
        if (project.isDisposed || !isVisible || inFlight) return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!isProjectMarkdown(project, file)) return
        val preview = previewViewport?.invoke()?.takeIf { it.document === document }
        val area = editor.scrollingModel.visibleArea
        if (preview == null && (area.height <= 0 || area.width <= 0)) return
        val first = preview?.firstLine ?: editor.xyToLogicalPosition(Point(area.x, area.y)).line
        val last = preview?.lastLine ?: editor.xyToLogicalPosition(Point(area.x, area.y + area.height - 1)).line
        val window = translationLineWindow(first, last, document.lineCount)
        val server = LspServerManager.getInstance(project).getServersForProvider(LanguageServerProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running } ?: return
        if (!supportsViewportTranslation(server.initializeResult?.capabilities?.experimental)) return
        val params = VisibleTranslationParams(
            VisibleTranslationDocument(server.getDocumentIdentifier(file).uri, server.getDocumentVersion(document), document.text),
            window.first, window.second,
        )
        val attempt = Attempt(server, params)
        if (lastAttempt == attempt || !retries.canAttempt(attempt)) return
        lastAttempt = attempt
        inFlight = true
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
                    inFlight = false
                    if (failed && lastAttempt == attempt) {
                        lastAttempt = null
                        retries.recordFailure(attempt)
                    }
                    if (!project.isDisposed) queueVisibleTranslation()
                }
            }
        }
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

/** Swing timer callbacks run on EDT without an IntelliJ read action. */
internal fun translationViewportTimer(translate: () -> Unit): Timer = Timer(1_000) {
    ApplicationManager.getApplication().runReadAction(Runnable { translate() })
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
