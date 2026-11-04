package honnyaku

import org.junit.Assert.*
import org.junit.Test
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Position

class HonnyakuViewModelTest {
    @Test
    fun `history keeps earlier paragraphs and ignores empty and repeated notifications`() {
        val model = HonnyakuViewModel()
        val first = translation(a)
        val second = translation(a, "new translation").copy(version = 2)
        model.receiveTranslation(first)
        model.receiveTranslation(second)
        model.receiveTranslation(second.copy(version = 3))
        model.receiveTranslation(second.copy(translations = emptyList()))
        model.receiveTranslation(translation(b))
        model.resetRuntimeState()
        assertEquals(listOf(first, second), model.history("file:/a.md"))
        assertEquals("new translation", model.translation(a)?.translations?.single()?.text)
        assertEquals(1, model.history(b).size)
    }

    @Test
    fun `IntelliJ persists results and seeds a restarted server without runtime usage`() {
        val result = translation(a, "line one\n<text> & 日本語")
        val storage = TranslationHistory()
        val model = HonnyakuViewModel(saveHistory = storage::save)
        model.receiveUsage(TokenUsageUpdate(totalTokens = 100))
        model.receiveTranslation(result)
        val reloadedStorage = TranslationHistory()
        reloadedStorage.loadState(storage.state.copy())
        val restored = HonnyakuViewModel(reloadedStorage.read(), reloadedStorage::save)
        assertEquals(listOf(result), restored.history(a))
        assertEquals(result, restored.translation(a))
        assertEquals(a, restored.selectedUri)
        assertNull(restored.usage(a))
        val newer = translation(a, "new")
        restored.receiveTranslation(newer)
        assertEquals(listOf(result, newer), reloadedStorage.read())
        val options = translationInitializationOptions("", reloadedStorage.read())
        assertEquals(setOf("initialTranslations"), options.keys)
        assertEquals(listOf(result, newer), options["initialTranslations"])
        assertEquals(mapOf("model" to "model"), translationInitializationOptions("model", emptyList()))
    }

    @Test
    fun `progress and usage changes are not persisted as translation results`() {
        val storage = TranslationHistory()
        val model = HonnyakuViewModel(saveHistory = storage::save)
        val result = translation(a)
        model.receiveTranslation(result.copy(pending = true))
        model.receiveUsage(TokenUsageUpdate(uri = a, totalTokens = 10))
        model.selectedUri = b
        model.clearActivity()
        assertTrue(storage.read().isEmpty())
        model.receiveTranslation(result)
        assertEquals(listOf(result), storage.read())
        val saved = storage.state.results
        model.receiveTranslation(result.copy(version = 2))
        model.resetRuntimeState()
        assertEquals(saved, storage.state.results)
    }

    @Test
    fun `invalid workspace cache does not prevent opening translations`() {
        val storage = TranslationHistory()
        storage.loadState(TranslationHistory.HistoryState("broken json"))
        assertTrue(storage.read().isEmpty())
    }

    @Test
    fun `catalog remains available when switching models clears runtime usage`() {
        val model = HonnyakuViewModel()
        val catalog = TranslationModelCatalog(listOf(AvailableTranslationModel("a", supportedReasoningEfforts = listOf(ReasoningEffortOption("low")))))
        model.receiveModelCatalog(catalog)
        model.receiveReasoningEffort(ReasoningEffortUpdate(a, "low"))
        model.resetRuntimeState()
        assertEquals(catalog, model.modelCatalog)
        assertNull(model.reasoningEffort(a))
    }

    @Test
    fun `reasoning displays reported values per file and becomes unknown after reset`() {
        val model = HonnyakuViewModel()
        assertNull(model.reasoningEffort(a))
        model.receiveReasoningEffort(ReasoningEffortUpdate(a, "medium"))
        model.receiveReasoningEffort(ReasoningEffortUpdate(b, "low"))
        assertEquals("medium", model.reasoningEffort("file:/a.md"))
        assertEquals("low", model.reasoningEffort(b))
        model.receiveReasoningEffort(ReasoningEffortUpdate(a))
        assertNull(model.reasoningEffort(a))
        model.resetRuntimeState()
        assertNull(model.reasoningEffort(b))
    }

    @Test fun `usage persists across reload and restored translations do not charge it again`() {
        val storage = TranslationHistory()
        val model = HonnyakuViewModel(saveSessions = storage::saveUsage)
        model.beginUsageSession("first", "model", "en")
        model.receiveTranslation(translation(a).copy(pending = true), "first")
        model.receiveUsage(TokenUsageUpdate(totalTokens = 100), "first")
        // Persist during translation, even if inference is subsequently interrupted.
        val reloaded = TranslationHistory()
        reloaded.loadState(storage.state.copy())
        val restored = HonnyakuViewModel(restoredSessions = reloaded.readUsage(), saveSessions = reloaded::saveUsage)
        assertEquals(100L, restored.usage(a)?.totalTokens)
        restored.receiveTranslation(translation(a).copy(restored = true, tokenUsage = TranslationTokenUsage(totalTokens = 100)))
        assertEquals(100L, restored.usage(a)?.totalTokens)
        restored.beginUsageSession("second", "model", "fr")
        restored.receiveUsage(TokenUsageUpdate(a, totalTokens = 20), "second")
        assertEquals(120L, restored.usage(a)?.totalTokens)
        assertEquals(2, reloaded.readUsage().size)
    }

