package com.codex.assistant.toolwindow.timeline

import com.codex.assistant.model.ChatMessage
import com.codex.assistant.toolwindow.AssistantUiTheme
import com.codex.assistant.toolwindow.ToolWindowUiText
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.JViewport

class ConversationTimelinePanel(
    private val onCopyMessage: (String) -> Unit,
    private val onOpenFile: (String) -> Unit,
    private val onRetryTool: (String, String) -> Unit,
    private val onRetryCommand: (String, String) -> Unit,
    private val onCopyCommand: (String) -> Unit,
) : JPanel(BorderLayout()) {
    private val contentPanel = TimelineContentPanel()
    private val scrollPane = JBScrollPane(contentPanel)
    private val expansionOverrides = mutableMapOf<String, Boolean>()

    init {
        background = Colors.APP_BG
        contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
        contentPanel.isOpaque = true
        contentPanel.background = Colors.APP_BG
        contentPanel.border = BorderFactory.createEmptyBorder(12, 14, 18, 14)

        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.viewport.background = Colors.APP_BG
        add(scrollPane, BorderLayout.CENTER)
    }

    fun renderTurns(
        turns: List<TimelineTurnViewModel>,
        forceAutoScroll: Boolean,
    ) {
        val autoScroll = forceAutoScroll || isNearBottom()
        contentPanel.removeAll()
        if (turns.isEmpty()) {
            contentPanel.add(Box.createVerticalGlue())
        } else {
            turns.forEachIndexed { index, turn ->
                contentPanel.add(buildTurnPanel(turn))
                if (index != turns.lastIndex) {
                    contentPanel.add(Box.createVerticalStrut(8))
                }
            }
        }
        contentPanel.revalidate()
        contentPanel.repaint()
        if (autoScroll) {
            val application = ApplicationManager.getApplication()
            if (application != null) {
                application.invokeLater { scrollToBottom() }
            } else {
                scrollToBottom()
            }
        }
    }

    fun isNearBottom(thresholdPx: Int = 32): Boolean {
        val bar = scrollPane.verticalScrollBar ?: return true
        return bar.value + bar.visibleAmount >= bar.maximum - thresholdPx
    }

    fun scrollToBottom() {
        val bar = scrollPane.verticalScrollBar ?: return
        bar.value = bar.maximum
    }

    private fun buildTurnPanel(turn: TimelineTurnViewModel): JComponent {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        val main = JPanel()
        main.layout = BoxLayout(main, BoxLayout.Y_AXIS)
        main.isOpaque = false

        turn.userMessage?.let {
            main.add(buildUserBubble(it))
            main.add(Box.createVerticalStrut(8))
        }
        turn.nodes.forEachIndexed { index, node ->
            main.add(buildNodeCard(node))
            if (index != turn.nodes.lastIndex) {
                main.add(Box.createVerticalStrut(8))
            }
        }
        if (turn.userMessage != null || turn.nodes.isNotEmpty()) {
            main.add(Box.createVerticalStrut(8))
        }
        main.add(buildTurnFooter(turn))

        panel.add(main, BorderLayout.CENTER)
        return panel
    }

    private fun buildUserBubble(message: ChatMessage): JComponent {
        val block = JPanel(BorderLayout(0, 8))
        block.name = "task-block-${message.id}"
        block.isOpaque = true
        block.background = Colors.TASK_BG
        block.alignmentX = LEFT_ALIGNMENT
        block.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        block.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Colors.TASK_BORDER, 1, true),
            BorderFactory.createEmptyBorder(10, 12, 10, 12),
        )

        val header = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
        }
        val titleRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
        titleRow.add(JLabel("Task").apply {
            foreground = Colors.TASK_LABEL
            font = font.deriveFont(Font.BOLD, 11f)
        })
        titleRow.add(Box.createHorizontalStrut(8))
        titleRow.add(JLabel(formatClockTime(message.timestamp)).apply {
            foreground = Colors.META
            font = font.deriveFont(11f)
        })

        val actions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
        actions.add(buildActionButton("Copy") { onCopyMessage(message.id) })

        header.add(titleRow, BorderLayout.WEST)
        header.add(actions, BorderLayout.EAST)
        block.add(header, BorderLayout.NORTH)
        block.add(createBodyView(message.content, Colors.TEXT_PRIMARY, fontSize = 14f), BorderLayout.CENTER)
        return block
    }

    private fun buildNodeCard(node: TimelineNodeViewModel): JComponent {
        val presentation = TimelineNodePresentation.forKind(node.kind)
        val expandable = isExpandable(node)
        val expanded = if (expandable) effectiveExpanded(node) else true

        return when (presentation.chrome) {
            TimelineNodeChrome.EXECUTION -> buildExecutionNodeCard(node, expanded, expandable)
            TimelineNodeChrome.NARRATIVE,
            TimelineNodeChrome.RESULT,
            TimelineNodeChrome.ALERT,
            TimelineNodeChrome.SUPPORTING,
            -> buildNarrativeNodeCard(node, presentation)
        }
    }

    private fun buildTurnFooter(turn: TimelineTurnViewModel): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(buildDot(statusColor(turn.footerStatus), 7))
            add(JLabel(turn.statusText).apply {
                foreground = Colors.META
                font = font.deriveFont(11f)
            })
            turn.durationMs?.let { durationMs ->
                add(JLabel("· ${formatDuration(durationMs)}").apply {
                    foreground = Colors.META
                    font = font.deriveFont(11f)
                })
            }
        }
    }

    private fun buildNodeButtons(node: TimelineNodeViewModel): List<JComponent> {
        val buttons = mutableListOf<JComponent>()
        if (node.filePath != null) {
            buttons += buildActionButton("Open") { onOpenFile(node.filePath) }
        }
        if (!node.command.isNullOrBlank()) {
            buttons += buildActionButton("Copy") { onCopyCommand(node.command) }
            if (node.status == TimelineNodeStatus.FAILED) {
                buttons += buildActionButton("Retry") { onRetryCommand(node.command, node.cwd.orEmpty()) }
            }
        }
        if (!node.toolName.isNullOrBlank() && node.status == TimelineNodeStatus.FAILED) {
            buttons += buildActionButton("Retry") { onRetryTool(node.toolName, node.toolInput.orEmpty()) }
        }
        return buttons
    }

    private var lastTurns: List<TimelineTurnViewModel> = emptyList()
    private var lastForceAutoScroll: Boolean = false

    private fun renderTurnsSnapshot(anchorNodeId: String? = null) {
        val anchor = anchorNodeId?.let(::captureAnchor)
        renderTurns(lastTurns, forceAutoScroll = false)
        anchor?.let(::restoreAnchorAfterLayout)
    }

    override fun addNotify() {
        super.addNotify()
        background = Colors.APP_BG
    }

    fun updateTurns(
        turns: List<TimelineTurnViewModel>,
        forceAutoScroll: Boolean,
    ) {
        lastTurns = turns
        lastForceAutoScroll = forceAutoScroll
        renderTurns(turns, forceAutoScroll)
    }

    private fun effectiveExpanded(node: TimelineNodeViewModel): Boolean {
        expansionOverrides[node.id]?.let { return it }
        return node.expanded
    }

    private fun isExpandable(node: TimelineNodeViewModel): Boolean {
        if (!TimelineNodePresentation.forKind(node.kind).isToggleable) return false
        return !node.command.isNullOrBlank() ||
            !node.cwd.isNullOrBlank() ||
            !node.toolInput.isNullOrBlank() ||
            !node.toolOutput.isNullOrBlank() ||
            node.body.isNotBlank()
    }

    private fun collapsedSummary(node: TimelineNodeViewModel): String {
        val source = when {
            !node.command.isNullOrBlank() -> node.command
            !node.body.isBlank() -> node.body
            !node.toolInput.isNullOrBlank() -> node.toolInput
            else -> node.title
        }
        return source.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(140).orEmpty()
    }

    private fun nodeTitle(node: TimelineNodeViewModel): String {
        return when (node.kind) {
            TimelineNodeKind.ASSISTANT_NOTE -> "Assistant"
            TimelineNodeKind.THINKING -> "Thinking"
            TimelineNodeKind.TOOL_STEP -> node.toolName ?: node.title
            TimelineNodeKind.COMMAND_STEP -> "Command"
            TimelineNodeKind.RESULT -> "Result"
            TimelineNodeKind.FAILURE -> when {
                !node.command.isNullOrBlank() -> "Command Failed"
                !node.toolName.isNullOrBlank() -> "${node.toolName} failed"
                else -> "Failure"
            }
            TimelineNodeKind.SYSTEM_AUX -> "System"
        }
    }

    private fun nodeSubtitle(node: TimelineNodeViewModel): String {
        val parts = mutableListOf<String>()
        node.timestamp?.let { parts += formatClockTime(it) }
        if (node.exitCode != null) {
            parts += "exit ${node.exitCode}"
        }
        parts += when (node.status) {
            TimelineNodeStatus.RUNNING -> "running"
            TimelineNodeStatus.SUCCESS -> "done"
            TimelineNodeStatus.FAILED -> "failed"
            TimelineNodeStatus.SKIPPED -> "skipped"
        }
        return parts.joinToString(" · ")
    }

    private fun statusColor(status: TimelineNodeStatus): Color {
        return when (status) {
            TimelineNodeStatus.RUNNING -> Colors.WARNING
            TimelineNodeStatus.SUCCESS -> Colors.SUCCESS
            TimelineNodeStatus.FAILED -> Colors.FAILURE
            TimelineNodeStatus.SKIPPED -> Colors.MUTED
        }
    }

    private fun buildDot(color: Color, size: Int): JComponent {
        return JPanel().apply {
            preferredSize = Dimension(size, size)
            minimumSize = Dimension(size, size)
            maximumSize = Dimension(size, size)
            background = color
            border = BorderFactory.createLineBorder(color, 1, true)
        }
    }

    private fun centerWrap(component: JComponent): JComponent {
        return JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            add(component)
        }
    }

    private fun buildExecutionNodeCard(
        node: TimelineNodeViewModel,
        expanded: Boolean,
        expandable: Boolean,
    ): JComponent {
        val card = JPanel(BorderLayout(0, 8))
        card.name = "node-card-${node.id}"
        card.isOpaque = true
        card.background = Colors.EXECUTION_BG
        card.alignmentX = LEFT_ALIGNMENT
        card.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        card.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Colors.EXECUTION_BORDER, 1, true),
            BorderFactory.createEmptyBorder(9, 10, 9, 10),
        )

        val header = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
        }
        val titleWrap = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        val titleLine = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
        if (expandable) {
            titleLine.add(JLabel(if (expanded) "\u25BE" else "\u25B8").apply {
                foreground = Colors.TEXT_SECONDARY
                font = font.deriveFont(12f)
            })
            titleLine.add(Box.createHorizontalStrut(6))
        }
        titleLine.add(JLabel(executionTitle(node)).apply {
            foreground = Colors.TEXT_PRIMARY
            font = font.deriveFont(Font.BOLD, 12.5f)
        })
        titleWrap.add(titleLine)
        val subtitle = executionSubtitle(node)
        if (subtitle.isNotBlank()) {
            titleWrap.add(Box.createVerticalStrut(2))
            titleWrap.add(JLabel(subtitle).apply {
                foreground = Colors.TEXT_SECONDARY
                font = font.deriveFont(11f)
            })
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
        }
        buildNodeButtons(node).forEach(actions::add)
        actions.add(buildDot(statusColor(node.status), 8))

        header.add(titleWrap, BorderLayout.CENTER)
        header.add(actions, BorderLayout.EAST)

        val bodyPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        if (expanded) {
            when (node.kind) {
                TimelineNodeKind.TOOL_STEP -> {
                    if (!node.toolInput.isNullOrBlank()) {
                        bodyPanel.add(createMetaLabel("Input"))
                        bodyPanel.add(createBodyView(node.toolInput, Colors.TEXT_SECONDARY, 12f))
                    }
                    if (!node.toolOutput.isNullOrBlank()) {
                        bodyPanel.add(Box.createVerticalStrut(6))
                        bodyPanel.add(createMetaLabel("Output"))
                        bodyPanel.add(createBodyView(node.toolOutput, Colors.TEXT_PRIMARY, 12f))
                    }
                    if (node.toolInput.isNullOrBlank() && node.toolOutput.isNullOrBlank() && node.body.isNotBlank()) {
                        bodyPanel.add(createBodyView(node.body, Colors.TEXT_PRIMARY, 12f))
                    }
                }

                TimelineNodeKind.COMMAND_STEP -> {
                    if (!node.command.isNullOrBlank()) {
                        bodyPanel.add(createMetaLabel("Command"))
                        bodyPanel.add(createCodeView("$ ${node.command}"))
                    }
                    if (!node.cwd.isNullOrBlank()) {
                        bodyPanel.add(Box.createVerticalStrut(6))
                        bodyPanel.add(createMetaLabel("Directory"))
                        bodyPanel.add(createBodyView(node.cwd, Colors.TEXT_SECONDARY, 12f))
                    }
                    if (!node.body.isBlank()) {
                        bodyPanel.add(Box.createVerticalStrut(6))
                        bodyPanel.add(createMetaLabel("Output"))
                        bodyPanel.add(createBodyView(node.body, Colors.TEXT_PRIMARY, 12f))
                    }
                }

                else -> Unit
            }
        } else {
            bodyPanel.add(createBodyView(collapsedSummary(node), Colors.TEXT_SECONDARY, 12f))
        }

        card.add(header, BorderLayout.NORTH)
        card.add(bodyPanel, BorderLayout.CENTER)
        if (expandable) {
            installCardToggle(card) {
                expansionOverrides[node.id] = !expanded
                renderTurnsSnapshot(anchorNodeId = node.id)
            }
        }
        return card
    }

    private fun buildNarrativeNodeCard(
        node: TimelineNodeViewModel,
        presentation: TimelineNodePresentation,
    ): JComponent {
        val row = JPanel(BorderLayout(12, 0))
        row.name = "node-card-${node.id}"
        row.alignmentX = LEFT_ALIGNMENT
        row.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        row.isOpaque = false
        row.border = BorderFactory.createEmptyBorder(2, 0, 2, 0)

        val labelColumn = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            preferredSize = Dimension(92, 1)
            minimumSize = Dimension(92, 1)
            maximumSize = Dimension(92, Int.MAX_VALUE)
        }
        labelColumn.add(JLabel(inlineLabel(node)).apply {
            foreground = narrativeTitleColor(presentation)
            font = font.deriveFont(Font.BOLD, 10.5f)
        })

        val bodyColumn = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        if (node.body.isNotBlank()) {
            bodyColumn.add(
                createBodyView(
                    text = node.body,
                    foreground = narrativeBodyColor(presentation),
                    fontSize = if (presentation.chrome == TimelineNodeChrome.NARRATIVE) 13f else 12.5f,
                ),
            )
        }
        val subtitle = nodeSubtitle(node)
        if (subtitle.isNotBlank()) {
            if (node.body.isNotBlank()) {
                bodyColumn.add(Box.createVerticalStrut(4))
            }
            bodyColumn.add(JLabel(subtitle).apply {
                foreground = Colors.META
                font = font.deriveFont(11f)
                alignmentX = LEFT_ALIGNMENT
            })
        }

        row.add(labelColumn, BorderLayout.WEST)
        row.add(bodyColumn, BorderLayout.CENTER)
        return row
    }

    private fun createMetaLabel(text: String): JComponent {
        return JLabel(text.uppercase()).apply {
            foreground = Colors.META
            font = font.deriveFont(Font.BOLD, 10f)
            alignmentX = LEFT_ALIGNMENT
        }
    }

    private fun createBodyView(
        text: String,
        foreground: Color,
        fontSize: Float,
    ): JComponent {
        val html = MarkdownRenderer.renderToHtml(text)
        return MarkdownPane(
            html = wrapHtmlBody(html, foreground, fontSize),
        ).apply {
            border = BorderFactory.createEmptyBorder()
            alignmentX = LEFT_ALIGNMENT
        }
    }

    private fun createCodeView(text: String): JComponent {
        return JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Colors.CODE_BORDER, 1, true),
                BorderFactory.createEmptyBorder(7, 9, 7, 9),
            )
            background = Colors.CODE_BG
            foreground = Colors.TEXT_PRIMARY
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            alignmentX = LEFT_ALIGNMENT
        }
    }

    private fun buildActionButton(
        text: String,
        action: () -> Unit,
    ): JButton {
        return JButton(text).apply {
            isFocusable = false
            horizontalAlignment = SwingConstants.CENTER
            foreground = Colors.ACTION_TEXT
            background = Colors.ACTION_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Colors.ACTION_BORDER, 1, true),
                BorderFactory.createEmptyBorder(2, 7, 2, 7),
            )
            font = font.deriveFont(10.5f)
            addActionListener { action() }
        }
    }

    private fun installCardToggle(
        component: JComponent,
        onToggle: () -> Unit,
    ) {
        if (component is JButton) {
            return
        }
        component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                onToggle()
            }
        })
        component.components
            .filterIsInstance<JComponent>()
            .forEach { child -> installCardToggle(child, onToggle) }
    }

    private fun executionTitle(node: TimelineNodeViewModel): String {
        val fileName = node.filePath?.let { java.io.File(it).name }.orEmpty()
        return when {
            node.kind == TimelineNodeKind.TOOL_STEP && fileName.isNotBlank() && isFileMutationTool(node.toolName) -> "Updated $fileName"
            node.kind == TimelineNodeKind.TOOL_STEP && fileName.isNotBlank() -> fileName
            else -> nodeTitle(node)
        }
    }

    private fun executionSubtitle(node: TimelineNodeViewModel): String {
        val parts = mutableListOf<String>()
        if (node.filePath != null) {
            parts += node.filePath
        } else if (node.toolName != null && node.kind == TimelineNodeKind.TOOL_STEP) {
            parts += node.toolName
        }
        val subtitle = nodeSubtitle(node)
        if (subtitle.isNotBlank()) {
            parts += subtitle
        }
        return parts.joinToString(" · ")
    }

    private fun isFileMutationTool(toolName: String?): Boolean {
        val normalized = toolName?.lowercase().orEmpty()
        return normalized.contains("edit") ||
            normalized.contains("write") ||
            normalized.contains("patch") ||
            normalized.contains("apply")
    }

    private fun inlineLabel(node: TimelineNodeViewModel): String {
        return when (node.kind) {
            TimelineNodeKind.ASSISTANT_NOTE -> "ASSISTANT"
            TimelineNodeKind.THINKING -> "THINKING"
            TimelineNodeKind.RESULT -> "RESULT"
            TimelineNodeKind.FAILURE -> "FAILED"
            TimelineNodeKind.SYSTEM_AUX -> "SYSTEM"
            TimelineNodeKind.TOOL_STEP -> "TOOL"
            TimelineNodeKind.COMMAND_STEP -> "COMMAND"
        }
    }

    private fun narrativeTitleColor(presentation: TimelineNodePresentation): Color {
        return when (presentation.chrome) {
            TimelineNodeChrome.ALERT -> Colors.FAILURE_TEXT
            TimelineNodeChrome.RESULT -> Colors.RESULT_TEXT
            TimelineNodeChrome.SUPPORTING -> Colors.TEXT_SECONDARY
            else -> Colors.TEXT_SECONDARY
        }
    }

    private fun narrativeBodyColor(presentation: TimelineNodePresentation): Color {
        return when (presentation.chrome) {
            TimelineNodeChrome.ALERT -> Colors.FAILURE_TEXT
            TimelineNodeChrome.SUPPORTING -> Colors.TEXT_SECONDARY
            else -> Colors.TEXT_PRIMARY
        }
    }

    private data class ScrollAnchor(
        val nodeId: String,
        val relativeTop: Int,
    )

    private fun captureAnchor(nodeId: String): ScrollAnchor? {
        val card = findNodeCard(nodeId) ?: return null
        val relativeTop = card.y - scrollPane.viewport.viewPosition.y
        return ScrollAnchor(nodeId = nodeId, relativeTop = relativeTop)
    }

    private fun restoreAnchorAfterLayout(anchor: ScrollAnchor) {
        val restore = Runnable {
            layoutHierarchy(this)
            findNodeCard(anchor.nodeId)?.let { card ->
                val targetY = (card.y - anchor.relativeTop).coerceAtLeast(0)
                scrollPane.viewport.viewPosition = Point(0, targetY)
            }
        }
        val application = ApplicationManager.getApplication()
        if (application != null) {
            application.invokeLater { restore.run() }
        } else {
            restore.run()
        }
    }

    private fun findNodeCard(nodeId: String): JPanel? {
        return findNamedPanel(contentPanel, "node-card-$nodeId")
    }

    private fun findNamedPanel(component: Container, name: String): JPanel? {
        component.components.forEach { child ->
            if (child is JPanel && child.name == name) {
                return child
            }
            if (child is Container) {
                val nested = findNamedPanel(child, name)
                if (nested != null) {
                    return nested
                }
            }
        }
        return null
    }

    private fun layoutHierarchy(component: java.awt.Component) {
        if (component is Container) {
            component.doLayout()
            component.validate()
            component.components.forEach(::layoutHierarchy)
        }
    }

    private fun formatClockTime(epochMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    }

    private fun formatDuration(durationMs: Long): String {
        return ToolWindowUiText.formatDuration(durationMs)
    }

    private fun wrapHtmlBody(
        bodyHtml: String,
        foreground: Color,
        fontSize: Float,
    ): String {
        val fontFamily = UIManager.getFont("Label.font")?.family ?: "SansSerif"
        return """
            <html>
              <head>
                <style>
                  body {
                    margin: 0;
                    color: ${toCssColor(foreground)};
                    font-family: '$fontFamily';
                    font-size: ${fontSize.toInt()}px;
                    line-height: 1.55;
                  }
                  p, ul, ol, pre, blockquote, h1, h2, h3, h4, h5, h6 { margin: 0 0 8px 0; }
                  ul, ol { padding-left: 20px; }
                  li + li { margin-top: 4px; }
                  pre {
                    white-space: pre-wrap;
                    border: 1px solid ${toCssColor(Colors.CODE_BORDER)};
                    background: ${toCssColor(Colors.CODE_BG)};
                    color: ${toCssColor(Colors.TEXT_PRIMARY)};
                    padding: 8px 10px;
                    border-radius: 8px;
                    overflow-wrap: anywhere;
                  }
                  code {
                    font-family: 'SF Mono', Menlo, Consolas, monospace;
                    background: ${toCssColor(Colors.CODE_BG)};
                    color: ${toCssColor(Colors.TEXT_PRIMARY)};
                    padding: 1px 4px;
                    border-radius: 4px;
                  }
                  pre code {
                    background: transparent;
                    padding: 0;
                  }
                  blockquote {
                    border-left: 3px solid ${toCssColor(Colors.EXECUTION_BORDER)};
                    padding-left: 10px;
                    color: ${toCssColor(Colors.TEXT_SECONDARY)};
                  }
                  a { color: ${toCssColor(Colors.LINK)}; }
                </style>
              </head>
              <body>$bodyHtml</body>
            </html>
        """.trimIndent()
    }

    private fun toCssColor(color: Color): String {
        return "#%02x%02x%02x".format(color.red, color.green, color.blue)
    }

    private class MarkdownPane(
        html: String,
    ) : JEditorPane("text/html", html) {
        init {
            isEditable = false
            isOpaque = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            isFocusable = false
        }

        override fun getPreferredSize(): Dimension {
            val availableWidth = resolveAvailableWidth()
            setSize(availableWidth, Int.MAX_VALUE / 4)
            val preferred = super.getPreferredSize()
            return Dimension(availableWidth, preferred.height)
        }

        override fun getMaximumSize(): Dimension = preferredSize

        private fun resolveAvailableWidth(): Int {
            val baseWidth = ancestorWidths().minOrNull() ?: 320
            return (baseWidth - 56).coerceAtLeast(120)
        }

        private fun ancestorWidths(): List<Int> {
            val widths = mutableListOf<Int>()
            var current: Container? = parent
            while (current != null) {
                val width = when (current) {
                    is JViewport -> current.extentSize.width
                    else -> current.width
                }
                if (width > 0) {
                    widths += width
                }
                current = current.parent
            }
            return widths
        }
    }

    private class TimelineContentPanel : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(
            visibleRect: Rectangle?,
            orientation: Int,
            direction: Int,
        ): Int = 24

        override fun getScrollableBlockIncrement(
            visibleRect: Rectangle?,
            orientation: Int,
            direction: Int,
        ): Int = visibleRect?.height ?: 120

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    private object Colors {
        val APP_BG = AssistantUiTheme.APP_BG
        val TASK_BG = AssistantUiTheme.CHROME_BG
        val TASK_BORDER = AssistantUiTheme.BORDER
        val TASK_LABEL = AssistantUiTheme.ACCENT
        val EXECUTION_BG = AssistantUiTheme.CHROME_BG
        val EXECUTION_BORDER = AssistantUiTheme.BORDER
        val RESULT_TEXT = JBColor(0xD8F3E4, 0xD8F3E4)
        val CODE_BG = AssistantUiTheme.SURFACE_SUBTLE
        val CODE_BORDER = AssistantUiTheme.BORDER_STRONG
        val ACTION_BG = AssistantUiTheme.SURFACE_SUBTLE
        val ACTION_BORDER = AssistantUiTheme.BORDER_SUBTLE
        val ACTION_TEXT = AssistantUiTheme.TEXT_SECONDARY
        val TEXT_PRIMARY = AssistantUiTheme.TEXT_PRIMARY
        val TEXT_SECONDARY = AssistantUiTheme.TEXT_SECONDARY
        val LINK = AssistantUiTheme.ACCENT
        val META = AssistantUiTheme.TEXT_MUTED
        val SUCCESS = AssistantUiTheme.SUCCESS
        val WARNING = AssistantUiTheme.WARNING
        val FAILURE = AssistantUiTheme.DANGER
        val FAILURE_TEXT = JBColor(0xF3C3C7, 0xF3C3C7)
        val MUTED = AssistantUiTheme.TEXT_MUTED
    }
}
