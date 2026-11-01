package honnyaku

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
        model.clearUsage()
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
        model.clearUsage()
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

class TranslationActivityTest {
    private val a = "file:///a.md"
    private fun pending(version: Int, vararg texts: String) = TranslationUpdate(a, version, "English",
        texts.map { ParagraphTranslation(it, null, null) }, pending = true)
    private fun completed(version: Int, source: String, text: String?) = TranslationUpdate(a, version, "English",
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
        model.clearUsage()
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

class TranslationScrollMappingTest {
    @Test
    fun `appending inserting and separating lines retains existing translations`() {
        val history = listOf(result(paragraph("既訳一", "First"), paragraph("既訳二", "Second")))
        assertEquals("First\n新規行", translationDocument("既訳一\n新規行", history).text)
        assertEquals("First\n新規行\nSecond", translationDocument("既訳一\n新規行\n既訳二", history).text)
        assertEquals("First\n\nSecond\n", translationDocument("既訳一\n\n既訳二\n", history).text)
        val updated = history + result(paragraph("新規行", "Added"))
        val display = translationDocument("既訳一\n新規行\n既訳二", updated)
        assertEquals("First\nAdded\nSecond", display.text)
        assertEquals(6, display.displayOffset(4))
    }

    @Test
    fun `legacy multiline results preserve unchanged line translations when a line is inserted`() {
        val history = listOf(result(paragraph("既訳一\n既訳二", "First\nSecond")))
        assertEquals("First\n追加\nSecond", translationDocument("既訳一\n追加\n既訳二", history).text)
        assertEquals("First\n\nSecond", translationDocument("既訳一\n\n既訳二", history).text)
    }

    private fun result(vararg paragraphs: ParagraphTranslation) =
        TranslationUpdate("file:///a.md", 1, "English", paragraphs.toList())

    private fun paragraph(source: String, translated: String = "translated") =
        ParagraphTranslation(source, translated, null)

    @Test
    fun `untranslated document is identical including syntax blank lines and indentation`() {
        val source = "# 日本語\n\n- 項目\n\n```kotlin\n    val x = 1\n```\n"
        val display = translationDocument(source, emptyList())
        assertEquals(source, display.text)
        (0..source.length).forEach { assertEquals(it, display.displayOffset(it)) }
    }

    @Test
    fun `only translated paragraph changes and later save keeps previous results`() {
        val source = "# Header\n\nfirst\n\nunchanged\n\nsecond\n"
        val history = listOf(result(paragraph("second", "last")), result(paragraph("first", "new\ntranslation")))
        val display = translationDocument(source, history)
        assertEquals("# Header\n\nnew\ntranslation\n\nunchanged\n\nlast\n", display.text)
        assertEquals(display.text.indexOf("unchanged"), display.displayOffset(source.indexOf("unchanged")))
        assertEquals(display.text.indexOf("last"), display.displayOffset(source.indexOf("second")))
        assertEquals(display.text.length, display.displayOffset(source.length))
    }

    @Test
    fun `edited and deleted paragraphs do not bring stale results into the document`() {
        val source = "new text\n\nprefix source\n\nsources"
        val history = listOf(result(paragraph("old text"), paragraph("source"), paragraph("deleted")))
        assertEquals(source, translationDocument(source, history).text)
        assertEquals("translated\nnew line", translationDocument("source\nnew line", history).text)
    }

    @Test
    fun `whitespace separator lines and trailing newlines are retained exactly`() {
        val display = translationDocument("first\n  \n\t\nsecond\n", listOf(result(paragraph("first", "one"))))
        assertEquals("one\n  \n\t\nsecond\n", display.text)
    }

    @Test
    fun `failed translation retains source or previous successful result`() {
        val error = ParagraphTranslation("source", null, "failed")
        assertEquals("source", translationDocument("source", listOf(result(error))).text)
        assertEquals("good", translationDocument("source", listOf(result(paragraph("source", "good")), result(error))).text)
    }

    @Test
    fun `latest successful result replaces each identical multiline occurrence`() {
        val original = "日本語\nsecond line"
        val source = "$original\n\n$original"
        val history = listOf(result(paragraph(original, "old")), result(paragraph(original, "new")))
        val display = translationDocument(source, history)
        assertEquals("new\n\nnew", display.text)
        assertEquals(5, display.displayOffset(source.lastIndexOf("日本語")))
    }

    @Test
    fun `overlapping historical paragraphs are not replaced twice`() {
        val source = "first\nsecond\n\nend"
        val history = listOf(result(paragraph("first\nsecond", "both"), paragraph("second", "part")))
        assertEquals("first\npart\n\nend", translationDocument(source, history).text)
    }

    @Test
    fun `empty document and empty translation map safely`() {
        assertEquals(TranslationDocument("", emptyList()), translationDocument("", emptyList()))
        assertEquals(0, translationDocument("", emptyList()).displayOffset(0))
        val display = translationDocument("first\n\nend", listOf(result(paragraph("first", ""))))
        assertEquals("\n\nend", display.text)
        assertEquals(0, display.displayOffset(3))
        assertEquals(2, display.displayOffset(7))
    }
}

class TranslationSaveListenerTest : BasePlatformTestCase() {
    fun testExplicitSaveOfUnchangedFileAndSaveAll() {
        val file = myFixture.addFileToProject("source.txt", "untouched").virtualFile
        myFixture.openFileInEditor(file)
        val document = myFixture.editor.document
        val saved = mutableListOf<Document>()
        val listener = TranslationSaveListener(project) { saved.add(it) }
        project.messageBus.connect(testRootDisposable).subscribe(FileDocumentManagerListener.TOPIC, listener)
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        FileDocumentManager.getInstance().saveDocument(document)
        assertEquals(listOf(document), saved)
        saved.clear()
        // The Save All implementation does not emit any per-document callbacks for this file.
        FileDocumentManager.getInstance().saveAllDocuments()
        assertTrue(saved.isEmpty())
        val action = ActionManager.getInstance().getAction("SaveAll")
        val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.MAIN_MENU,
            SimpleDataContext.getProjectContext(project))
        listener.beforeActionPerformed(action, event)
        listener.afterActionPerformed(action, event, AnActionResult.PERFORMED)
        assertEquals(listOf(document), saved)
    }

    fun testModifiedFileLeavesNotificationToBuiltInLsp() {
        val file = myFixture.addFileToProject("source.txt", "old").virtualFile
        myFixture.openFileInEditor(file)
        val document = myFixture.editor.document
        val saved = mutableListOf<String>()
        val listener = TranslationSaveListener(project) { saved.add(it.text) }
        project.messageBus.connect(testRootDisposable).subscribe(FileDocumentManagerListener.TOPIC, listener)
        WriteCommandAction.runWriteCommandAction(project) { document.setText("new") }
        assertTrue(saved.isEmpty())
        FileDocumentManager.getInstance().saveDocument(document)
        assertTrue(saved.isEmpty())
        val action = ActionManager.getInstance().getAction("SaveAll")
        val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.MAIN_MENU,
            SimpleDataContext.getProjectContext(project))
        WriteCommandAction.runWriteCommandAction(project) { document.setText("newer") }
        listener.beforeActionPerformed(action, event)
        FileDocumentManager.getInstance().saveAllDocuments()
        listener.afterActionPerformed(action, event, AnActionResult.PERFORMED)
        assertTrue(saved.isEmpty())
    }
}
