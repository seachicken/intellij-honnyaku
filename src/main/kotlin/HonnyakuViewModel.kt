package honnyaku

import com.intellij.openapi.Disposable
import java.net.URI

class HonnyakuViewModel(
    restored: List<TranslationUpdate> = emptyList(),
    private val saveHistory: (List<TranslationUpdate>) -> Unit = {},
    restoredSessions: List<TranslationUsageSession> = emptyList(),
    saveSessions: (List<TranslationUsageSession>) -> Unit = {},
) : Disposable {
    private val translations = mutableMapOf<URI, TranslationUpdate>()
    private val activity = TranslationActivity()
    private val history = restored.toMutableList()
    init { restored.forEach { translations[key(it.uri)] = it } }
    private val ledger = TranslationUsageLedger(restoredSessions, saveSessions)
    private data class Session(val model: String, val language: String, val startedAt: Long)
    private val sessions = mutableMapOf<String, Session>()
    private var currentSession = java.util.UUID.randomUUID().toString()
    fun beginUsageSession(id: String, model: String, language: String) {
        sessions[id] = Session(model, language, System.currentTimeMillis())
        currentSession = id
        changed()
    }
    private val reasoningEfforts = mutableMapOf<URI, String>()
    var modelCatalog: TranslationModelCatalog? = null
        private set
    private val listeners = mutableListOf<() -> Unit>()
    private val pendingUsages = mutableMapOf<String, TranslationTokenUsage>()
    private val activeUsageUris = mutableMapOf<String, String>()
    var selectedUri: String? = restored.lastOrNull()?.uri
        set(value) {
            if (field == value) return
            field = value
            changed()
        }

    fun translation(uri: String?): TranslationUpdate? = uri?.let { translations[key(it)] }
    fun history(uri: String?): List<TranslationUpdate> =
        if (uri == null) emptyList() else history.filter { key(it.uri) == key(uri) }
    fun usage(uri: String?): TranslationTokenUsage? = ledger.total(uri)
    fun usageSessions(uri: String?): List<TranslationUsageSession> = ledger.sessions(uri)
    private fun recordUsage(id: String, uri: String, usage: TranslationTokenUsage, language: String = "") {
        val session = sessions.getOrPut(id) { Session("", language, System.currentTimeMillis()) }
        ledger.record(id, uri, session.model, session.language.ifBlank { language }, session.startedAt, usage)
    }
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

    fun receiveTranslation(update: TranslationUpdate, sessionId: String = currentSession) {
        if (update.pending) {
            activeUsageUris[sessionId] = update.uri
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
        activeUsageUris.remove(sessionId)
        val pendingUsage = pendingUsages.remove(sessionId)
        if (!update.restored) (pendingUsage ?: update.tokenUsage)?.let {
            recordUsage(sessionId, update.uri, it, update.targetLanguage)
        }
        changed()
    }

    fun receiveUsage(update: TokenUsageUpdate, sessionId: String = currentSession) {
        if (update.uri == null) {
            // The sequential server supplies the file URI in its next translation result.
            // Notifications contain cumulative totals, so retain only the latest value.
            val activeUri = activeUsageUris[sessionId]
            if (activeUri != null) {
                recordUsage(sessionId, activeUri, update.usage())
                changed()
            } else pendingUsages[sessionId] = update.usage()
        } else {
            recordUsage(sessionId, update.uri, update.usage())
            changed()
        }
    }

    fun resetRuntimeState() {
        activity.clear()
        pendingUsages.clear()
        activeUsageUris.clear()
        currentSession = java.util.UUID.randomUUID().toString()
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
            if (line.range != null) {
                val span = sourceOffsets(source, line.range)
                if (span != null && source.substring(span.first, span.second) == line.sourceText) {
                    matched.add(SourceBlock(span.first, span.second, line.sourceText))
                }
                return@forEach
            }
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
