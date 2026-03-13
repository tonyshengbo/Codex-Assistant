package com.codex.assistant.toolwindow

import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel

object AssistantUiTheme {
    val APP_BG = JBColor(0x0B1015, 0x0B1015)
    val CHROME_BG = JBColor(0x0E141C, 0x0E141C)
    val CHROME_RAISED = JBColor(0x111923, 0x111923)
    val SURFACE = JBColor(0x101823, 0x101823)
    val SURFACE_RAISED = JBColor(0x15202C, 0x15202C)
    val SURFACE_SUBTLE = JBColor(0x0C1218, 0x0C1218)
    val BORDER = JBColor(0x22303D, 0x22303D)
    val BORDER_STRONG = JBColor(0x324455, 0x324455)
    val BORDER_SUBTLE = JBColor(0x18222D, 0x18222D)
    val TEXT_PRIMARY = JBColor(0xE7EDF7, 0xE7EDF7)
    val TEXT_SECONDARY = JBColor(0x9FAFC3, 0x9FAFC3)
    val TEXT_MUTED = JBColor(0x73839A, 0x73839A)
    val ACCENT = JBColor(0x4D95FF, 0x4D95FF)
    val ACCENT_BG = JBColor(0x15345C, 0x15345C)
    val ACCENT_BG_SOFT = JBColor(0x12263E, 0x12263E)
    val SUCCESS = JBColor(0x56C27A, 0x56C27A)
    val WARNING = JBColor(0xD9B15C, 0xD9B15C)
    val DANGER = JBColor(0xE47D7D, 0xE47D7D)

    fun panel(component: JComponent) {
        component.background = SURFACE
        component.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER, 1, true),
            BorderFactory.createEmptyBorder(14, 14, 14, 14),
        )
    }

    fun subtlePanel(component: JComponent) {
        component.background = CHROME_RAISED
        component.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_SUBTLE, 1, true),
            BorderFactory.createEmptyBorder(12, 12, 12, 12),
        )
    }

    fun title(label: JLabel) {
        label.foreground = TEXT_PRIMARY
        label.font = label.font.deriveFont(Font.BOLD, 15f)
    }

    fun sectionTitle(label: JLabel) {
        label.foreground = TEXT_PRIMARY
        label.font = label.font.deriveFont(Font.BOLD, 12.5f)
    }

    fun subtitle(label: JLabel) {
        label.foreground = TEXT_SECONDARY
        label.font = label.font.deriveFont(11.5f)
    }

    fun meta(label: JLabel, color: Color = TEXT_MUTED) {
        label.foreground = color
        label.font = label.font.deriveFont(10.5f)
    }

    fun primaryButton(button: JButton) {
        button.isFocusable = false
        button.background = ACCENT_BG
        button.foreground = TEXT_PRIMARY
        button.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ACCENT, 1, true),
            BorderFactory.createEmptyBorder(5, 11, 5, 11),
        )
    }

    fun secondaryButton(button: JButton) {
        button.isFocusable = false
        button.background = SURFACE_RAISED
        button.foreground = TEXT_PRIMARY
        button.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_STRONG, 1, true),
            BorderFactory.createEmptyBorder(5, 10, 5, 10),
        )
    }

    fun ghostButton(button: JButton) {
        button.isFocusable = false
        button.background = CHROME_RAISED
        button.foreground = TEXT_SECONDARY
        button.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_SUBTLE, 1, true),
            BorderFactory.createEmptyBorder(4, 9, 4, 9),
        )
    }

    fun toolbarButton(button: JButton) {
        button.isFocusable = false
        button.background = CHROME_BG
        button.foreground = TEXT_SECONDARY
        button.border = BorderFactory.createEmptyBorder(3, 6, 3, 6)
    }

    fun toolbarChip(button: JButton) {
        button.isFocusable = false
        button.background = CHROME_RAISED
        button.foreground = TEXT_PRIMARY
        button.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_SUBTLE, 1, true),
            BorderFactory.createEmptyBorder(3, 8, 3, 8),
        )
    }

    fun compactPrimaryButton(button: JButton) {
        button.isFocusable = false
        button.background = ACCENT_BG_SOFT
        button.foreground = TEXT_PRIMARY
        button.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ACCENT, 1, true),
            BorderFactory.createEmptyBorder(4, 10, 4, 10),
        )
    }

    fun chipButton(button: JButton) {
        button.isFocusable = false
        button.background = SURFACE_SUBTLE
        button.foreground = TEXT_SECONDARY
        button.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_SUBTLE, 1, true),
            BorderFactory.createEmptyBorder(3, 8, 3, 8),
        )
    }

    fun badge(label: JLabel) {
        label.isOpaque = true
        label.background = CHROME_RAISED
        label.foreground = TEXT_SECONDARY
        label.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_SUBTLE, 1, true),
            BorderFactory.createEmptyBorder(3, 7, 3, 7),
        )
    }
}
