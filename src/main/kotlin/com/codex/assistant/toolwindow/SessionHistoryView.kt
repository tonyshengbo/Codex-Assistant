package com.codex.assistant.toolwindow

import com.codex.assistant.service.AgentChatService
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class SessionHistoryView(
    private val onOpenSession: (String) -> Unit,
    private val onCreateSession: () -> Unit,
    private val onDeleteSession: (String) -> Unit,
) : JPanel(BorderLayout(0, 12)) {
    private data class Row(
        val id: String,
        val title: String,
        val subtitle: String,
    )

    private val model = DefaultListModel<Row>()
    private val list = JBList(model)
    private val summaryLabel = JLabel("Keep recent sessions inside the current workspace.")
    private val openButton = JButton("Open Session")
    private val newButton = JButton("New Session")
    private val deleteButton = JButton("Delete")
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

    init {
        isOpaque = true
        background = AssistantUiTheme.APP_BG
        border = javax.swing.BorderFactory.createEmptyBorder(18, 18, 18, 18)

        val header = JPanel(BorderLayout(12, 6)).apply {
            isOpaque = false
            add(
                JPanel(BorderLayout(0, 6)).apply {
                    isOpaque = false
                    add(JLabel("Session History").also(AssistantUiTheme::title), BorderLayout.NORTH)
                    add(summaryLabel.apply(AssistantUiTheme::subtitle), BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
        }

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.background = AssistantUiTheme.APP_BG
        list.cellRenderer = SessionRowRenderer()

        val listPane = JBScrollPane(list).apply {
            preferredSize = Dimension(540, 360)
            border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(AssistantUiTheme.BORDER_SUBTLE, 1, true),
                javax.swing.BorderFactory.createEmptyBorder(6, 6, 6, 6),
            )
            viewport.background = AssistantUiTheme.APP_BG
        }

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(openButton)
            add(newButton)
            add(deleteButton)
        }
        AssistantUiTheme.primaryButton(openButton)
        AssistantUiTheme.secondaryButton(newButton)
        AssistantUiTheme.ghostButton(deleteButton)
        header.add(actions, BorderLayout.EAST)

        openButton.addActionListener { selectedSessionId()?.let(onOpenSession) }
        newButton.addActionListener { onCreateSession() }
        deleteButton.addActionListener { selectedSessionId()?.let(onDeleteSession) }
        list.addListSelectionListener { refreshActionState() }
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    selectedSessionId()?.let(onOpenSession)
                }
            }
        })

        add(header, BorderLayout.NORTH)
        add(listPane, BorderLayout.CENTER)
        refreshActionState()
    }

    fun updateSessions(
        sessions: List<AgentChatService.SessionSummary>,
        currentSessionId: String,
    ) {
        model.clear()
        sessions.forEach { session ->
            model.addElement(
                Row(
                    id = session.id,
                    title = session.title,
                    subtitle = "${session.messageCount} messages · ${timeFormatter.format(Instant.ofEpochMilli(session.updatedAt))}",
                ),
            )
        }
        val selectedIndex = (0 until model.size()).firstOrNull { model.get(it).id == currentSessionId } ?: 0
        if (model.size() > 0 && selectedIndex >= 0) {
            list.selectedIndex = selectedIndex
        }
        refreshActionState()
    }

    private fun selectedSessionId(): String? = list.selectedValue?.id

    private fun refreshActionState() {
        val hasSelection = list.selectedValue != null
        openButton.isEnabled = hasSelection
        deleteButton.isEnabled = hasSelection
    }

    private inner class SessionRowRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val row = value as Row
            val title = JLabel(row.title)
            val subtitle = JLabel(row.subtitle)
            AssistantUiTheme.sectionTitle(title)
            AssistantUiTheme.meta(subtitle)

            val panel = JPanel(BorderLayout(0, 4)).apply {
                isOpaque = true
                background = if (isSelected) AssistantUiTheme.CHROME_RAISED else AssistantUiTheme.SURFACE_SUBTLE
                border = javax.swing.BorderFactory.createCompoundBorder(
                    javax.swing.BorderFactory.createMatteBorder(
                        0,
                        if (isSelected) 2 else 1,
                        1,
                        0,
                        if (isSelected) AssistantUiTheme.ACCENT else AssistantUiTheme.BORDER_SUBTLE,
                    ),
                    javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12),
                )
                add(title, BorderLayout.NORTH)
                add(subtitle, BorderLayout.CENTER)
            }
            return panel
        }
    }
}
