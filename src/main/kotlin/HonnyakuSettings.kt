package honnyaku

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import java.net.URI

@Service(Service.Level.PROJECT)
class HonnyakuSettings(private val project: Project) : Disposable {
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

/** File translations and thread usage live in memory for this project session. */
class HonnyakuViewModel : Disposable {
    private val translations = mutableMapOf<URI, TranslationUpdate>()
    private val usages = mutableMapOf<URI, TranslationTokenUsage>()
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
