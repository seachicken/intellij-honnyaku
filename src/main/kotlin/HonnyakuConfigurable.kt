package honnyaku

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.SimpleListCellRenderer

class HonnyakuConfigurable : BoundConfigurable("Honnyaku") {
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
            ).comment("Preferred translates into this language. Choose Auto or another language from the Target list.")
        }
    }
}
