package honnyaku

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TranslationLanguageControlsTest : BasePlatformTestCase() {
    private fun event(action: AnAction) =
        AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT)

    fun testTitleMenuReflectsAutomaticAndExplicitLanguage() {
        val settings = TranslationPreferences(HonnyakuPreferences.SettingsState(), HonnyakuSettings.SettingsState())
        settings.targetLanguage = "Auto"
        val controls = TranslationLanguageControls(settings, {}, {})
        val target = controls.actions.first() as ComboBoxAction
        assertTrue(target.isSmallVariant)
        controls.updateTarget("ja")
        val automatic = event(target)
        target.update(automatic)
        assertEquals("Auto (JA)", automatic.presentation.text)

        settings.targetLanguage = "fr"
        val explicit = event(target)
        target.update(explicit)
        assertEquals("FR", explicit.presentation.text)
    }

    fun testHeaderActionsRetainRetranslateAndSettings() {
        var cleared = false
        var openedSettings = false
        val controls = TranslationLanguageControls(
            TranslationPreferences(HonnyakuPreferences.SettingsState(), HonnyakuSettings.SettingsState()),
            {}, { openedSettings = true }, { cleared = true },
        )
        val refresh = controls.actions[1]
        val unavailable = event(refresh)
        refresh.update(unavailable)
        assertFalse(unavailable.presentation.isEnabled)
        controls.updateCacheAction("日本語", true)
        val available = event(refresh)
        refresh.update(available)
        assertTrue(available.presentation.isEnabled)
        refresh.actionPerformed(available)
        assertTrue(cleared)
        controls.actions[2].let { it.actionPerformed(event(it)) }
        assertTrue(openedSettings)
    }
}
