package honnyaku

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import java.net.URI

@Service(Service.Level.PROJECT)
// Preserve the stored component name so existing model selections continue to load.
@State(name = "HonnyakuTranslationModel", storages = [Storage("honnyaku.xml")])
class HonnyakuSettings(private val project: Project) : PersistentStateComponent<HonnyakuSettings.SettingsState>, Disposable {
    data class SettingsState(var model: String = "")
    private var settingsState = SettingsState()
    override fun getState(): SettingsState = settingsState
    override fun loadState(state: SettingsState) { settingsState = SettingsState(state.model.trim()) }

    val viewModel = HonnyakuViewModel().apply { selectedUri = selectedFileUri() }

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    viewModel.selectedUri = selectedFileUri()
                }
            })
    }

    private fun selectedFileUri(): String? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        ?.let { VfsUtilCore.virtualToIoFile(it).toURI().toASCIIString() }

    override fun dispose() { viewModel.dispose() }
}

internal fun translationModelOptions(model: String): Map<String, String> =
    model.trim().takeIf { it.isNotEmpty() }?.let { mapOf("model" to it) } ?: emptyMap()

/** File translations and thread usage live in memory for this project session. */
class HonnyakuViewModel : Disposable {
    private val translations = mutableMapOf<URI, TranslationUpdate>()
    private val usages = mutableMapOf<URI, TranslationTokenUsage>()
    private val reasoningEfforts = mutableMapOf<URI, String>()
    var modelCatalog: TranslationModelCatalog? = null
        private set
    private val listeners = mutableListOf<() -> Unit>()
    private var pendingUsage: TranslationTokenUsage? = null
    var selectedUri: String? = null
        set(value) {
            if (field == value) return
            field = value
            changed()
        }

    fun translation(uri: String?): TranslationUpdate? = uri?.let { translations[key(it)] }
    fun usage(uri: String?): TranslationTokenUsage? = uri?.let { usages[key(it)] }
    fun reasoningEffort(uri: String?): String? = uri?.let { reasoningEfforts[key(it)] }

    fun receiveModelCatalog(catalog: TranslationModelCatalog) {
        modelCatalog = catalog
        changed()
    }

    fun receiveReasoningEffort(update: ReasoningEffortUpdate) {
        val uri = key(update.uri)
        if (update.reasoningEffort.isNullOrBlank()) reasoningEfforts.remove(uri)
        else reasoningEfforts[uri] = update.reasoningEffort
        changed()
    }

    fun receiveTranslation(update: TranslationUpdate) {
        translations[key(update.uri)] = update
        (pendingUsage ?: update.tokenUsage)?.let { usages[key(update.uri)] = it }
        pendingUsage = null
        changed()
    }

    fun receiveUsage(update: TokenUsageUpdate) {
        if (update.uri == null) {
            // The sequential server supplies the file URI in its next translation result.
            // Notifications contain cumulative totals, so retain only the latest value.
            pendingUsage = update.usage()
        } else {
            usages[key(update.uri)] = update.usage()
            changed()
        }
    }

    fun clearUsage() {
        pendingUsage = null
        usages.clear()
        reasoningEfforts.clear()
        changed()
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun key(uri: String): URI = URI(uri).normalize()
    private fun changed() { listeners.toList().forEach { it() } }
    override fun dispose() { listeners.clear() }
}
