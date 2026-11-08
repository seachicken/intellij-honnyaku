package honnyaku

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.SimpleListCellRenderer

class HonnyakuConfigurable : BoundConfigurable("Honnyaku"), Configurable.NoScroll {
    override fun createPanel() = panel {
        row("Preferred language:") {
            comboBox(
                translationLanguages.toList(),
                SimpleListCellRenderer.create<String> { label, value, _ ->
                    label.text = languageLabel(value)
                },
            ).bindItem(
                { service<HonnyakuPreferences>().state.preferredLanguage },
                { value -> value?.let { service<HonnyakuPreferences>().updatePreferredLanguage(it) } },
            ).comment(
                "Translations use this language when Target is set to Preferred.",
                maxLineLength = MAX_LINE_LENGTH_WORD_WRAP,
            )
        }
    }
}
