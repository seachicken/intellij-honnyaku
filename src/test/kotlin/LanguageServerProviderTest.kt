package honnyaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import com.google.gson.JsonParser
import org.eclipse.lsp4j.jsonrpc.Launcher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Test

class LanguageServerProviderTest {
    @Test fun `range scope distinguishes omitted empty and explicit ranges on the wire`() {
        val gson = com.google.gson.Gson()
        val params = VisibleTranslationParams(VisibleTranslationDocument("file:///a.kt", 1, "code // hello"), 0, 1)
        assertFalse(gson.toJsonTree(params).asJsonObject.has("ranges"))
        assertEquals(0, gson.toJsonTree(params.copy(ranges = emptyList())).asJsonObject["ranges"].asJsonArray.size())
        val range = org.eclipse.lsp4j.Range(org.eclipse.lsp4j.Position(0, 5), org.eclipse.lsp4j.Position(0, 13))
        assertEquals(5, gson.toJsonTree(params.copy(ranges = listOf(range))).asJsonObject["ranges"].asJsonArray[0].asJsonObject["start"].asJsonObject["character"].asInt)
        assertFalse(supportsRangeTranslation(null))
        assertTrue(supportsRangeTranslation(mapOf("honnyakuRangeTranslation" to true)))
    }

    @Test fun `inline comment replacements preserve code and ignore stale ranges`() {
        val range = org.eclipse.lsp4j.Range(org.eclipse.lsp4j.Position(0, 8), org.eclipse.lsp4j.Position(0, 16))
        val update = TranslationUpdate("file:///a.kt", 1, "English", listOf(ParagraphTranslation("// hello", "// こんにちは", null, range)))
        assertEquals("😀 code // こんにちは", translationDocument("😀 code // hello", listOf(update)).text)
        assertEquals("changed code // hello", translationDocument("changed code // hello", listOf(update)).text)
    }

    @Test fun `custom LSP request transmits an atomic viewport snapshot and accepts null completion`() {
        val response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}"
        val input = "Content-Length: ${response.toByteArray().size}\r\n\r\n$response"
        val output = ByteArrayOutputStream()
        val launcher = Launcher.Builder<HonnyakuLanguageServer>()
            .setLocalService(Any())
            .setRemoteInterface(HonnyakuLanguageServer::class.java)
            .setInput(ByteArrayInputStream(input.toByteArray()))
            .setOutput(output)
            .create()
        val params = VisibleTranslationParams(VisibleTranslationDocument("file:///a.md", 2, "一\n二"), 1, 2)
        val result = launcher.remoteProxy.translateVisible(params)
        val listening = launcher.startListening()
        try {
            assertNull(result.get(5, TimeUnit.SECONDS))
            val message = JsonParser.parseString(output.toString(Charsets.UTF_8).substringAfter("\r\n\r\n")).asJsonObject
            assertEquals("honnyaku/translateVisible", message["method"].asString)
            assertEquals(1, message["params"].asJsonObject["startLine"].asInt)
            assertEquals(2, message["params"].asJsonObject["endLine"].asInt)
            val document = message["params"].asJsonObject["textDocument"].asJsonObject
            assertEquals("一\n二", document["text"].asString)
            assertEquals(2, document["version"].asInt)
        } finally {
            listening.cancel(true)
        }
    }

    @Test fun `viewport windows include five surrounding lines and stay within document`() {
        assertEquals(0 to 8, translationLineWindow(0, 2, 100))
        assertEquals(15 to 36, translationLineWindow(20, 30, 100))
        assertEquals(90 to 100, translationLineWindow(95, 110, 100))
        assertEquals(0 to 1, translationLineWindow(0, 0, 1))
    }

    @Test fun `older servers cannot silently fall back to whole file translation`() {
        assertTrue(supportsViewportTranslation(mapOf("honnyakuViewportTranslation" to true)))
        assertTrue(supportsViewportTranslation(JsonParser.parseString("{\"honnyakuViewportTranslation\":true}")))
        assertFalse(supportsViewportTranslation(mapOf("honnyakuModelCatalog" to true)))
        assertFalse(supportsViewportTranslation(JsonParser.parseString("{\"honnyakuViewportTranslation\":\"true\"}")))
        assertFalse(supportsViewportTranslation(null))
    }

    @Test fun `scrolling accumulates partial translations and edits retain only matching source`() {
        val model = HonnyakuViewModel()
        val first = TranslationUpdate("file:///a.md", 1, "English", listOf(ParagraphTranslation("一", "one", null)))
        val second = first.copy(translations = listOf(ParagraphTranslation("三", "three", null)))
        model.receiveTranslation(first)
        model.receiveTranslation(second)
        assertEquals("one\n二\nthree", translationDocument("一\n二\n三", model.history(first.uri)).text)
        assertEquals("変更\n二\nthree", translationDocument("変更\n二\n三", model.history(first.uri)).text)
    }

    @Test fun `changing server target preserves caches for both directions`() {
        val english = TranslationUpdate("file:///a.md", 1, "English", listOf(ParagraphTranslation("原文", "English text", null)))
        val french = english.copy(targetLanguage = "French", translations = listOf(ParagraphTranslation("原文", "Texte français", null)))
        val saved = listOf(english, french)
        for (language in listOf("English", "French", "English")) {
            val options = translationInitializationOptions("model", saved, language)
            assertEquals(language, options["targetLanguage"])
            assertEquals(saved, options["initialTranslations"])
            assertEquals("model", options["model"])
            val restored = HonnyakuViewModel(options["initialTranslations"] as List<TranslationUpdate>)
            val preview = translationDocument("原文", restored.history(english.uri).filter { it.targetLanguage == language })
            assertEquals(if (language == "English") "English text" else "Texte français", preview.text)
        }
    }
}
