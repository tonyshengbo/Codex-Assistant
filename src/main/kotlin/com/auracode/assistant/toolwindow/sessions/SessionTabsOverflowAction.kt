package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shared.AssistantUiTheme
import com.auracode.assistant.toolwindow.shared.EffectiveTheme
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import com.auracode.assistant.toolwindow.shared.currentIdeDarkTheme
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid
import java.awt.BorderLayout
import java.awt.Font
import java.lang.ref.WeakReference
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

internal class SessionTabsOverflowAction(
    overflowTabs: List<SessionTab>,
    private val onSelect: (String) -> Unit,
) : DumbAwareAction(AuraCodeBundle.message("common.more")), CustomComponentAction {
    private var overflowTabs: List<SessionTab> = overflowTabs
    private val customComponents = mutableListOf<WeakReference<JComponent>>()

    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val tokens = assistantUiTokens()
        val panel = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = true
        }
        val label = JLabel(AuraCodeBundle.message("common.more"))
        val arrow = JButton("\u2304").apply {
            isFocusable = false
            isOpaque = false
            setContentAreaFilled(false)
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
            addActionListener { showOverflowPopup(panel) }
        }
        label.font = label.font.deriveFont(Font.BOLD, tokens.type.label.value)
        arrow.font = arrow.font.deriveFont(Font.PLAIN, tokens.type.label.value)
        panel.add(label, BorderLayout.CENTER)
        panel.add(arrow, BorderLayout.EAST)
        panel.putClientProperty("label", label)
        panel.putClientProperty("arrow", arrow)
        panel.border = BorderFactory.createEmptyBorder(0, tokens.spacing.xs.value.toInt(), 0, tokens.spacing.xs.value.toInt())
        panel.toolTipText = AuraCodeBundle.message("common.more")
        panel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                showOverflowPopup(panel)
            }
        })
        applyStyle(panel, label, arrow)
        customComponents += WeakReference(panel)
        return panel
    }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        val panel = component as? JPanel ?: return
        val label = panel.getClientProperty("label") as? JLabel ?: return
        val arrow = panel.getClientProperty("arrow") as? JButton ?: return
        label.text = AuraCodeBundle.message("common.more")
        applyStyle(panel, label, arrow)
    }

    fun updateOverflowTabs(overflowTabs: List<SessionTab>) {
        this.overflowTabs = overflowTabs
        refreshCustomComponents()
    }

    private fun showOverflowPopup(anchor: JComponent) {
        if (overflowTabs.isEmpty()) {
            return
        }
        val actions = overflowTabs.map { tab ->
            object : DumbAwareAction(tab.fullTitle) {
                override fun actionPerformed(e: AnActionEvent) {
                    onSelect(tab.sessionId)
                }
            }
        }
        val group = DefaultActionGroup(actions)
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            AuraCodeBundle.message("common.more"),
            group,
            DataManager.getInstance().getDataContext(anchor),
            ActionSelectionAid.SPEEDSEARCH,
            true,
        )
        popup.showUnderneathOf(anchor)
    }

    private fun applyStyle(panel: JPanel, label: JLabel, arrow: JButton) {
        val tokens = assistantUiTokens()
        val theme = if (currentIdeDarkTheme()) EffectiveTheme.DARK else EffectiveTheme.LIGHT
        val palette = AssistantUiTheme.palette(theme)
        panel.background = palette.chromeBg
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(0, 0, 0, 0),
            BorderFactory.createEmptyBorder(
                tokens.spacing.xs.value.toInt(),
                tokens.spacing.sm.value.toInt(),
                tokens.spacing.xs.value.toInt(),
                tokens.spacing.sm.value.toInt(),
            ),
        )
        label.foreground = palette.textSecondary
        arrow.foreground = palette.textSecondary
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
}
