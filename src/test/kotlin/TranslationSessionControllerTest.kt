package honnyaku

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TranslationSessionControllerTest : BasePlatformTestCase() {
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
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(0, "\n")
        }
        assertNull(textTranslationRanges(project, document))
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
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
            val file = com.intellij.testFramework.LightVirtualFile(name, fileType, "= Title\n\n本文")
            assertTrue(name, isWholeDocumentTranslation(file))
        }
    }

    private class TestAsciiDocFileType : com.intellij.openapi.fileTypes.LanguageFileType(com.intellij.lang.Language.ANY) {
        override fun getName() = "AsciiDoc"
        override fun getDescription() = "AsciiDoc"
        override fun getDefaultExtension() = "adoc"
        override fun getIcon(): javax.swing.Icon? = null
    }
}
