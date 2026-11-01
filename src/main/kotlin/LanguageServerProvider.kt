package honnyaku

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import java.nio.file.Files
import java.text.NumberFormat
import java.util.Locale

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
        val developmentExecutable = System.getenv("HONNYAKU_SERVER_PATH")?.takeIf { it.isNotBlank() }
        val plugin = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("honnyaku.intellij-honnyaku")))
        val executable = developmentExecutable?.let { java.nio.file.Path.of(it) }
            ?: plugin.pluginPath.resolve("bin").resolve(if (SystemInfo.isWindows) "honnyaku.exe" else "honnyaku")
        check(Files.isRegularFile(executable)) { "Honnyaku executable not found: $executable" }
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
    init {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                val viewModel = project.service<HonnyakuSettings>().viewModel
                viewModel.clearUsage()
            }
        }
    }

    @JsonNotification("honnyaku/tokenUsage")
    fun tokenUsage(update: TokenUsageUpdate) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            project.service<HonnyakuSettings>().viewModel.receiveUsage(update)
        }
    }

    @JsonNotification("honnyaku/translations")
    fun translations(update: TranslationUpdate) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val viewModel = project.service<HonnyakuSettings>().viewModel
            viewModel.receiveTranslation(update)
            if (viewModel.translation(viewModel.selectedUri) === update) {
                ToolWindowManager.getInstance(project).getToolWindow("Honnyaku")?.show()
            }
        }
    }

}

data class TranslationUpdate(
    val uri: String,
    val version: Int,
    val targetLanguage: String,
    val translations: List<ParagraphTranslation>,
    val tokenUsage: TranslationTokenUsage? = null,
)

data class ParagraphTranslation(val sourceText: String, val text: String?, val error: String?)

/** URI is optional for compatibility with servers that send usage before translations. */
data class TokenUsageUpdate(
    val uri: String? = null,
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
) {
    fun usage() = TranslationTokenUsage(inputTokens, cachedInputTokens, outputTokens, totalTokens)
}

/** Cumulative usage of a file’s translation thread. Cached input is included in inputTokens. */
data class TranslationTokenUsage(
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
) {
    fun summary(): String = "${format(totalTokens)} tokens"

    fun details(): String = "Input: ${format(inputTokens)} · Cached input: ${format(cachedInputTokens)} · Output: ${format(outputTokens)}"

    private fun format(value: Long): String =
        NumberFormat.getCompactNumberInstance(Locale.US, NumberFormat.Style.SHORT).apply {
            maximumFractionDigits = 1
        }.format(value.coerceAtLeast(0))
}
