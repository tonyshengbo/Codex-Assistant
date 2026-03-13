package com.codex.assistant.settings

import com.codex.assistant.toolwindow.AssistantUiTheme
import com.intellij.openapi.options.Configurable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

class AgentSettingsConfigurable : Configurable {
    private val codexField = JBTextField()
    private val codexHint = JLabel("Path to the local Codex CLI executable used for all Codex requests and session resume.")
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Codex Assistant"

    override fun createComponent(): JComponent {
        val p = JPanel(BorderLayout())
        p.border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        p.background = AssistantUiTheme.APP_BG

        val content = JPanel()
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.isOpaque = false

        val header = JLabel("Codex Configuration")
        header.font = header.font.deriveFont(16f)
        header.foreground = AssistantUiTheme.TEXT_PRIMARY
        val subtitle = JLabel("Configure the local Codex CLI used for chat and native session resume.")
        subtitle.foreground = AssistantUiTheme.TEXT_SECONDARY

        content.add(header)
        content.add(Box.createVerticalStrut(4))
        content.add(subtitle)
        content.add(Box.createVerticalStrut(14))

        content.add(createFieldBlock("Codex CLI Path", codexField, codexHint))
        content.add(Box.createVerticalGlue())

        p.add(content, BorderLayout.NORTH)

        panel = p
        reset()
        return p
    }

    private fun createFieldBlock(title: String, field: JComponent, hint: JLabel): JPanel {
        val block = JPanel(BorderLayout(0, 6))
        block.background = AssistantUiTheme.SURFACE
        block.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AssistantUiTheme.BORDER, 1, true),
            BorderFactory.createEmptyBorder(10, 10, 10, 10),
        )
        val top = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        top.isOpaque = false
        top.add(JLabel(title).apply { foreground = AssistantUiTheme.TEXT_PRIMARY })
        block.add(top, BorderLayout.NORTH)
        field.preferredSize = Dimension(field.preferredSize.width, 30)
        block.add(field, BorderLayout.CENTER)
        hint.foreground = AssistantUiTheme.TEXT_SECONDARY
        block.add(hint, BorderLayout.SOUTH)
        return block
    }

    override fun isModified(): Boolean {
        val state = AgentSettingsService.getInstance().state
        return codexField.text.trim() != state.executablePathFor("codex")
    }

    override fun apply() {
        val state = AgentSettingsService.getInstance().state
        state.setExecutablePathFor("codex", codexField.text.trim())
    }

    override fun reset() {
        val state = AgentSettingsService.getInstance().state
        codexField.text = state.executablePathFor("codex")
    }
}
