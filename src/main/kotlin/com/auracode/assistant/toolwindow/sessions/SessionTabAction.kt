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
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.lang.ref.WeakReference
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

internal open class SessionTabAction(
    tab: SessionTab,
    private val onSelect: (String) -> Unit,
    private val onClose: (String) -> Unit,
) : DumbAwareAction(tab.tooltipTitle), CustomComponentAction {
    private var tab: SessionTab = tab
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
        val statusDot = TabStatusDot()
        val titleLabel = JLabel(tab.displayTitle)
        val closeButton = JButton().apply {
            isFocusable = false
            isOpaque = false
            setContentAreaFilled(false)
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
            icon = IconLoader.getIcon("/icons/close-small.svg", SessionTabAction::class.java)
            toolTipText = AuraCodeBundle.message("session.tab.close")
            isVisible = false
            addActionListener { onClose(tab.sessionId) }
        }
        titleLabel.font = titleLabel.font.deriveFont(Font.PLAIN, tokens.type.label.value)
        titleLabel.toolTipText = tab.tooltipTitle
        closeButton.preferredSize = Dimension(tokens.controls.iconMd.value.toInt(), tokens.controls.iconMd.value.toInt())

        val center = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            // Only attach the status dot when the tab has a visible status.
            add(titleLabel, BorderLayout.CENTER)
        }
        panel.add(center, BorderLayout.CENTER)
        // Keep the close-button width stable so hover-only visibility does not resize the tab.
        val closeWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(tokens.controls.iconMd.value.toInt(), tokens.controls.iconMd.value.toInt())
            add(closeButton, BorderLayout.CENTER)
        }
        panel.add(closeWrapper, BorderLayout.EAST)
        panel.putClientProperty("center", center)
        panel.putClientProperty("label", titleLabel)
        panel.putClientProperty("close", closeButton)
        panel.putClientProperty("dot", statusDot)
        applyStyle(panel, titleLabel, closeButton, statusDot)
        panel.toolTipText = tab.tooltipTitle
        installHandlers(panel, center, titleLabel, closeButton)
        customComponents += WeakReference(panel)
        return panel
    }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        val panel = component as? JPanel ?: return
        val titleLabel = panel.getClientProperty("label") as? JLabel ?: return
        val closeButton = panel.getClientProperty("close") as? JButton ?: return
        val statusDot = panel.getClientProperty("dot") as? TabStatusDot ?: return
        titleLabel.text = tab.displayTitle
        titleLabel.toolTipText = tab.tooltipTitle
        panel.toolTipText = tab.tooltipTitle
        applyStyle(panel, titleLabel, closeButton, statusDot)
    }

    fun updateTab(tab: SessionTab) {
        this.tab = tab
        templatePresentation.text = tab.tooltipTitle
        refreshCustomComponents()
    }

    private fun applyStyle(panel: JPanel, titleLabel: JLabel, closeButton: JButton, statusDot: TabStatusDot) {
        val tokens = assistantUiTokens()
        val theme = if (currentIdeDarkTheme()) EffectiveTheme.DARK else EffectiveTheme.LIGHT
        val palette = AssistantUiTheme.palette(theme)
        val isHovered = panel.getClientProperty("hovered") == true
        val center = panel.getClientProperty("center") as? JPanel
        titleLabel.foreground = if (tab.active) palette.textPrimary else palette.textSecondary
        closeButton.foreground = if (tab.active) palette.textPrimary else palette.textSecondary

        // Show the close button only for the active or hovered tab.
        closeButton.isVisible = tab.closable && (tab.active || isHovered)

        // Running status takes precedence over unread completion.
        val newDotState = when {
            tab.running -> TabStatusDot.State.RUNNING
            tab.hasUnreadCompletion -> TabStatusDot.State.DONE
            else -> TabStatusDot.State.NONE
        }
        statusDot.state = newDotState
        statusDot.dotColor = when (newDotState) {
            TabStatusDot.State.RUNNING -> palette.accent
            TabStatusDot.State.DONE -> Color(0x3D, 0xD6, 0x8C)
            TabStatusDot.State.NONE -> Color(0, 0, 0, 0)
        }
        if (center != null) {
            val dotInLayout = statusDot.parent == center
            if (newDotState != TabStatusDot.State.NONE && !dotInLayout) {
                center.add(statusDot, BorderLayout.WEST)
                center.revalidate()
            } else if (newDotState == TabStatusDot.State.NONE && dotInLayout) {
                center.remove(statusDot)
                center.revalidate()
            }
        }
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
     * to the outer tab panel. Also installs hover tracking for close-button
     * visibility and background tint.
     */
    private fun installHandlers(panel: JPanel, vararg clickTargets: JComponent) {
        val hoverListener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                panel.putClientProperty("hovered", true)
                updateCustomComponent(panel, templatePresentation)
            }
            override fun mouseExited(e: MouseEvent?) {
                panel.putClientProperty("hovered", false)
                updateCustomComponent(panel, templatePresentation)
            }
            override fun mouseClicked(e: MouseEvent?) {
                onSelect(tab.sessionId)
            }
        }
        panel.addMouseListener(hoverListener)
        clickTargets.forEach { it.addMouseListener(hoverListener) }
    }
}

/**
 * Draws the small status dot and pulses it while the session is still running.
 */
internal class TabStatusDot : JPanel() {
    enum class State { NONE, RUNNING, DONE }

    var state: State = State.NONE
    var dotColor: Color = Color(0, 0, 0, 0)

    private var pulseAlpha: Float = 0f
    private val timer = javax.swing.Timer(80) {
        if (state == State.RUNNING) {
            pulseAlpha = (pulseAlpha + 0.15f) % 2f
            repaint()
        }
    }

    init {
        isOpaque = false
        preferredSize = Dimension(10, 10)
    }

    override fun addNotify() {
        super.addNotify()
        timer.start()
    }

    override fun removeNotify() {
        timer.stop()
        super.removeNotify()
    }

    override fun paintComponent(g: Graphics) {
        if (state == State.NONE) return
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val alpha = if (state == State.RUNNING) {
            // Use a triangle wave to keep the running pulse smooth.
            val t = pulseAlpha % 2f
            if (t < 1f) t else 2f - t
        } else {
            1f
        }
        g2.color = Color(dotColor.red, dotColor.green, dotColor.blue, (alpha * 255).toInt().coerceIn(60, 255))
        val size = 6
        val x = (width - size) / 2
        val y = (height - size) / 2
        g2.fillOval(x, y, size, size)
    }
}
