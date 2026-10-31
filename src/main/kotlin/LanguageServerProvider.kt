package honnyaku

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import java.nio.file.Files

class LanguageServerProvider : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        if (isMarkdown(file)) {
            serverStarter.ensureServerStarted(HonnyakuServerDescriptor(project))
        }
    }
}

private fun isMarkdown(file: VirtualFile): Boolean = file.fileType.name.equals("Markdown", ignoreCase = true)

private class HonnyakuServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "Honnyaku") {
    override fun isSupportedFile(file: VirtualFile): Boolean = isMarkdown(file)

    override fun getLanguageId(file: VirtualFile): String = "markdown"

    override fun createCommandLine(): GeneralCommandLine {
        val plugin = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("honnyaku.intellij-honnyaku")))
        val executable = plugin.pluginPath.resolve("bin").resolve(if (SystemInfo.isWindows) "honnyaku.exe" else "honnyaku")
        check(Files.isRegularFile(executable)) { "Bundled Honnyaku executable not found: $executable" }
        if (!SystemInfo.isWindows && !Files.isExecutable(executable)) {
            check(executable.toFile().setExecutable(true)) { "Cannot make Honnyaku executable: $executable" }
        }
        return GeneralCommandLine(executable.toString()).withWorkDirectory(project.basePath)
    }

    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient =
        HonnyakuLanguageClient(project, handler)
}

class HonnyakuLanguageClient(
    private val project: Project,
    handler: LspServerNotificationsHandler,
) : Lsp4jClient(handler) {
    @JsonNotification("honnyaku/translations")
    fun translations(update: TranslationUpdate) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val window = ToolWindowManager.getInstance(project).getToolWindow("Honnyaku") ?: return@invokeLater
            window.show {
                val panel = window.contentManager.getContent(0)?.component as? HonnyakuTranslationPanel
                panel?.showTranslation(update)
            }
        }
    }
}

data class TranslationUpdate(
    val uri: String,
    val version: Int,
    val targetLanguage: String,
    val translations: List<ParagraphTranslation>,
)

data class ParagraphTranslation(val sourceText: String, val text: String?, val error: String?)
