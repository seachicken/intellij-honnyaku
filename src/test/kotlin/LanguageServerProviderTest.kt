package honnyaku

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageServerProviderTest {
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
