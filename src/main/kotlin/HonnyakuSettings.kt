package honnyaku

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
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

    val viewModel = HonnyakuViewModel(
        project.service<TranslationHistory>().read(),
        { project.service<TranslationHistory>().save(it) },
    ).apply { selectedFileUri()?.let { selectedUri = it } }

    init {
        val saves = TranslationSaveListener(project, ::queueTranslationSave)
        project.messageBus.connect(this).subscribe(FileDocumentManagerListener.TOPIC, saves)
        project.messageBus.connect(this).subscribe(AnActionListener.TOPIC, saves)
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    selectedFileUri()?.let { viewModel.selectedUri = it }
                }
            })
    }

    private fun queueTranslationSave(document: Document) {
        val snapshot = ApplicationManager.getApplication().runReadAction(Computable {
            if (project.isDisposed) return@Computable null
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@Computable null
            if (!isProjectMarkdown(project, file)) return@Computable null
            Triple(file, VfsUtilCore.virtualToIoFile(file).toURI().toASCIIString(), document.text)
        }) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && project.service<TranslationSessionController>().isVisible) {
                notifyUnchangedDocumentSaved(project, snapshot.first, snapshot.third)
            }
        }
    }

    private fun selectedFileUri(): String? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        ?.takeIf { isProjectMarkdown(project, it) }
        ?.let { VfsUtilCore.virtualToIoFile(it).toURI().toASCIIString() }

    override fun dispose() { viewModel.dispose() }
}

internal fun translationModelOptions(model: String): Map<String, String> =
    model.trim().takeIf { it.isNotEmpty() }?.let { mapOf("model" to it) } ?: emptyMap()

/** Project-local persistence; the server receives these results as its initial in-memory state. */
@Service(Service.Level.PROJECT)
@State(name = "HonnyakuTranslationHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TranslationHistory : PersistentStateComponent<TranslationHistory.HistoryState> {
    data class HistoryState(var results: String = "[]")
    @Volatile private var historyState = HistoryState()
    override fun getState() = historyState
    override fun loadState(state: HistoryState) { historyState = state }
    fun save(results: List<TranslationUpdate>) { historyState = HistoryState(Gson().toJson(results)) }
    fun read(): List<TranslationUpdate> = runCatching {
        Gson().fromJson(historyState.results, Array<TranslationUpdate>::class.java)?.toList().orEmpty()
    }.getOrDefault(emptyList())
}

class HonnyakuViewModel(
    restored: List<TranslationUpdate> = emptyList(),
    private val saveHistory: (List<TranslationUpdate>) -> Unit = {},
) : Disposable {
    private val translations = mutableMapOf<URI, TranslationUpdate>()
    private val activity = TranslationActivity()
    private val history = restored.toMutableList()
    init { restored.forEach { translations[key(it.uri)] = it } }
    private val usages = mutableMapOf<URI, TranslationTokenUsage>()
    private val reasoningEfforts = mutableMapOf<URI, String>()
    var modelCatalog: TranslationModelCatalog? = null
        private set
    private val listeners = mutableListOf<() -> Unit>()
    private var pendingUsage: TranslationTokenUsage? = null
    var selectedUri: String? = restored.lastOrNull()?.uri
        set(value) {
            if (field == value) return
            field = value
            changed()
        }

    fun translation(uri: String?): TranslationUpdate? = uri?.let { translations[key(it)] }
    fun history(uri: String?): List<TranslationUpdate> =
        if (uri == null) emptyList() else history.filter { key(it.uri) == key(uri) }
    fun usage(uri: String?): TranslationTokenUsage? = uri?.let { usages[key(it)] }
    fun reasoningEffort(uri: String?): String? = uri?.let { reasoningEfforts[key(it)] }
    internal fun pendingBlocks(uri: String?, source: String): List<SourceBlock> =
        uri?.let { activity.pending(key(it).toString(), source) }.orEmpty()
    fun pendingCount(uri: String?): Int = uri?.let { activity.count(key(it).toString()) } ?: 0
    fun clearActivity() { activity.clear(); changed() }

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
        if (update.pending) {
            activity.started(key(update.uri).toString(), update.version, update.translations)
            changed()
            return
        }
        if (!update.restored) activity.completed(key(update.uri).toString(), update.version)
        if (update.translations.isNotEmpty()) {
            val previous = translation(update.uri)
            translations[key(update.uri)] = update
            if (previous?.translations != update.translations || previous.targetLanguage != update.targetLanguage) {
                history.add(update)
                saveHistory(history.toList())
            }
        }
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
        activity.clear()
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

/** Also catch explicit saves which IntelliJ omits from its normal changed-document notifications. */
internal class TranslationSaveListener(
    private val project: Project,
    private val queueSave: (Document) -> Unit,
) : FileDocumentManagerListener, AnActionListener {
    override fun beforeAnyDocumentSaving(document: Document, explicit: Boolean) {
        if (explicit && !FileDocumentManager.getInstance().isDocumentUnsaved(document)) queueSave(document)
    }



    private var unchangedOnSaveAll: Document? = null

    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        if (event.project == project && ActionManager.getInstance().getId(action) == "SaveAll") {
            unchangedOnSaveAll = FileEditorManager.getInstance(project).selectedTextEditor?.document
                ?.takeUnless { FileDocumentManager.getInstance().isDocumentUnsaved(it) }
        }
    }

    override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) {
        if (event.project == project && ActionManager.getInstance().getId(action) == "SaveAll") {
            val document = unchangedOnSaveAll
            unchangedOnSaveAll = null
            if (result.isPerformed && document != null && !FileDocumentManager.getInstance().isDocumentUnsaved(document)) queueSave(document)
        }
    }
}

