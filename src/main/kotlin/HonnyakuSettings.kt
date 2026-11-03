package honnyaku

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore

@Service(Service.Level.PROJECT)
// Translation preferences are personal to this project's local workspace.
@State(name = "HonnyakuTranslationModel", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class HonnyakuSettings(private val project: Project) : PersistentStateComponent<HonnyakuSettings.SettingsState>, Disposable {
    data class SettingsState(
        var model: String = "",
        var preferredLanguage: String = "Japanese",
        var targetLanguage: String = "Auto",
        var previewPreferred: Boolean = false,
    )
    private var settingsState = SettingsState()
    override fun getState(): SettingsState = settingsState
    override fun loadState(state: SettingsState) { settingsState = state.copy(model = state.model.trim()) }

    private val history = project.service<TranslationHistory>()
    val viewModel = HonnyakuViewModel(
        history.read(),
        history::save,
        history.readUsage(),
        history::saveUsage,
    ).apply { selectedFileUri()?.let { selectedUri = it } }

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    selectedFileUri()?.let { viewModel.selectedUri = it }
                }
            })
    }

    private fun selectedFileUri(): String? = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        ?.takeIf { isProjectMarkdown(project, it) }
        ?.let { VfsUtilCore.virtualToIoFile(it).toURI().toASCIIString() }

    override fun dispose() { viewModel.dispose() }
}

internal fun translationModelOptions(model: String): Map<String, String> =
    model.trim().takeIf { it.isNotEmpty() }?.let { mapOf("model" to it) } ?: emptyMap()

/** Project-local translation cache and usage history. Only translation results seed the server cache. */
@Service(Service.Level.PROJECT)
@State(name = "HonnyakuTranslationHistory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TranslationHistory : PersistentStateComponent<TranslationHistory.HistoryState> {
    data class HistoryState(var results: String = "[]", var sessions: String = "[]")
    @Volatile private var historyState = HistoryState()
    override fun getState() = historyState
    override fun loadState(state: HistoryState) { historyState = state }
    fun save(results: List<TranslationUpdate>) { historyState = historyState.copy(results = Gson().toJson(results)) }
    fun read(): List<TranslationUpdate> = runCatching {
        Gson().fromJson(historyState.results, Array<TranslationUpdate>::class.java)?.toList().orEmpty()
    }.getOrDefault(emptyList())
    fun saveUsage(sessions: List<TranslationUsageSession>) {
        historyState = historyState.copy(sessions = Gson().toJson(sessions))
    }
    fun readUsage(): List<TranslationUsageSession> = runCatching {
        Gson().fromJson(historyState.sessions, Array<TranslationUsageSession>::class.java)?.toList().orEmpty()
    }.getOrDefault(emptyList())
}

internal data class TranslationTextRange(
    val sourceStart: Int,
    val sourceEnd: Int,
    val displayStart: Int,
    val displayEnd: Int,
)

internal data class TranslationDocument(val text: String, val ranges: List<TranslationTextRange>) {
    fun sourceOffset(displayOffset: Int): Int {
        val range = ranges.firstOrNull { displayOffset < it.displayEnd } ?: return ranges.lastOrNull()?.sourceEnd ?: 0
        val offset = displayOffset.coerceIn(range.displayStart, range.displayEnd)
        return range.sourceStart + ((offset - range.displayStart).toLong() *
            (range.sourceEnd - range.sourceStart) / (range.displayEnd - range.displayStart)).toInt()
    }

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
