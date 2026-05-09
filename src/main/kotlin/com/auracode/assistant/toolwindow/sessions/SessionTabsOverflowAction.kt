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
import com.intellij.openapi.util.IconLoader
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.lang.ref.WeakReference
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
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
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }
        val arrowIcon = IconLoader.getIcon("/icons/arrow-down.svg", SessionTabsOverflowAction::class.java)
        val arrow = JButton(arrowIcon).apply {
            isFocusable = false
            isOpaque = false
            setContentAreaFilled(false)
            isBorderPainted = false
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
            addActionListener { showOverflowPopup(panel) }
        }
        panel.add(arrow, BorderLayout.CENTER)
        panel.putClientProperty("arrow", arrow)
        panel.toolTipText = AuraCodeBundle.message("common.more")
        installHandlers(panel, arrow)
        applyStyle(panel, arrow)
        customComponents += WeakReference(panel)
        return panel
    }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        val panel = component as? JPanel ?: return
        val arrow = panel.getClientProperty("arrow") as? JButton ?: return
        applyStyle(panel, arrow)
    }

    fun updateOverflowTabs(overflowTabs: List<SessionTab>) {
        this.overflowTabs = overflowTabs
        refreshCustomComponents()
    }

    private fun showOverflowPopup(anchor: JComponent) {
        if (overflowTabs.isEmpty()) return
        val group = DefaultActionGroup(buildOverflowActions())
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            AuraCodeBundle.message("common.more"),
            group,
            DataManager.getInstance().getDataContext(anchor),
            ActionSelectionAid.SPEEDSEARCH,
            true,
        )
        popup.showUnderneathOf(anchor)
    }

    /**
     * Builds popup actions from the current overflow snapshot using bounded menu labels.
     */
    internal fun buildOverflowActions(): List<DumbAwareAction> {
        val theme = if (currentIdeDarkTheme()) EffectiveTheme.DARK else EffectiveTheme.LIGHT
        val palette = AssistantUiTheme.palette(theme)
        return overflowTabs.map { tab ->
            object : DumbAwareAction(tab.overflowTitle) {
                override fun actionPerformed(e: AnActionEvent) {
                    onSelect(tab.sessionId)
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.icon = when {
                        tab.running -> TabStatusColorIcon(palette.accent)
                        tab.hasUnreadCompletion -> TabStatusColorIcon(Color(0x3D, 0xD6, 0x8C))
                        else -> null
                    }
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            }
        }
    }

    private fun applyStyle(panel: JPanel, arrow: JButton) {
        val tokens = assistantUiTokens()
        val theme = if (currentIdeDarkTheme()) EffectiveTheme.DARK else EffectiveTheme.LIGHT
        val palette = AssistantUiTheme.palette(theme)
        val isHovered = panel.getClientProperty("hovered") == true
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createEmptyBorder(0, 0, 0, 0),
            BorderFactory.createEmptyBorder(
                tokens.spacing.xs.value.toInt(),
                tokens.spacing.xs.value.toInt(),
                tokens.spacing.xs.value.toInt(),
                tokens.spacing.xs.value.toInt(),
            ),
        )
        val iconTint = if (isHovered) palette.textPrimary else palette.textSecondary
        val rawIcon = IconLoader.getIcon("/icons/arrow-down.svg", SessionTabsOverflowAction::class.java)
        arrow.icon = TintedIcon(rawIcon, iconTint)
    }

    /**
     * Tracks hover state without adding a persistent background to the overflow trigger.
     */
    private fun installHandlers(panel: JPanel, arrow: JButton) {
        val listener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                panel.putClientProperty("hovered", true)
                updateCustomComponent(panel, templatePresentation)
            }

            override fun mouseExited(e: MouseEvent?) {
                panel.putClientProperty("hovered", false)
                updateCustomComponent(panel, templatePresentation)
            }
        }
        panel.addMouseListener(listener)
        arrow.addMouseListener(listener)
        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                showOverflowPopup(panel)
            }
        })
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

/** Draws the small overflow-menu status dot used for running and done tabs. */
private class TabStatusColorIcon(private val color: Color) : Icon {
    override fun getIconWidth() = 8
    override fun getIconHeight() = 8
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillOval(x + 1, y + 1, 6, 6)
    }
}

/**
 * Recolors opaque icon pixels while preserving the original alpha channel.
 */
private class TintedIcon(private val source: Icon, private val tint: Color) : Icon {
    override fun getIconWidth() = source.iconWidth
    override fun getIconHeight() = source.iconHeight
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = source.iconWidth
        val h = source.iconHeight
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val ig = img.createGraphics()
        source.paintIcon(c, ig, 0, 0)
        ig.dispose()
        val tr = tint.red
        val tg = tint.green
        val tb = tint.blue
        for (px in 0 until w) {
            for (py in 0 until h) {
                val argb = img.getRGB(px, py)
                val alpha = (argb ushr 24) and 0xFF
                if (alpha > 0) {
                    img.setRGB(px, py, (alpha shl 24) or (tr shl 16) or (tg shl 8) or tb)
                }
            }
        }
        g2.drawImage(img, x, y, null)
    }
}
