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
) : DumbAwareAction(tab.fullTitle), CustomComponentAction {
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
        titleLabel.toolTipText = tab.fullTitle
        closeButton.preferredSize = Dimension(tokens.controls.iconMd.value.toInt(), tokens.controls.iconMd.value.toInt())

        val center = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            // statusDot 仅在有状态时才加入布局，避免 NONE 状态下占位
            add(titleLabel, BorderLayout.CENTER)
        }
        panel.add(center, BorderLayout.CENTER)
        // closeButton 用 wrapper 包裹，wrapper 宽度固定，避免 isVisible=false 时
        // BorderLayout 仍保留 EAST 空间导致 tab 宽度不一致
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
        panel.toolTipText = tab.fullTitle
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
        titleLabel.toolTipText = tab.fullTitle
        panel.toolTipText = tab.fullTitle
        applyStyle(panel, titleLabel, closeButton, statusDot)
    }

    fun updateTab(tab: SessionTab) {
        this.tab = tab
        templatePresentation.text = tab.fullTitle
        refreshCustomComponents()
    }

    private fun applyStyle(panel: JPanel, titleLabel: JLabel, closeButton: JButton, statusDot: TabStatusDot) {
        val tokens = assistantUiTokens()
        val theme = if (currentIdeDarkTheme()) EffectiveTheme.DARK else EffectiveTheme.LIGHT
        val palette = AssistantUiTheme.palette(theme)
        val isHovered = panel.getClientProperty("hovered") == true
        val center = panel.getClientProperty("center") as? JPanel

        panel.background = when {
            tab.active -> palette.chromeRaised
            // 固定叠加值替代 brighter()，在 dark 主题下有明显但不刺眼的反馈
            isHovered -> Color(
                (palette.chromeBg.red + 18).coerceAtMost(255),
                (palette.chromeBg.green + 18).coerceAtMost(255),
                (palette.chromeBg.blue + 22).coerceAtMost(255),
            )
            else -> palette.chromeBg
        }

        // 激活态：顶部 accent 线；非激活：透明占位保持高度一致
        val topBorderColor = if (tab.active) palette.accent else Color(0, 0, 0, 0)
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(2, 0, 0, 0, topBorderColor),
            BorderFactory.createEmptyBorder(
                tokens.spacing.xs.value.toInt(),
                tokens.spacing.sm.value.toInt(),
                tokens.spacing.xs.value.toInt(),
                tokens.spacing.sm.value.toInt(),
            ),
        )

        titleLabel.foreground = if (tab.active) palette.textPrimary else palette.textSecondary
        closeButton.foreground = if (tab.active) palette.textPrimary else palette.textSecondary

        // close 按钮仅在 active 或 hover 时显示；wrapper 宽度固定，不影响 tab 整体宽度
        closeButton.isVisible = tab.closable && (tab.active || isHovered)

        // 状态圆点：running 优先于 done；NONE 时从布局移除避免占位
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

/** 6×6 状态圆点，running 时由内部 Timer 驱动透明度脉冲动画。 */
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
            // 0→1→0 triangle wave for pulse
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
