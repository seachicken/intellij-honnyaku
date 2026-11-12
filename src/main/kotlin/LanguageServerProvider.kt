package honnyaku

import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import java.nio.file.Files
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.CompletableFuture
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer

class LanguageServerProvider : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        if (isTranslationFile(file)) {
            val sessionController = project.service<TranslationSessionController>()
            sessionController.syncVisibility()
            if (!sessionController.isVisible) return
            serverStarter.ensureServerStarted(HonnyakuServerDescriptor(project))
            // Also schedule when an already running server opens another file.
            sessionController.queueVisibleTranslation(force = true)
        }
    }
}

// Scroll and Swing layout callbacks can run on the EDT without model read access.
internal fun isTranslationFile(file: VirtualFile): Boolean =
    ReadAction.compute<Boolean, RuntimeException> {
        !file.isDirectory && !file.fileType.isBinary &&
            (isWholeDocumentTranslation(file) ||
                (file.fileType as? LanguageFileType)?.language?.let {
                    LanguageParserDefinitions.INSTANCE.forLanguage(it) != null
                } == true)
    }

internal class HonnyakuServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "Honnyaku") {
    override fun createInitializationOptions(): Any =
        translationInitializationOptions(
            project.service<HonnyakuSettings>().preferences.model,
            project.service<TranslationHistory>().read(),
            project.service<HonnyakuSettings>().preferences.previewLanguage(
                project.service<TranslationSources>().current?.document?.text.orEmpty()),
        )

    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverStopped(shutdownNormally: Boolean) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    project.service<TranslationSessionController>().serverStopped()
                    project.service<HonnyakuSettings>().viewModel.clearActivity()
                }
            }
        }

        override fun serverInitialized(params: InitializeResult) {
            if (supportsModelCatalog(params.capabilities.experimental) &&
                supportsViewportTranslation(params.capabilities.experimental)) {
                // The first viewport attempt may have run while the server was starting.
                project.service<TranslationSessionController>().queueVisibleTranslation(force = true)
                return
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val message = "This Honnyaku server requires model catalog and viewport translation support. Rebuild or update the server configured in HONNYAKU_SERVER_PATH."
                project.service<HonnyakuSettings>().viewModel.receiveModelCatalog(TranslationModelCatalog(error = message))
                LspServerManager.getInstance(project).stopServers(LanguageServerProvider::class.java)
                NotificationGroupManager.getInstance().getNotificationGroup("Honnyaku")
                    .createNotification("Translation server update required",
                        message,
                        NotificationType.ERROR).notify(project)
            }
        }
    }

    override fun isSupportedFile(file: VirtualFile): Boolean = isTranslationFile(file)

    override fun startServerProcess(): OSProcessHandler {
        check(project.service<TranslationSessionController>().isVisible) { "Open Honnyaku to enable translation" }
        return super.startServerProcess().also { project.service<TranslationSessionController>().track(it) }
    }

    override fun getLanguageId(file: VirtualFile): String =
        if (file.fileType.name.equals("Markdown", ignoreCase = true)) "markdown"
        else if (isWholeDocumentTranslation(file)) "plaintext"
        else (file.fileType as LanguageFileType).language.id.lowercase(Locale.ROOT)

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

    override val lsp4jServerClass: Class<out LanguageServer> = HonnyakuLanguageServer::class.java
}

interface HonnyakuLanguageServer : LanguageServer {
    @JsonRequest("honnyaku/clearTranslationCache")
    fun clearTranslationCache(params: ClearTranslationCacheParams): CompletableFuture<Any?>

    @JsonRequest("honnyaku/translateVisible")
    fun translateVisible(params: VisibleTranslationParams): CompletableFuture<Any?>
}

data class ClearTranslationCacheParams(val uri: String, val targetLanguage: String)

internal fun supportsCacheDeletion(experimental: Any?): Boolean = when (experimental) {
    is Map<*, *> -> experimental["honnyakuCacheDeletion"] == true
    is JsonObject -> experimental.get("honnyakuCacheDeletion")?.let {
        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
    } == true
    else -> false
}

data class VisibleTranslationDocument(val uri: String, val version: Int, val text: String)
data class VisibleTranslationParams(
    val textDocument: VisibleTranslationDocument,
    val startLine: Int,
    val endLine: Int,
    val ranges: List<Range>? = null,
)

internal fun supportsViewportTranslation(experimental: Any?): Boolean = when (experimental) {
    is Map<*, *> -> experimental["honnyakuViewportTranslation"] == true
    is JsonObject -> experimental.get("honnyakuViewportTranslation")?.let {
        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
    } == true
    else -> false
}

internal fun supportsModelCatalog(experimental: Any?): Boolean = when (experimental) {
    is Map<*, *> -> experimental["honnyakuModelCatalog"] == true
    is JsonObject -> experimental.get("honnyakuModelCatalog")?.let {
        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
    } == true
    else -> false
}

/** Initialize the server memory cache from client-persisted results, never from the agent prompt. */
internal fun translationInitializationOptions(
    model: String,
    saved: List<TranslationUpdate>,
    targetLanguage: String? = null,
): Map<String, Any> = translationModelOptions(model) +
    (if (saved.isEmpty()) emptyMap() else mapOf("initialTranslations" to saved)) +
    (targetLanguage?.let { mapOf("targetLanguage" to it) } ?: emptyMap())

class HonnyakuLanguageClient(
    private val project: Project,
    handler: LspServerNotificationsHandler,
) : Lsp4jClient(handler) {
    private val usageSessionId = java.util.UUID.randomUUID().toString()
    init {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                val viewModel = project.service<HonnyakuSettings>().viewModel
                viewModel.resetRuntimeState()
                val settings = project.service<HonnyakuSettings>().preferences
                val source = project.service<TranslationSources>().current?.document?.text.orEmpty()
                viewModel.beginUsageSession(usageSessionId, settings.model, settings.previewLanguage(source))
            }
        }
    }

    @JsonNotification("honnyaku/tokenUsage")
    fun tokenUsage(update: TokenUsageUpdate) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            project.service<HonnyakuSettings>().viewModel.receiveUsage(update, usageSessionId)
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
            viewModel.receiveTranslation(update, usageSessionId)
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
    val partial: Boolean = false,
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

internal fun supportsRangeTranslation(experimental: Any?): Boolean = when (experimental) {
    is Map<*, *> -> experimental["honnyakuRangeTranslation"] == true
    is JsonObject -> experimental.get("honnyakuRangeTranslation")?.let {
        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
    } == true
    else -> false
}

internal fun isWholeDocumentTranslation(file: VirtualFile): Boolean =
    file.fileType == PlainTextFileType.INSTANCE ||
        file.fileType.name.equals("Markdown", ignoreCase = true) ||
        file.fileType.name.equals("AsciiDoc", ignoreCase = true)
