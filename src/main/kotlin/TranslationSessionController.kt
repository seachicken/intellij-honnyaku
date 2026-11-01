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
