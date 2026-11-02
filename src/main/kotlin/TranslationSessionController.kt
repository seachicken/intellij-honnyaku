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
import org.eclipse.lsp4j.DidSaveTextDocumentParams

@Service(Service.Level.PROJECT)
class TranslationSessionController(private val project: Project) : Disposable {
    private val processes = mutableSetOf<OSProcessHandler>()
    private val visibility = TranslationVisibilityGate(
        onShow = { LspServerManager.getInstance(project).startServersIfNeeded(LanguageServerProvider::class.java) },
        onHide = {
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
                // didSave is ignored by Honnyaku until didOpen has registered the document.
                // Use this server's open event rather than racing serverInitialized/restart.
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || !isVisible || !isProjectMarkdown(project, file)) return@invokeLater
                    if (FileEditorManager.getInstance(project).selectedFiles.firstOrNull() != file) return@invokeLater
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: return@invokeLater
                    val params = DidSaveTextDocumentParams(lspServer.getDocumentIdentifier(file), document.text)
                    lspServer.sendNotification { it.textDocumentService.didSave(params) }
                }
            }
        }, this)
        project.messageBus.connect(this).subscribe(ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) { syncVisibility() }
            })
    }

    fun syncVisibility() {
        visibility.update(ToolWindowManager.getInstance(project).getToolWindow("Honnyaku")?.isVisible == true)
    }

    fun track(process: OSProcessHandler) {
        process.setShouldDestroyProcessRecursively(true)
        synchronized(processes) {
            processes.removeAll { it.isProcessTerminated }
            if (isVisible) processes.add(process) else process.destroyProcess()
        }
    }

    override fun dispose() {
        visibility.update(false)
    }
}

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
