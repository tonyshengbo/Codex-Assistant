package com.codex.assistant.toolwindow.session

import com.codex.assistant.i18n.CodexBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.project.DumbAwareAction
import com.codex.assistant.toolwindow.shared.AssistantUiTheme
import com.codex.assistant.toolwindow.shared.assistantUiTokens
import com.codex.assistant.toolwindow.shared.currentIdeDarkTheme
import com.codex.assistant.toolwindow.shared.EffectiveTheme
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

internal class ToolWindowHeaderTabAction(
    private val tab: ToolWindowHeaderTab,
    private val onSelect: (String) -> Unit,
    private val onClose: (String) -> Unit,
) : DumbAwareAction(tab.title), CustomComponentAction {

    override fun actionPerformed(e: AnActionEvent) {
        onSelect(tab.sessionId)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val tokens = assistantUiTokens()
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 3, 0)).apply {
            isOpaque = true
        }
        val titleLabel = JLabel(tab.title)
        val closeButton = JButton().apply {
            isFocusable = false
            isOpaque = false
            setContentAreaFilled(false)
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
            icon = IconLoader.getIcon("/icons/close-small.svg", ToolWindowHeaderTabAction::class.java)
            toolTipText = CodexBundle.message("session.tab.close")
            isVisible = tab.closable
            addActionListener { onClose(tab.sessionId) }
        }
        panel.add(titleLabel)
        panel.add(closeButton)
        panel.putClientProperty("label", titleLabel)
        panel.putClientProperty("close", closeButton)
        applyStyle(panel, titleLabel, closeButton)
        titleLabel.font = titleLabel.font.deriveFont(Font.PLAIN, 12f)
        closeButton.preferredSize = java.awt.Dimension(14, 14)
        panel.border = BorderFactory.createEmptyBorder(0, tokens.spacing.xs.value.toInt(), 0, tokens.spacing.xs.value.toInt())
        panel.toolTipText = tab.title
        panel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                onSelect(tab.sessionId)
            }
        })
        return panel
    }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        val panel = component as? JPanel ?: return
        val titleLabel = panel.getClientProperty("label") as? JLabel ?: return
        val closeButton = panel.getClientProperty("close") as? JButton ?: return
        titleLabel.text = tab.title
        closeButton.isVisible = tab.closable
        applyStyle(panel, titleLabel, closeButton)
    }

    private fun applyStyle(panel: JPanel, titleLabel: JLabel, closeButton: JButton) {
        val tokens = assistantUiTokens()
        val theme = if (currentIdeDarkTheme()) EffectiveTheme.DARK else EffectiveTheme.LIGHT
        val palette = AssistantUiTheme.palette(theme)
        panel.background = if (tab.active) palette.chromeRaised else palette.chromeBg
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, if (tab.active) 2 else 0, 0, palette.accent),
            BorderFactory.createEmptyBorder(
                tokens.spacing.xs.value.toInt(),
                tokens.spacing.sm.value.toInt(),
                tokens.spacing.xs.value.toInt(),
                tokens.spacing.sm.value.toInt(),
            ),
        )
        titleLabel.foreground = if (tab.active) palette.textPrimary else palette.textSecondary
        closeButton.foreground = if (tab.active) palette.textPrimary else palette.textSecondary
    }
}
