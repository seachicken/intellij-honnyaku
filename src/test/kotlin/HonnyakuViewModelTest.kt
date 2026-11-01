package honnyaku

import org.junit.Assert.*
import org.junit.Test

class HonnyakuViewModelTest {
    @Test
    fun `catalog remains available when switching models clears runtime usage`() {
        val model = HonnyakuViewModel()
        val catalog = TranslationModelCatalog(listOf(AvailableTranslationModel("a", supportedReasoningEfforts = listOf(ReasoningEffortOption("low")))))
        model.receiveModelCatalog(catalog)
        model.receiveReasoningEffort(ReasoningEffortUpdate(a, "low"))
        model.clearUsage()
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
        model.clearUsage()
        assertNull(model.reasoningEffort(b))
    }

    private val a = "file:///a.md"
    private val b = "file:///b.md"
    private fun translation(uri: String, text: String = "translated") =
        TranslationUpdate(uri, 1, "English", listOf(ParagraphTranslation("source", text, null)))

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
    fun `reconnecting drops both recorded and pending usage but retains translations`() {
        val model = HonnyakuViewModel()
        val result = translation(a)
        model.receiveUsage(TokenUsageUpdate(totalTokens = 100))
        model.receiveTranslation(result)
        model.receiveUsage(TokenUsageUpdate(totalTokens = 200))
        model.clearUsage()
        model.receiveTranslation(translation(b))
        assertNull(model.usage(a))
        assertNull(model.usage(b))
        assertEquals(result, model.translation(a))
        model.receiveUsage(TokenUsageUpdate(totalTokens = 10))
        model.receiveTranslation(translation(a))
        assertEquals(10L, model.usage(a)?.totalTokens)
    }
}
