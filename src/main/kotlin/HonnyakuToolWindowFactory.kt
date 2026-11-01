package honnyaku

import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.ui.JBColor
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.BoxLayout
import javax.swing.JPanel

class HonnyakuToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(HonnyakuTranslationPanel(project.service<HonnyakuSettings>().viewModel), null, false).apply {
                setDisposer(component as HonnyakuTranslationPanel)
            }
        )
    }
}

class HonnyakuTranslationPanel(private val viewModel: HonnyakuViewModel) : JPanel(BorderLayout()), Disposable {
    private val unsubscribe: () -> Unit
    private var usage: TranslationTokenUsage? = null
    private var expanded = false
    private val usageDetails = JBLabel().apply {
        border = JBUI.Borders.emptyTop(6)
        isVisible = false
    }
    private val usageToggle = TokenUsageLink { toggleUsageDetails() }.apply {
        toolTipText = "Cumulative token usage for this file’s translation thread"
        border = JBUI.Borders.compound(
            javax.swing.BorderFactory.createLineBorder(JBColor.border(), 1, true),
            JBUI.Borders.empty(4, 8),
        )
    }
    private val text = JBTextArea("Edit a Markdown file and save it to translate changed paragraphs into English.").apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    init {
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12)
            add(usageToggle)
            add(usageDetails)
        }, BorderLayout.NORTH)
        add(JBScrollPane(text), BorderLayout.CENTER)
        unsubscribe = viewModel.subscribe { refreshSelectedFile() }
        refreshSelectedFile()
    }

    override fun dispose() { unsubscribe() }

    private fun toggleUsageDetails() {
        expanded = !expanded
        refreshUsage()
    }

    private fun refreshUsage() {
        usageToggle.text = usage?.let { "${if (expanded) "▾" else "▸"} Thread: ${it.summary()}" }
            ?: "Thread tokens: unavailable"
        usageToggle.icon = TokenUsageIcon(usage)
        usageToggle.isEnabled = usage != null
        usageToggle.accessibleContext.accessibleName = "Token usage: ${usage?.summary() ?: "unavailable"}"
        usageDetails.text = usage?.details()?.let { "<html>${it.replace(" · ", "<br>")}</html>" }.orEmpty()
        usageDetails.isVisible = expanded && usage != null
        revalidate()
        repaint()
    }

    private fun refreshSelectedFile() {
        val uri = viewModel.selectedUri
        usage = viewModel.usage(uri)
        refreshUsage()
        val update = viewModel.translation(uri)
        text.text = if (update == null) {
            if (uri == null) "Open a Markdown file to see its translation."
            else "Edit this Markdown file and save it to translate changed paragraphs into English."
        } else buildString {
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

internal class TokenUsageLink(onToggle: () -> Unit) : ActionLink("Thread tokens: unavailable", { onToggle() }) {
    init {
        // ActionLink otherwise hides itself when usage is not yet available.
        autoHideOnDisable = false
        isEnabled = false
    }
}

/** Input/output composition, not a percentage of an invented token budget. */
private class TokenUsageIcon(private val usage: TranslationTokenUsage?) : Icon {
    override fun getIconWidth() = JBUI.scale(14)
    override fun getIconHeight() = JBUI.scale(14)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val graphics = g.create() as Graphics2D
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.stroke = BasicStroke(JBUI.scale(2).toFloat())
            val inset = JBUI.scale(2)
            val size = iconWidth - inset * 2
            graphics.color = JBColor.border()
            graphics.drawOval(x + inset, y + inset, size, size)
            val input = usage?.inputTokens?.coerceAtLeast(0)?.toDouble() ?: 0.0
            val output = usage?.outputTokens?.coerceAtLeast(0)?.toDouble() ?: 0.0
            if (input + output > 0) {
                val inputAngle = (360 * input / (input + output)).toInt()
                graphics.color = JBColor(Color(0x3574F0), Color(0x6B9BFA))
                graphics.drawArc(x + inset, y + inset, size, size, 90, -inputAngle)
                graphics.color = JBColor(Color(0x26835B), Color(0x59A869))
                graphics.drawArc(x + inset, y + inset, size, size, 90 - inputAngle, -(360 - inputAngle))
            }
        } finally {
            graphics.dispose()
        }
    }
}