    @Test fun `unkeyed notifications from separate connections never mix`() {
        val model = HonnyakuViewModel()
        model.beginUsageSession("first", "model", "en")
        model.beginUsageSession("second", "model", "fr")
        model.receiveUsage(TokenUsageUpdate(totalTokens = 100), "first")
        model.receiveUsage(TokenUsageUpdate(totalTokens = 20), "second")
        model.receiveTranslation(translation(a).copy(uri = "file:///b.md"), "second")
        model.receiveTranslation(translation(a), "first")
        assertEquals(100L, model.usage(a)?.totalTokens)
        assertEquals(20L, model.usage("file:///b.md")?.totalTokens)
    }

    @Test fun `cache deletion removes only selected file and language and retains usage after reload`() {
        val storage = TranslationHistory()
        val model = HonnyakuViewModel(saveHistory = storage::save, saveSessions = storage::saveUsage)
        val english = translation(a)
        val french = translation(a, "fr").copy(targetLanguage = "fr")
        val otherFile = translation(b)
        model.receiveTranslation(english)
        model.receiveTranslation(french)
        model.receiveTranslation(otherFile)
        model.receiveUsage(TokenUsageUpdate(a, totalTokens = 100))
        model.clearTranslationCache("file:/a.md", "fr")
        assertEquals(listOf(english), model.history(a))
        assertEquals(english, model.translation(a))
        assertEquals(listOf(otherFile), model.history(b))
        model.clearTranslationCache(a, "en")
        assertNull(model.translation(a))
        val restored = HonnyakuViewModel(storage.read(), restoredSessions = storage.readUsage())
        assertTrue(restored.history(a).isEmpty())
        assertEquals(listOf(otherFile), restored.history(b))
        assertEquals(100L, restored.usage(a)?.totalTokens)
        model.receiveTranslation(translation(a, "retried"))
        assertEquals("retried", model.translation(a)?.translations?.single()?.text)
    }

    private val a = "file:///a.md"
    private val b = "file:///b.md"
    private fun translation(uri: String, text: String = "translated") =
        TranslationUpdate(uri, 1, "en", listOf(ParagraphTranslation("source", text, null)))

    @Test
    fun `unkeyed usage belongs to completed translation even when another editor is selected`() {
        val model = HonnyakuViewModel()
        model.selectedUri = b
        model.receiveUsage(TokenUsageUpdate(inputTokens = 80, cachedInputTokens = 60, outputTokens = 20, totalTokens = 100))
        // Receiving usage alone must not attribute it to the selected editor.
        assertNull(model.usage(b))
        val result = translation(a)
        model.receiveTranslation(result)
        assertEquals(b, model.selectedUri)
        assertNull(model.translation(model.selectedUri))
        assertNull(model.usage(model.selectedUri))
        model.selectedUri = a
        assertEquals(result, model.translation(model.selectedUri))
        assertEquals(TranslationTokenUsage(80, 60, 20, 100), model.usage(model.selectedUri))
    }

    @Test
    fun `intermediate and later save totals replace usage instead of being added`() {
        val model = HonnyakuViewModel()
        model.receiveUsage(TokenUsageUpdate(totalTokens = 50))
        model.receiveUsage(TokenUsageUpdate(totalTokens = 100))
        model.receiveTranslation(translation(a))
        assertEquals(100L, model.usage(a)?.totalTokens)
        model.receiveUsage(TokenUsageUpdate(totalTokens = 180))
        model.receiveTranslation(translation(a, "updated"))
        assertEquals(180L, model.usage(a)?.totalTokens)
        assertEquals("updated", model.translation(a)?.translations?.single()?.text)
    }

    @Test
    fun `consumed usage never leaks into the next file and missing usage preserves known totals`() {
        val model = HonnyakuViewModel()
        model.receiveUsage(TokenUsageUpdate(totalTokens = 100))
        model.receiveTranslation(translation(a))
        model.receiveTranslation(translation(b))
        assertNull(model.usage(b))
        model.receiveUsage(TokenUsageUpdate(totalTokens = 200))
        model.receiveTranslation(translation(b))
        model.receiveTranslation(translation(a).copy(translations = emptyList()))
        assertEquals(100L, model.usage(a)?.totalTokens)
        assertEquals(200L, model.usage(b)?.totalTokens)
    }

