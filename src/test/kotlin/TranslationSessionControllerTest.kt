package honnyaku

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TranslationSessionControllerTest : BasePlatformTestCase() {
    fun testCommentsExcludeMarkupAndCommentLikeStrings() {
        val file = myFixture.configureByText("example.xml", "<a value=\"// text\"><!-- hello --></a>")
        val document = myFixture.editor.document
        val ranges = commentTranslationRanges(project, document)!!
        assertEquals(1, ranges.size)
        val span = sourceOffsets(document.text, ranges.single())!!
        assertEquals("<!-- hello -->", document.text.substring(span.first, span.second))
        assertFalse(isWholeDocumentTranslation(file.virtualFile))
    }

    fun testMultilineCommentsKeepOriginalPositionsAfterAnEdit() {
        myFixture.configureByText("example.xml", "<a><!-- first\nsecond --></a>")
        val document = myFixture.editor.document
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "\n")
        }
        assertNull(commentTranslationRanges(project, document))
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
        val range = commentTranslationRanges(project, document)!!.single()
        assertEquals(1, range.start.line)
        assertEquals(3, range.start.character)
        assertEquals(2, range.end.line)
        assertEquals(10, range.end.character)
    }

    fun testPlainTextUsesWholeDocument() {
        val file = myFixture.configureByText("example.txt", "hello")
        assertTrue(isWholeDocumentTranslation(file.virtualFile))
    }
}
