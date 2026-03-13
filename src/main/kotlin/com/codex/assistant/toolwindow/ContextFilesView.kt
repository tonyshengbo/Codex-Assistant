package com.codex.assistant.toolwindow

import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class ContextFilesView(
    private val onAddFile: () -> String?,
    private val onFilesChanged: (Set<String>) -> Unit,
) : JPanel(BorderLayout(0, 12)) {
    private val model = DefaultListModel<String>()
    private val list = JBList(model)
    private val addButton = JButton("Attach File")
    private val removeButton = JButton("Remove")
    private val clearButton = JButton("Clear All")

    init {
        isOpaque = true
        background = AssistantUiTheme.APP_BG
        border = javax.swing.BorderFactory.createEmptyBorder(18, 18, 18, 18)

        val header = JPanel(BorderLayout(12, 6)).apply {
            isOpaque = false
            add(
                JPanel(BorderLayout(0, 6)).apply {
                    isOpaque = false
                    add(javax.swing.JLabel("Context Files").also(AssistantUiTheme::title), BorderLayout.NORTH)
                    add(javax.swing.JLabel("Manage extra files attached to the next run.").apply(AssistantUiTheme::subtitle), BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
        }

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.background = AssistantUiTheme.APP_BG
        list.cellRenderer = ContextRowRenderer()
        list.addListSelectionListener { refreshActionState() }

        val scrollPane = JBScrollPane(list).apply {
            border = javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(AssistantUiTheme.BORDER_SUBTLE, 1, true),
                javax.swing.BorderFactory.createEmptyBorder(6, 6, 6, 6),
            )
            viewport.background = AssistantUiTheme.APP_BG
        }

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(addButton)
            add(removeButton)
            add(clearButton)
        }
        AssistantUiTheme.primaryButton(addButton)
        AssistantUiTheme.secondaryButton(removeButton)
        AssistantUiTheme.ghostButton(clearButton)
        header.add(actions, BorderLayout.EAST)

        addButton.addActionListener {
            val file = onAddFile() ?: return@addActionListener
            if ((0 until model.size()).none { model[it] == file }) {
                model.addElement(file)
                notifyFilesChanged()
            }
            refreshActionState()
        }
        removeButton.addActionListener {
            val index = list.selectedIndex
            if (index >= 0) {
                model.remove(index)
                notifyFilesChanged()
            }
            refreshActionState()
        }
        clearButton.addActionListener {
            model.clear()
            notifyFilesChanged()
            refreshActionState()
        }

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        refreshActionState()
    }

    fun replaceFiles(paths: Collection<String>) {
        model.clear()
        paths.forEach(model::addElement)
        refreshActionState()
    }

    private fun notifyFilesChanged() {
        onFilesChanged((0 until model.size()).map { model.get(it) }.toSet())
    }

    private fun refreshActionState() {
        val hasSelection = list.selectedValue != null
        removeButton.isEnabled = hasSelection
        clearButton.isEnabled = model.size() > 0
    }

    private inner class ContextRowRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            return JPanel(BorderLayout()).apply {
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
                add(javax.swing.JLabel(value.toString()).apply {
                    foreground = AssistantUiTheme.TEXT_PRIMARY
                }, BorderLayout.CENTER)
            }
        }
    }
}
