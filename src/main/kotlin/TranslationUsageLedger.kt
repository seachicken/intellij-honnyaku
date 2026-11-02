package honnyaku

import java.net.URI

data class TranslationUsageSession(
    val id: String,
    val uri: String,
    val model: String,
    val targetLanguage: String,
    val startedAt: Long,
    val usage: TranslationTokenUsage,
)

/**
 * Tracks cumulative token usage per client connection ID and normalized file URI.
 * Callers supply session IDs and metadata; record() replaces the session total, rather than adding a delta.
 * total() sums all recorded sessions for a file across languages and models. Cached input is already
 * included in inputTokens. Files without usage return null; files without sessions return an empty list.
 * Restored records participate in the same totals. Only changes invoke save(), with a complete snapshot.
 */
internal class TranslationUsageLedger(
    restored: List<TranslationUsageSession>,
    private val save: (List<TranslationUsageSession>) -> Unit,
) {
    private val records = restored.toMutableList()
    fun sessions(uri: String?): List<TranslationUsageSession> =
        if (uri == null) emptyList() else records.filter { URI(it.uri).normalize() == URI(uri).normalize() }

    fun record(id: String, uri: String, model: String, language: String, startedAt: Long, usage: TranslationTokenUsage) {
        val index = records.indexOfFirst { it.id == id && URI(it.uri).normalize() == URI(uri).normalize() }
        val record = TranslationUsageSession(id, uri, model, language, startedAt, usage)
        if (index >= 0) {
            if (records[index] == record) return
            records[index] = record
        } else records.add(record)
        save(records.toList())
    }

    fun total(uri: String?): TranslationTokenUsage? = sessions(uri).takeIf { it.isNotEmpty() }?.fold(TranslationTokenUsage()) { sum, session ->
        val usage = session.usage
        TranslationTokenUsage(sum.inputTokens + usage.inputTokens, sum.cachedInputTokens + usage.cachedInputTokens,
            sum.outputTokens + usage.outputTokens, sum.totalTokens + usage.totalTokens)
    }
}
