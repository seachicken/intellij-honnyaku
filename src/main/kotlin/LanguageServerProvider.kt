package honnyaku

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.components.service
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.google.gson.JsonObject
import org.eclipse.lsp4j.InitializeResult
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import java.nio.file.Files
import java.text.NumberFormat
import java.util.Locale
import com.intellij.platform.lsp.api.LspServerState
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Range

class LanguageServerProvider : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        if (isProjectMarkdown(project, file)) {
            val sessionController = project.service<TranslationSessionController>()
            sessionController.syncVisibility()
            if (!sessionController.isVisible) return
            serverStarter.ensureServerStarted(HonnyakuServerDescriptor(project))
        }
    }
}

internal fun isProjectMarkdown(project: Project, file: VirtualFile): Boolean =
    file.fileType.name.equals("Markdown", ignoreCase = true) &&
        ProjectFileIndex.getInstance(project).isInContent(file)

private class HonnyakuServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "Honnyaku") {
    override fun createInitializationOptions(): Any =
        translationInitializationOptions(project.service<HonnyakuSettings>().state.model,
            project.service<TranslationHistory>().read())

    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverStopped(shutdownNormally: Boolean) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) project.service<HonnyakuSettings>().viewModel.clearActivity()
            }
        }

        override fun serverInitialized(params: InitializeResult) {
            if (supportsModelCatalog(params.capabilities.experimental)) return
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val message = "This Honnyaku server cannot provide the model list. Rebuild or update the server configured in HONNYAKU_SERVER_PATH."
                project.service<HonnyakuSettings>().viewModel.receiveModelCatalog(TranslationModelCatalog(error = message))
                LspServerManager.getInstance(project).stopServers(LanguageServerProvider::class.java)
                NotificationGroupManager.getInstance().getNotificationGroup("Honnyaku")
                    .createNotification("Translation server update required",
                        message,
                        NotificationType.ERROR).notify(project)
            }
        }
    }

    override fun isSupportedFile(file: VirtualFile): Boolean = isProjectMarkdown(project, file)

    override fun startServerProcess(): OSProcessHandler {
        check(project.service<TranslationSessionController>().isVisible) { "Open Honnyaku to enable translation" }
        return super.startServerProcess().also { project.service<TranslationSessionController>().track(it) }
    }

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

internal fun supportsModelCatalog(experimental: Any?): Boolean = when (experimental) {
    is Map<*, *> -> experimental["honnyakuModelCatalog"] == true
    is JsonObject -> experimental.get("honnyakuModelCatalog")?.let {
        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
    } == true
    else -> false
}

/** Initialize the server memory cache from client-persisted results, never from the agent prompt. */
internal fun translationInitializationOptions(model: String, saved: List<TranslationUpdate>): Map<String, Any> =
    translationModelOptions(model) + if (saved.isEmpty()) emptyMap() else mapOf("initialTranslations" to saved)

internal fun notifyUnchangedDocumentSaved(project: Project, file: VirtualFile, source: String) {
    val server = LspServerManager.getInstance(project).getServersForProvider(LanguageServerProvider::class.java)
        .firstOrNull { it.state == LspServerState.Running } ?: return
    val params = DidSaveTextDocumentParams(server.getDocumentIdentifier(file), source)
    server.sendNotification { it.textDocumentService.didSave(params) }
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

    @JsonNotification("honnyaku/reasoningEffort")
    fun reasoningEffort(update: ReasoningEffortUpdate) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            project.service<HonnyakuSettings>().viewModel.receiveReasoningEffort(update)
        }
    }

    @JsonNotification("honnyaku/models")
    fun models(catalog: TranslationModelCatalog) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            project.service<HonnyakuSettings>().viewModel.receiveModelCatalog(catalog)
        }
    }

    @JsonNotification("honnyaku/translations")
    fun translations(update: TranslationUpdate) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val viewModel = project.service<HonnyakuSettings>().viewModel
            viewModel.receiveTranslation(update)
        }
    }

}

data class TranslationUpdate(
    val uri: String,
    val version: Int,
    val targetLanguage: String,
    val translations: List<ParagraphTranslation>,
    val tokenUsage: TranslationTokenUsage? = null,
    val pending: Boolean = false,
    val restored: Boolean = false,
)

data class ParagraphTranslation(val sourceText: String, val text: String?, val error: String?, val range: Range? = null)

data class ReasoningEffortUpdate(val uri: String, val reasoningEffort: String? = null)

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

data class TranslationModelCatalog(
    val models: List<AvailableTranslationModel> = emptyList(),
    val error: String? = null,
)

data class AvailableTranslationModel(
    val model: String,
    val displayName: String = "",
    val isDefault: Boolean = false,
    val defaultReasoningEffort: String? = null,
    val supportedReasoningEfforts: List<ReasoningEffortOption>? = null,
) {
    fun supportsLow(): Boolean = supportedReasoningEfforts.orEmpty().any { it.reasoningEffort == "low" }
    fun label(): String = displayName.ifBlank { model }
}

data class ReasoningEffortOption(val reasoningEffort: String)