internal data class SourceBlock(val start: Int, val end: Int, val text: String)

internal fun sourceBlocks(source: String): List<SourceBlock> = buildList {
    var offset = 0
    source.split('\n').forEach { line ->
        if (line.isNotBlank()) add(SourceBlock(offset, offset + line.length, line))
        offset += line.length + 1
    }
}

/** The server reports active work; the client only maps it onto the current editor contents. */
internal class TranslationActivity {
    private data class Pending(val version: Int, val lines: List<ParagraphTranslation>)
    private val pending = mutableMapOf<URI, Pending>()

    fun started(uri: String, version: Int, lines: List<ParagraphTranslation>) {
        pending[URI(uri).normalize()] = Pending(version, lines)
    }

    fun completed(uri: String, version: Int) {
        val key = URI(uri).normalize()
        if ((pending[key]?.version ?: return) <= version) pending.remove(key)
    }

    fun pending(uri: String, source: String): List<SourceBlock> {
        val candidates = sourceBlocks(source)
        val matched = mutableListOf<SourceBlock>()
        pending[URI(uri).normalize()]?.lines.orEmpty().forEach { line ->
            candidates.filter { it.text == line.sourceText && it !in matched }
                .minByOrNull { block ->
                    kotlin.math.abs(source.take(block.start).count { it == '\n' } - (line.range?.start?.line ?: 0))
                }?.let { matched.add(it) }
        }
        return matched.sortedBy { it.start }
    }

    fun count(uri: String): Int = pending[URI(uri).normalize()]?.lines?.size ?: 0
    fun clear() { pending.clear() }
}

internal data class TranslationTextRange(
    val sourceStart: Int,
    val sourceEnd: Int,
    val displayStart: Int,
    val displayEnd: Int,
)

internal data class TranslationDocument(val text: String, val ranges: List<TranslationTextRange>) {
    fun displayOffset(sourceOffset: Int): Int {
        val range = ranges.firstOrNull { sourceOffset < it.sourceEnd } ?: return text.length
        val offset = sourceOffset.coerceIn(range.sourceStart, range.sourceEnd)
        return range.displayStart + ((offset - range.sourceStart).toLong() *
            (range.displayEnd - range.displayStart) / (range.sourceEnd - range.sourceStart)).toInt()
    }
}

/** Keep the complete document, replacing only source paragraphs with successful translations. */
internal fun translationDocument(source: String, history: List<TranslationUpdate>): TranslationDocument {
    data class Replacement(val start: Int, val end: Int, val text: String, val priority: Int)
    val candidates = mutableListOf<Replacement>()
    fun match(original: String, translated: String, priority: Int) {
        if (original.isBlank()) return
        var start = source.indexOf(original)
        while (start >= 0) {
            val end = start + original.length
            if ((start == 0 || source[start - 1] == '\n') && (end == source.length || source[end] == '\n')) {
                candidates.add(Replacement(start, end, translated, priority))
            }
            start = source.indexOf(original, end)
        }
    }
    history.flatMap { it.translations }.forEachIndexed { priority, result ->
        if (result.error == null && result.text != null) {
            match(result.sourceText, result.text, priority)
            // Migrate older paragraph results where their source and translation have matching lines.
            val originalLines = result.sourceText.split('\n')
            val translatedLines = result.text.split('\n')
            if (originalLines.size > 1 && originalLines.size == translatedLines.size) {
                originalLines.zip(translatedLines).forEach { (original, translated) -> match(original, translated, priority) }
            }
        }
    }
    val replacements = mutableListOf<Replacement>()
    candidates.sortedWith(compareByDescending<Replacement> { it.priority }.thenByDescending { it.end - it.start })
        .forEach { candidate ->
            if (replacements.none { it.start < candidate.end && candidate.start < it.end }) replacements.add(candidate)
        }
    replacements.sortBy { it.start }
    val text = StringBuilder()
    val ranges = mutableListOf<TranslationTextRange>()
    var cursor = 0
    fun append(start: Int, end: Int, value: String) {
        if (start == end) return
        val displayStart = text.length
        text.append(value)
        ranges.add(TranslationTextRange(start, end, displayStart, text.length))
    }
    replacements.forEach {
        if (it.start >= cursor) {
            append(cursor, it.start, source.substring(cursor, it.start))
            append(it.start, it.end, it.text)
            cursor = it.end
        }
    }
    append(cursor, source.length, source.substring(cursor))
    return TranslationDocument(text.toString(), ranges)
}
