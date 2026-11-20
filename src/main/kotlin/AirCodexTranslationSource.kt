package honnyaku

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import javax.swing.Timer

/** Air tabs are FileEditors, but have no text Editor or file-backed Document. */
internal class AirCodexTranslationSource(
    private val project: Project,
    parent: Disposable,
    private val changed: () -> Unit,
    private val readResponses: (String) -> List<String>? = { id -> readThreadResponses(project, id) },
) : Disposable {
    private var active = false
    private var disposed = false
    private var reading = false
    private var threadId: String? = null
    private var source: TranslationSource? = null
    private val timer = Timer(1_000) { refresh() }

    init {
        Disposer.register(parent, this)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) { if (active) refresh() }
            })
    }

    val current: TranslationSource?
        get() = source?.takeIf { selectedThreadId() == threadId }

    fun setActive(value: Boolean) {
        active = value
        if (value) {
            timer.start()
            refresh()
        } else timer.stop()
    }

    private fun selectedThreadId(): String? =
        FileEditorManager.getInstance(project).selectedEditor?.file?.let(::airCodexThreadId)

    private fun refresh() {
        if (disposed || project.isDisposed || !active) return
        if (!ApplicationManager.getApplication().isDispatchThread ||
            !ApplicationManager.getApplication().isReadAccessAllowed) {
            ApplicationManager.getApplication().invokeLater({ WriteIntentReadAction.run { refresh() } }, ModalityState.nonModal())
            return
        }
        val selected = selectedThreadId()
        if (selected != threadId) {
            threadId = selected
            source = selected?.let {
                TranslationSource(
                    editor = null,
                    document = EditorFactory.getInstance().createDocument(""),
                    file = LightVirtualFile("codex-$it.txt", PlainTextFileType.INSTANCE, ""),
                    uri = "honnyaku-air:///codex/$it.txt",
                    snapshotVersion = 0,
                )
            }
            changed()
        }
        val previous = source ?: return
        if (selected == null) return
        if (reading) return
        reading = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val messages = try { readResponses(selected) } catch (_: Exception) { null }
            val text = messages?.joinToString("\n\n")
            ApplicationManager.getApplication().invokeLater({
                reading = false
                if (disposed || project.isDisposed || !active || source !== previous || text == null || messages == null) return@invokeLater
                WriteIntentReadAction.run {
                    if (previous.document.text == text) return@run
                    // Publish the new metadata before listeners see the document change.
                    source = previous.copy(
                        snapshotVersion = (previous.snapshotVersion ?: 0) + 1,
                        preferredOffset = text.length - messages.lastOrNull().orEmpty().length,
                    )
                    ApplicationManager.getApplication().runWriteAction { previous.document.setText(text) }
                    changed()
                }
            }, ModalityState.nonModal())
        }
    }

    override fun dispose() {
        disposed = true
        timer.stop()
        source = null
    }
}

/** Use only Air's tab identity; never guess the most recent Codex session in the project. */
internal fun airCodexThreadId(file: VirtualFile): String? {
    if (file.javaClass.name != "com.intellij.air.thread.view.AgentThreadViewVirtualFile") return null
    // Keep Air optional and preserve compatibility with IDEs which do not bundle it.
    return runCatching {
        val agent = file.javaClass.methods.firstOrNull {
            it.name.startsWith("getAgentId-") && it.parameterCount == 0
        }?.invoke(file) as? String
        if (agent != "codex") return null
        (file.javaClass.getMethod("getThreadId").invoke(file) as? String)?.takeIf(threadIdPattern::matches)
    }.getOrNull()
}

private val threadIdPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private fun readThreadResponses(project: Project, id: String): List<String>? {
    val server = LspServerManager.getInstance(project).getServersForProvider(LanguageServerProvider::class.java)
        .firstOrNull { it.state == LspServerState.Running } ?: return null
    if (!supportsCapability(server.initializeResult?.capabilities?.experimental, "honnyakuThreadResponses")) return null
    return server.sendRequestSync(150_000) {
        (it as HonnyakuLanguageServer).readThreadResponses(ThreadResponsesParams(id))
    }
}
