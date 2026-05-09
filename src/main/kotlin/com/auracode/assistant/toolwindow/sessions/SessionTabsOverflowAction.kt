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
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.lang.ref.WeakReference
import javax.swing.BorderFactory
import javax.swing.Icon
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
        // \u4f7f\u7528\u5df2\u6709\u7684 arrow-down.svg\uff0c\u4e0e\u5176\u4ed6 SVG \u56fe\u6807\u98ce\u683c\u4e00\u81f4
        val arrowIcon = IconLoader.getIcon("/icons/arrow-down.svg", SessionTabsOverflowAction::class.java)
        val arrow = JButton(arrowIcon).apply {
            isFocusable = false
            isOpaque = false
            setContentAreaFilled(false)
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
            addActionListener { showOverflowPopup(panel) }
        }
        label.font = label.font.deriveFont(Font.PLAIN, tokens.type.label.value)
        panel.add(label, BorderLayout.CENTER)
        panel.add(arrow, BorderLayout.EAST)
        panel.putClientProperty("label", label)
        panel.putClientProperty("arrow", arrow)
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
        if (overflowTabs.isEmpty()) return
        val theme = if (currentIdeDarkTheme()) EffectiveTheme.DARK else EffectiveTheme.LIGHT
        val palette = AssistantUiTheme.palette(theme)
        val actions = overflowTabs.map { tab ->
            object : DumbAwareAction(tab.fullTitle) {
                override fun actionPerformed(e: AnActionEvent) {
                    onSelect(tab.sessionId)
                }
                override fun update(e: AnActionEvent) {
                    // 状态圆点：用 ColorIcon 在菜单项左侧显示 running/done 状态
                    e.presentation.icon = when {
                        tab.running -> TabStatusColorIcon(palette.accent)
                        tab.hasUnreadCompletion -> TabStatusColorIcon(Color(0x3D, 0xD6, 0x8C))
                        else -> null
                    }
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
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
        // arrow-down.svg 原始 fill 是黑色，用 TintedIcon 替换为 textSecondary 颜色
        val rawIcon = IconLoader.getIcon("/icons/arrow-down.svg", SessionTabsOverflowAction::class.java)
        arrow.icon = TintedIcon(rawIcon, palette.textSecondary)
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

/** 6×6 纯色圆形 Icon，用于 overflow 菜单项的状态指示。 */
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
 * 将任意 Icon 的不透明像素替换为指定颜色，用于修正 SVG 图标在深色主题下的颜色。
 * 仅替换非透明像素，保留原始 alpha 通道。
 */
private class TintedIcon(private val source: Icon, private val tint: Color) : Icon {
    override fun getIconWidth() = source.iconWidth
    override fun getIconHeight() = source.iconHeight
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        // 先把原始图标画到临时 BufferedImage，再逐像素替换颜色
        val w = source.iconWidth
        val h = source.iconHeight
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val ig = img.createGraphics()
        source.paintIcon(c, ig, 0, 0)
        ig.dispose()
        val tr = tint.red; val tg = tint.green; val tb = tint.blue
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
