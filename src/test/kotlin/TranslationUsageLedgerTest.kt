package honnyaku

import org.junit.Assert.*
import org.junit.Test

class TranslationUsageLedgerTest {
    private val uri = "file:///a.md"

    private fun session(id: String = "first", file: String = uri, language: String = "en",
                        model: String = "model-a", usage: TranslationTokenUsage = TranslationTokenUsage(80, 60, 20, 100)) =
        TranslationUsageSession(id, file, model, language, 1, usage)

    private fun TranslationUsageLedger.record(session: TranslationUsageSession) =
        record(session.id, session.uri, session.model, session.targetLanguage, session.startedAt, session.usage)

    @Test fun `same session replaces cumulative usage and duplicate notifications do not save again`() {
        val saved = mutableListOf<List<TranslationUsageSession>>()
        val ledger = TranslationUsageLedger(emptyList()) { saved.add(it) }
        val first = session()
        ledger.record(first)
        ledger.record(first)
        val latest = first.copy(usage = TranslationTokenUsage(160, 120, 40, 200))
        ledger.record(latest.copy(uri = "file:/a.md"))

        assertEquals(latest.usage, ledger.total(uri))
        assertEquals(1, ledger.sessions(uri).size)
        assertEquals(2, saved.size)
        assertEquals(first, saved.first().single())
    }

    @Test fun `file total includes all sessions across languages and models`() {
        val ledger = TranslationUsageLedger(emptyList()) {}
        ledger.record(session())
        ledger.record(session(id = "second", language = "fr", model = "model-b",
            usage = TranslationTokenUsage(40, 10, 10, 50)))

        assertEquals(TranslationTokenUsage(120, 70, 30, 150), ledger.total(uri))
        assertEquals(listOf("en", "fr"), ledger.sessions(uri).map { it.targetLanguage })
        assertEquals(listOf("model-a", "model-b"), ledger.sessions(uri).map { it.model })
    }

    @Test fun `same connection can record separate file threads and unknown files have no usage`() {
        val ledger = TranslationUsageLedger(emptyList()) {}
        ledger.record(session())
        ledger.record(session(file = "file:///b.md", usage = TranslationTokenUsage(totalTokens = 20)))

        assertEquals(100L, ledger.total(uri)?.totalTokens)
        assertEquals(20L, ledger.total("file:///b.md")?.totalTokens)
        assertNull(ledger.total("file:///unknown.md"))
        assertTrue(ledger.sessions(null).isEmpty())
    }

    @Test fun `restored sessions remain in totals when a new session is recorded`() {
        val previous = session()
        var saved = emptyList<TranslationUsageSession>()
        val ledger = TranslationUsageLedger(listOf(previous)) { saved = it }
        assertEquals(previous.usage, ledger.total(uri))
        val next = session(id = "second", usage = TranslationTokenUsage(totalTokens = 20))
        ledger.record(next)

        assertEquals(120L, ledger.total(uri)?.totalTokens)
        assertEquals(listOf(previous, next), saved)
    }
}
