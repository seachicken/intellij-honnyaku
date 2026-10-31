package honnyaku

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

class HonnyakuToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(HonnyakuTranslationPanel(), null, false)
        )
    }
}

class HonnyakuTranslationPanel : JPanel(BorderLayout()) {
    private val text = JBTextArea("Edit a Markdown file and save it to translate changed paragraphs into English.").apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    init { add(JBScrollPane(text), BorderLayout.CENTER) }

    fun showTranslation(update: TranslationUpdate) {
        text.text = buildString {
            appendLine(update.uri)
            appendLine("→ ${update.targetLanguage}")
            update.translations.forEach {
                appendLine()
                appendLine(it.error?.let { error -> "Translation failed: $error" } ?: it.text.orEmpty())
            }
        }
        text.caretPosition = 0
    }
}
