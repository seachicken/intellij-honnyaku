package honnyaku

import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.platform.lsp.api.LspServer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.ActionEvent
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.junit.Assert.assertThrows

class TranslationSessionControllerTest : BasePlatformTestCase() {
    // Exercise Swing timer callbacks without the fixture's implicit read lock.
    override fun runInDispatchThread() = name != "testViewportTimerAcquiresWriteIntentReadAccess"

    fun testCommentsAndStringContentsExcludeMarkup() {
        val file = myFixture.configureByText("example.xml", "<a value=\"// text\"><!-- hello --></a>")
        val document = myFixture.editor.document
        val ranges = textTranslationRanges(project, document)!!
        assertEquals(listOf("// text", "<!-- hello -->"), ranges.map { range ->
            val span = sourceOffsets(document.text, range)!!
            document.text.substring(span.first, span.second)
        })
        assertFalse(isWholeDocumentTranslation(file.virtualFile))
    }

    fun testMultilineCommentsKeepOriginalPositionsAfterAnEdit() {
        myFixture.configureByText("example.xml", "<a><!-- first\nsecond --></a>")
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "\n")
        }
        assertNull(textTranslationRanges(project, document))
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val range = textTranslationRanges(project, document)!!.single()
        assertEquals(1, range.start.line)
        assertEquals(3, range.start.character)
        assertEquals(2, range.end.line)
        assertEquals(10, range.end.character)
    }

    fun testYamlStringsAndBlockScalarsExcludeKeysAndDelimiters() {
        val source = "\"title\": \"Hello world\"\nplain: Welcome home\nliteral: |\n  First line\n  Second line\nfolded: >\n  Another line\n  Continued\n# A comment\n"
        myFixture.configureByText("example.yaml", source)
        val document = myFixture.editor.document
        val contents = textTranslationRanges(project, document)!!.map { range ->
            val span = sourceOffsets(source, range)!!
            source.substring(span.first, span.second)
        }
        assertEquals(listOf("Hello world", "Welcome home", "First line\n  Second line", "Another line\n  Continued", "# A comment"), contents)
    }

    fun testEmptyStringContentsAreSkipped() {
        myFixture.configureByText("example.xml", "<a empty=\"\" blank=\"   \"/>")
        assertEmpty(textTranslationRanges(project, myFixture.editor.document)!!)
    }

    fun testPlainTextUsesWholeDocument() {
        val file = myFixture.configureByText("example.txt", "hello")
        assertTrue(isWholeDocumentTranslation(file.virtualFile))
    }

    fun testAsciiDocUsesWholeDocumentWithALanguageFileType() {
        // Simulate the AsciiDoc plugin so plain-text fallback cannot hide a regression.
        val fileType = TestAsciiDocFileType()
        for (name in listOf("example.adoc", "custom.doc")) {
            val file = LightVirtualFile(name, fileType, "= Title\n\n本文")
            assertTrue(name, isWholeDocumentTranslation(file))
        }
    }

    private class TestAsciiDocFileType : LanguageFileType(Language.ANY) {
        override fun getName() = "AsciiDoc"
        override fun getDescription() = "AsciiDoc"
        override fun getDefaultExtension() = "adoc"
        override fun getIcon(): Icon? = null
    }

    private val params = VisibleTranslationParams(VisibleTranslationDocument("file:///source.txt", 3, "edited"), 0, 1)

    private fun server(events: MutableList<String>, errorCode: Int? = null): LspServer {
        var first = true
        val documents = proxy<TextDocumentService> { name, args ->
            if (name == "didOpen") {
                val doc = (args[0] as DidOpenTextDocumentParams).textDocument
                assertEquals(params.textDocument.uri, doc.uri)
                assertEquals(params.textDocument.version, doc.version)
                assertEquals(params.textDocument.text, doc.text)
                assertEquals("plaintext", doc.languageId)
                events.add("open")
            }
            null
        }
        val remote = proxy<HonnyakuLanguageServer> { name, args ->
            when (name) {
                "getTextDocumentService" -> documents
                "translateVisible" -> {
                    assertSame(params, args[0])
                    events.add("translate")
                    if (first && errorCode != null) {
                        first = false
                        CompletableFuture.failedFuture<Any?>(ResponseErrorException(ResponseError(errorCode, "failure", null)))
                    } else CompletableFuture.completedFuture<Any?>(null)
                }
                else -> error(name)
            }
        }
        return proxy { name, args ->
            assertEquals("sendRequestSync", name)
            @Suppress("UNCHECKED_CAST")
            val request = args[1] as (LanguageServer) -> CompletableFuture<Any?>
            request(remote).get()
        }
    }

    fun testDiffOnlyDocumentIsOpenedBeforeItsFirstTranslation() {
        val events = mutableListOf<String>()
        translateSnapshot(server(events), params, true, "plaintext")
        assertEquals(listOf("open", "translate"), events)
    }

    fun testNormalEditorUsesPlatformDocumentSynchronization() {
        val events = mutableListOf<String>()
        translateSnapshot(server(events), params, false, "plaintext")
        assertEquals(listOf("translate"), events)
    }

    fun testNormalEditorFailuresDoNotReopenDocuments() {
        val events = mutableListOf<String>()
        assertThrows(Exception::class.java) {
            translateSnapshot(server(events, -32801), params, false, "plaintext")
        }
        assertEquals(listOf("translate"), events)
    }

    fun testAnEditBeforeTheRequestPreventsOpeningAnOutdatedDiffSnapshot() {
        val events = mutableListOf<String>()
        translateSnapshot(server(events), params, true, "plaintext") { false }
        assertEmpty(events)
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args ?: emptyArray())
        } as T

    fun testViewportTimerAcquiresWriteIntentReadAccess() {
        val result = CompletableFuture<Boolean>()
        val timer = translationViewportTimer {
            result.complete(ApplicationManager.getApplication().isReadAccessAllowed &&
                ApplicationManager.getApplication().isDispatchThread)
        }
        timer.actionListeners.single().actionPerformed(ActionEvent(timer, ActionEvent.ACTION_PERFORMED, "test"))
        assertTrue(result.get(15, TimeUnit.SECONDS))
    }
}
