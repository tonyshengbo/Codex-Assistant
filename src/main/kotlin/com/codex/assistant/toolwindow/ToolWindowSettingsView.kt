package com.codex.assistant.toolwindow

import com.codex.assistant.settings.AgentSettingsService
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class ToolWindowSettingsView(
    private val onSaved: () -> Unit,
) : JPanel(BorderLayout(0, 12)) {
    private val codexField = JBTextField()
    private val hintLabel = JLabel("Configure the local Codex CLI executable used inside this workspace.")
    private val saveButton = JButton("Save")
    private val resetButton = JButton("Reset")

    init {
        isOpaque = true
        background = AssistantUiTheme.APP_BG
        border = javax.swing.BorderFactory.createEmptyBorder(18, 18, 18, 18)

        val header = JPanel(BorderLayout(12, 6)).apply {
            isOpaque = false
            add(
                JPanel(BorderLayout(0, 6)).apply {
                    isOpaque = false
                    add(JLabel("Settings").also(AssistantUiTheme::title), BorderLayout.NORTH)
                    add(hintLabel.apply(AssistantUiTheme::subtitle), BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
        }

        val fieldTitle = JLabel("Codex CLI Path")
        AssistantUiTheme.sectionTitle(fieldTitle)
        val fieldHint = JLabel("Used for chat runs, session resume, and execution tasks.")
        AssistantUiTheme.meta(fieldHint)

        val fieldPanel = JPanel(BorderLayout(0, 8)).apply {
            isOpaque = true
            AssistantUiTheme.panel(this)
            add(fieldTitle, BorderLayout.NORTH)
            add(codexField, BorderLayout.CENTER)
            add(fieldHint, BorderLayout.SOUTH)
        }
        codexField.background = AssistantUiTheme.SURFACE_SUBTLE
        codexField.foreground = AssistantUiTheme.TEXT_PRIMARY
        codexField.caretColor = AssistantUiTheme.TEXT_PRIMARY
        codexField.border = javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(AssistantUiTheme.BORDER_SUBTLE, 1, true),
            javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8),
        )

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(saveButton)
            add(resetButton)
        }
        AssistantUiTheme.primaryButton(saveButton)
        AssistantUiTheme.secondaryButton(resetButton)
        header.add(actions, BorderLayout.EAST)

        saveButton.addActionListener {
            val state = AgentSettingsService.getInstance().state
            state.setExecutablePathFor("codex", codexField.text.trim())
            onSaved()
        }
        resetButton.addActionListener { reload() }

        add(header, BorderLayout.NORTH)
        add(fieldPanel, BorderLayout.CENTER)
        reload()
    }

    fun reload() {
        val state = AgentSettingsService.getInstance().state
        codexField.text = state.executablePathFor("codex")
    }
}
