package honnyaku

import org.junit.Assert.*
import org.junit.Test

class HonnyakuSettingsTest {
    @Test fun `translation preferences restore together without project state`() {
        val preferences = HonnyakuPreferences()
        preferences.state.apply {
            model = "  chosen-model  "
            preferredLanguage = "en"
        }
        val restored = HonnyakuPreferences()
        restored.loadState(preferences.state.copy())
        assertEquals(HonnyakuPreferences.SettingsState(
            model = "chosen-model",
            preferredLanguage = "en",
        ), restored.state)
    }

    @Test fun `projects share model and preferred language but keep target and preview separate`() {
        val global = HonnyakuPreferences.SettingsState()
        val firstState = HonnyakuSettings.SettingsState()
        val secondState = HonnyakuSettings.SettingsState()
        val first = TranslationPreferences(global, firstState)
        val second = TranslationPreferences(global, secondState)
        first.model = "chosen-model"
        first.preferredLanguage = "en"
        first.targetLanguage = "fr"
        first.previewPreferred = true
        assertEquals("chosen-model", second.model)
        assertEquals("en", second.preferredLanguage)
        assertEquals("Auto", second.targetLanguage)
        assertFalse(second.previewPreferred)
        assertEquals(HonnyakuSettings.SettingsState("fr", true), firstState.copy())
    }

    @Test fun `unsupported language preferences fall back to defaults`() {
        for (language in listOf("Japanese", "English", "Chinese", "Chinese (Traditional, Taiwan)", "unsupported", "")) {
            val preferences = TranslationPreferences(
                HonnyakuPreferences.SettingsState(preferredLanguage = language),
                HonnyakuSettings.SettingsState(targetLanguage = language),
            )
            assertEquals("ja", preferences.preferredLanguage)
            assertEquals("Auto", preferences.targetLanguage)
        }
    }

    @Test fun `traditional Chinese target reaches server and preview`() {
        val preferences = TranslationPreferences(
            HonnyakuPreferences.SettingsState(), HonnyakuSettings.SettingsState(),
        )
        preferences.targetLanguage = "zh-Hant"
        val language = preferences.resolvedTarget("source")
        assertEquals("zh-Hant",
            translationInitializationOptions("model", emptyList(), language)["targetLanguage"])
        assertEquals("zh-Hant", languageOption(language)?.code)
        assertEquals("繁體中文 (ZH-HANT)", languageLabel(language))
        assertEquals(language, preferences.previewLanguage("source"))
    }

    @Test fun `unsupported cached languages are ignored while supported results remain`() {
        val storage = TranslationHistory()
        val valid = result()
        storage.save(listOf(valid.copy(targetLanguage = "Chinese"), valid,
            valid.copy(targetLanguage = "unsupported")))
        assertEquals(listOf(valid), storage.read())
    }

    private val uri = "file:///a.md"
    private fun result(restored: Boolean = false) = TranslationUpdate(uri, 1, "en",
        listOf(ParagraphTranslation("原文", "Text", null)), restored = restored)

    @Test fun `saving translations and usage preserves both in the same state`() {
        val storage = TranslationHistory()
        val session = TranslationUsageSession("first", uri, "model", "en", 1, TranslationTokenUsage(totalTokens = 100))
        storage.save(listOf(result()))
        storage.saveUsage(listOf(session))
        storage.save(listOf(result().copy(version = 2)))
        val restored = TranslationHistory()
        restored.loadState(storage.state.copy())
        assertEquals(2, restored.read().single().version)
        assertEquals(listOf(session), restored.readUsage())
        restored.saveUsage(listOf(session.copy(usage = TranslationTokenUsage(totalTokens = 200))))
        assertEquals(2, restored.read().single().version)
        assertEquals(200L, restored.readUsage().single().usage.totalTokens)
    }

    @Test fun `existing translation-only state restores with empty usage`() {
        val storage = TranslationHistory()
        storage.save(listOf(result()))
        val restored = TranslationHistory()
        restored.loadState(TranslationHistory.HistoryState(storage.state.results))
        assertEquals(listOf(result()), restored.read())
        assertTrue(restored.readUsage().isEmpty())
    }

    @Test fun `invalid usage storage does not prevent opening the project`() {
        val storage = TranslationHistory()
        storage.loadState(TranslationHistory.HistoryState(sessions = "invalid"))
        assertTrue(storage.readUsage().isEmpty())
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
        TranslationUpdate("file:///a.md", 1, "en", paragraphs.toList())

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
