package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.i18n.AuraCodeBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.project.DumbAwareAction
import com.auracode.assistant.toolwindow.shared.AssistantUiTheme
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import com.auracode.assistant.toolwindow.shared.currentIdeDarkTheme
import com.auracode.assistant.toolwindow.shared.EffectiveTheme
import java.awt.BorderLayout
import java.awt.Font
import java.lang.ref.WeakReference
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

internal open class ToolWindowHeaderTabAction(
    tab: ToolWindowHeaderTab,
    private val onSelect: (String) -> Unit,
    private val onClose: (String) -> Unit,
) : DumbAwareAction(tab.fullTitle), CustomComponentAction {
    private var tab: ToolWindowHeaderTab = tab
    private val customComponents = mutableListOf<WeakReference<JComponent>>()

    override fun actionPerformed(e: AnActionEvent) {
        onSelect(tab.sessionId)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val tokens = assistantUiTokens()
        val panel = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = true
        }
        val titleLabel = JLabel(tab.displayTitle)
        val closeButton = JButton().apply {
            isFocusable = false
            isOpaque = false
            setContentAreaFilled(false)
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
            icon = IconLoader.getIcon("/icons/close-small.svg", ToolWindowHeaderTabAction::class.java)
            toolTipText = AuraCodeBundle.message("session.tab.close")
            isVisible = tab.closable
            addActionListener { onClose(tab.sessionId) }
        }
        titleLabel.font = titleLabel.font.deriveFont(Font.PLAIN, tokens.type.label.value)
        titleLabel.toolTipText = tab.fullTitle
        closeButton.preferredSize = java.awt.Dimension(tokens.controls.iconMd.value.toInt(), tokens.controls.iconMd.value.toInt())
        val center = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.CENTER)
        }
        panel.add(center, BorderLayout.CENTER)
        panel.add(closeButton, BorderLayout.EAST)
        panel.putClientProperty("label", titleLabel)
        panel.putClientProperty("close", closeButton)
        applyStyle(panel, titleLabel, closeButton)
        panel.border = BorderFactory.createEmptyBorder(0, tokens.spacing.xs.value.toInt(), 0, tokens.spacing.xs.value.toInt())
        panel.toolTipText = tab.fullTitle
        installSelectionHandlers(panel, center, titleLabel)
        customComponents += WeakReference(panel)
        return panel
    }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        val panel = component as? JPanel ?: return
        val titleLabel = panel.getClientProperty("label") as? JLabel ?: return
        val closeButton = panel.getClientProperty("close") as? JButton ?: return
        titleLabel.text = tab.displayTitle
        titleLabel.toolTipText = tab.fullTitle
        closeButton.isVisible = tab.closable
        panel.toolTipText = tab.fullTitle
        applyStyle(panel, titleLabel, closeButton)
    }

    fun updateTab(tab: ToolWindowHeaderTab) {
        this.tab = tab
        templatePresentation.text = tab.fullTitle
        refreshCustomComponents()
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

    private fun refreshCustomComponents() {
        val iterator = customComponents.iterator()
        while (iterator.hasNext()) {
            val component = iterator.next().get()
            if (component == null) {
                iterator.remove()
            } else {
                updateCustomComponent(component, templatePresentation)
            }
        }
    }

    /**
     * Swing header actions do not bubble mouse clicks from child components back
     * to the outer tab panel, so the title text needs its own selection handler.
     */
    private fun installSelectionHandlers(vararg components: JComponent) {
        val selectListener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                onSelect(tab.sessionId)
            }
        }
        components.forEach { component ->
            component.addMouseListener(selectListener)
        }
    }
}