    @Test
    fun `keyed usage refreshes selected file subscribers without waiting for translation`() {
        val model = HonnyakuViewModel()
        model.selectedUri = "file:/a.md"
        val displayedTotals = mutableListOf<Long?>()
        val unsubscribe = model.subscribe { displayedTotals.add(model.usage(model.selectedUri)?.totalTokens) }
        model.receiveUsage(TokenUsageUpdate(uri = a, totalTokens = 100))
        model.receiveTranslation(translation(a))
        model.selectedUri = b
        model.receiveUsage(TokenUsageUpdate(uri = b, totalTokens = 200))
        model.selectedUri = a
        assertEquals(listOf(100L, 100L, null, 200L, 100L), displayedTotals)
        unsubscribe()
        model.receiveUsage(TokenUsageUpdate(uri = a, totalTokens = 300))
        assertEquals(5, displayedTotals.size)
    }

    @Test
    fun `reconnecting retains recorded usage and translations but drops pending usage`() {
        val model = HonnyakuViewModel()
        val result = translation(a)
        model.receiveUsage(TokenUsageUpdate(totalTokens = 100))
        model.receiveTranslation(result)
        model.receiveUsage(TokenUsageUpdate(totalTokens = 200))
        model.resetRuntimeState()
        model.receiveTranslation(translation(b))
        assertEquals(100L, model.usage(a)?.totalTokens)
        assertNull(model.usage(b))
        assertEquals(result, model.translation(a))
        model.receiveUsage(TokenUsageUpdate(totalTokens = 10))
        model.receiveTranslation(translation(a))
        assertEquals(110L, model.usage(a)?.totalTokens)
    }

    @Test fun `preview uses only history for the selected language`() {
        val model = HonnyakuViewModel()
        model.receiveTranslation(TranslationUpdate("file:///a.md", 1, "en", listOf(ParagraphTranslation("原文", "English text", null))))
        model.receiveTranslation(TranslationUpdate("file:///a.md", 1, "fr", listOf(ParagraphTranslation("原文", "Texte français", null))))
        val history = model.history("file:///a.md")
        assertEquals("English text", translationDocument("原文", history.filter { it.targetLanguage == "en" }).text)
        assertEquals("原文", translationDocument("原文", history.filter { it.targetLanguage == "ja" }).text)
    }
}

class TranslationActivityTest {
    private val a = "file:///a.md"
    private fun pending(version: Int, vararg texts: String) = TranslationUpdate(a, version, "en",
        texts.map { ParagraphTranslation(it, null, null) }, pending = true)
    private fun completed(version: Int, source: String, text: String?) = TranslationUpdate(a, version, "en",
        listOf(ParagraphTranslation(source, text, if (text == null) "failure" else null)))

    @Test
    fun `server progress controls loading and does not overwrite existing results`() {
        val result = completed(1, "source", "translated")
        val model = HonnyakuViewModel(listOf(result))
        model.selectedUri = a
        assertEquals(0, model.pendingCount(a))
        model.receiveTranslation(pending(2, "new"))
        assertEquals(1, model.pendingCount("file:/a.md"))
        assertEquals(listOf(SourceBlock(7, 10, "new")), model.pendingBlocks(a, "source\nnew"))
        assertEquals(result, model.translation(a))
        assertEquals(listOf(result), model.history(a))
        model.receiveTranslation(completed(2, "new", "added"))
        assertEquals(0, model.pendingCount(a))
        assertEquals(2, model.history(a).size)
    }

    @Test
    fun `failure and cancellation stop loading and a server retry starts it again`() {
        val model = HonnyakuViewModel()
        model.receiveTranslation(pending(1, "source"))
        model.receiveTranslation(completed(1, "source", null))
        assertEquals(0, model.pendingCount(a))
        model.receiveTranslation(pending(1, "source"))
        assertEquals(1, model.pendingCount(a))
        model.clearActivity()
        assertEquals(0, model.pendingCount(a))
        model.receiveTranslation(pending(2, "source"))
        model.resetRuntimeState()
        assertEquals(0, model.pendingCount(a))
    }

    @Test
    fun `restoration and older results do not clear current server work`() {
        val model = HonnyakuViewModel()
        model.receiveTranslation(pending(3, "new"))
        model.receiveTranslation(completed(1, "source", "cached").copy(restored = true))
        assertEquals(1, model.pendingCount(a))
        model.receiveTranslation(completed(2, "source", "older"))
        assertEquals(1, model.pendingCount(a))
        model.receiveTranslation(completed(3, "new", "done"))
        assertEquals(0, model.pendingCount(a))
    }

    @Test
    fun `server line ranges disambiguate duplicates and editing hides unmatched shimmer`() {
        val model = HonnyakuViewModel()
        model.receiveTranslation(pending(1, "same").copy(translations = listOf(
            ParagraphTranslation("same", null, null, Range(Position(2, 0), Position(2, 4))),
        )))
        assertEquals(listOf(SourceBlock(6, 10, "same")), model.pendingBlocks(a, "same\n\nsame"))
        assertTrue(model.pendingBlocks(a, "changed").isEmpty())
        assertEquals(1, model.pendingCount(a))
        model.selectedUri = "file:///b.md"
        assertEquals(0, model.pendingCount(model.selectedUri))
        assertEquals(1, model.pendingCount(a))
    }
}
