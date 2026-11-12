package honnyaku

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class TranslationSourceTest : BasePlatformTestCase() {
    // Source selection also runs from Swing callbacks without model read access.
    override fun runInDispatchThread() = name != "testSourceLookupFromEdtWithoutReadAccess"

    private fun context() = object : DiffContext() {
        override fun getProject(): Project = this@TranslationSourceTest.project
        override fun isWindowFocused() = true
        override fun isFocusedInWindow() = false
        override fun requestFocusInWindow() {}
    }

    private fun request(): SimpleDiffRequest {
        val file = myFixture.addFileToProject("source.txt", "first\nnew\nlast").virtualFile
        val factory = DiffContentFactory.getInstance()
        // Revisions can have the same highlight file as the actual working copy.
        return SimpleDiffRequest("diff", factory.create(project, "first\nold\nremoved\nlast", file),
            factory.create(project, file), "HEAD", "Working copy")
    }

    fun testDiffExtensionIsRegistered() {
        assertTrue(DiffExtension.EP_NAME.extensionList.any { it is TranslationDiffExtension })
    }

    fun testSideBySideUsesWorkingCopyAndEditsShareItsDocument() {
        val viewer = SimpleDiffViewer(context(), request())
        try {
            viewer.init()
            val source = diffTranslationSource(project, viewer)!!
            assertSame(viewer.editor2, source.editor)
            assertSame(FileDocumentManager.getInstance().getDocument(source.file), source.document)
            assertTrue(source.isDiff)
            WriteCommandAction.runWriteCommandAction(project) {
                viewer.editor2.document.insertString(0, "edited\n")
            }
            assertTrue(source.document.text.startsWith("edited\n"))
            assertFalse(viewer.editor1.document.text.startsWith("edited\n"))
        } finally { Disposer.dispose(viewer) }
    }

    fun testUnifiedUsesOriginalWorkingDocumentAndMapsPastDeletedLines() {
        val viewer = UnifiedDiffViewer(context(), request())
        try {
            viewer.init()
            viewer.rediff(true)
            val source = diffTranslationSource(project, viewer)!!
            assertSame(viewer.editor, source.editor)
            assertNotSame(viewer.editor.document, source.document)
            assertEquals("first\nnew\nlast", source.document.text)
            val lastLine = viewer.editor.document.text.lines().indexOf("last")
            assertTrue(lastLine > 2)
            assertEquals(2, source.sourceLine(lastLine))
            assertEquals(source.document.getLineStartOffset(2),
                source.sourceOffset(viewer.editor.document.getLineStartOffset(lastLine)))
        } finally { Disposer.dispose(viewer) }
    }

    fun testRevisionOnlyDiffDoesNotTranslateHighlightFile() {
        val file = myFixture.addFileToProject("source.txt", "actual").virtualFile
        val factory = DiffContentFactory.getInstance()
        val viewer = SimpleDiffViewer(context(), SimpleDiffRequest("history",
            factory.create(project, "old", file), factory.create(project, "new", file), "old", "new"))
        try {
            viewer.init()
            assertNull(diffTranslationSource(project, viewer))
        } finally { Disposer.dispose(viewer) }
    }

    fun testSourceLookupFromEdtWithoutReadAccess() {
        lateinit var file: VirtualFile
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            file = myFixture.addFileToProject("source.txt", "hello").virtualFile
            FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)
        }
        val result = CompletableFuture<Unit>()
        SwingUtilities.invokeLater {
            try {
                assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
                assertTrue(isTranslationFile(file))
                val source = project.service<TranslationSources>().current!!
                assertEquals(file, source.file)
                assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
                result.complete(Unit)
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        result.get(15, TimeUnit.SECONDS)
    }
}
