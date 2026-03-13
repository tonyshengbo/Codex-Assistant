package com.codex.assistant.toolwindow.timeline

import com.codex.assistant.model.ChatMessage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.UIManager

class ConversationTimelinePanel(
    private val onCopyMessage: (String) -> Unit,
    private val onOpenFile: (String) -> Unit,
    private val onRetryTool: (String, String) -> Unit,
    private val onRetryCommand: (String, String) -> Unit,
    private val onCopyCommand: (String) -> Unit,
) : JPanel(BorderLayout()) {
    private val contentPanel = JPanel()
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
                contentPanel.add(buildTurnPanel(turn, showLine = index != turns.lastIndex))
                if (index != turns.lastIndex) {
                    contentPanel.add(Box.createVerticalStrut(8))
                }
            }
        }
        contentPanel.revalidate()
        contentPanel.repaint()
        if (autoScroll) {
            ApplicationManager.getApplication().invokeLater { scrollToBottom() }
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

    private fun buildTurnPanel(
        turn: TimelineTurnViewModel,
        showLine: Boolean,
    ): JComponent {
        val panel = JPanel(BorderLayout(12, 0))
        panel.isOpaque = false

        val gutter = JPanel(BorderLayout())
        gutter.isOpaque = false
        gutter.preferredSize = Dimension(16, 1)
        gutter.minimumSize = Dimension(16, 1)

        val dotColor = statusColor(turn.footerStatus)
        gutter.add(centerWrap(buildDot(dotColor, 9)), BorderLayout.NORTH)
        if (showLine) {
            val line = JPanel(BorderLayout())
            line.isOpaque = false
            line.border = BorderFactory.createEmptyBorder(0, 7, 0, 7)
            line.add(JPanel().apply {
                background = Colors.TIMELINE
                preferredSize = Dimension(1, 1)
            }, BorderLayout.CENTER)
            gutter.add(line, BorderLayout.CENTER)
        }

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

        panel.add(gutter, BorderLayout.WEST)
        panel.add(main, BorderLayout.CENTER)
        return panel
    }

    private fun buildUserBubble(message: ChatMessage): JComponent {
        val wrap = JPanel(BorderLayout())
        wrap.isOpaque = false

        val right = JPanel()
        right.layout = BoxLayout(right, BoxLayout.Y_AXIS)
        right.isOpaque = false

        val meta = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        meta.isOpaque = false
        meta.add(JLabel(formatClockTime(message.timestamp)).apply {
            foreground = Colors.META
            font = font.deriveFont(11f)
        })
        meta.add(buildActionButton("Copy") { onCopyMessage(message.id) }.apply {
            foreground = Colors.META
        })

        val bubble = JPanel(BorderLayout())
        bubble.background = Colors.USER_BUBBLE
        bubble.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Colors.USER_BUBBLE_BORDER, 1, true),
            BorderFactory.createEmptyBorder(12, 14, 12, 14),
        )
        bubble.add(createBodyView(message.content, Colors.USER_TEXT, fontSize = 14f), BorderLayout.CENTER)
        bubble.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        val bubbleWrap = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        bubbleWrap.isOpaque = false
        bubbleWrap.add(bubble)

        right.add(meta)
        right.add(Box.createVerticalStrut(6))
        right.add(bubbleWrap)
        wrap.add(right, BorderLayout.CENTER)
        return wrap
    }

    private fun buildNodeCard(node: TimelineNodeViewModel): JComponent {
        val expanded = effectiveExpanded(node)
        val card = JPanel(BorderLayout(0, 8))
        card.background = cardBackground(node)
        card.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(cardBorder(node), 1, true),
            BorderFactory.createEmptyBorder(10, 12, 10, 12),
        )

        val header = JPanel(BorderLayout(8, 0))
        header.isOpaque = false

        val titleWrap = JPanel()
        titleWrap.layout = BoxLayout(titleWrap, BoxLayout.Y_AXIS)
        titleWrap.isOpaque = false
        titleWrap.add(JLabel(nodeTitle(node)).apply {
            foreground = Colors.TEXT_PRIMARY
            font = font.deriveFont(Font.BOLD, 13f)
        })
        val subtitle = nodeSubtitle(node)
        if (subtitle.isNotBlank()) {
            titleWrap.add(Box.createVerticalStrut(2))
            titleWrap.add(JLabel(subtitle).apply {
                foreground = Colors.TEXT_SECONDARY
                font = font.deriveFont(11f)
            })
        }

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        actions.isOpaque = false
        buildNodeButtons(node, expanded).forEach(actions::add)
        actions.add(buildDot(statusColor(node.status), 8))

        header.add(titleWrap, BorderLayout.CENTER)
        header.add(actions, BorderLayout.EAST)
        card.add(header, BorderLayout.NORTH)

        val bodyPanel = JPanel()
        bodyPanel.layout = BoxLayout(bodyPanel, BoxLayout.Y_AXIS)
        bodyPanel.isOpaque = false

        if (expanded) {
            when (node.kind) {
                TimelineNodeKind.ASSISTANT_NOTE,
                TimelineNodeKind.THINKING,
                TimelineNodeKind.RESULT,
                TimelineNodeKind.SYSTEM_AUX,
                TimelineNodeKind.FAILURE,
                -> if (node.body.isNotBlank()) {
                    bodyPanel.add(createBodyView(node.body, bodyColor(node), fontSize = 12.5f))
                }

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
            }
        } else {
            bodyPanel.add(createBodyView(collapsedSummary(node), bodyColor(node), 12f))
        }

        card.add(bodyPanel, BorderLayout.CENTER)
        return card
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

    private fun buildNodeButtons(
        node: TimelineNodeViewModel,
        expanded: Boolean,
    ): List<JComponent> {
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
        if (isExpandable(node)) {
            buttons += buildActionButton(if (expanded) "Collapse" else "Expand") {
                expansionOverrides[node.id] = !expanded
                renderTurnsSnapshot()
            }
        }
        return buttons
    }

    private var lastTurns: List<TimelineTurnViewModel> = emptyList()
    private var lastForceAutoScroll: Boolean = false

    private fun renderTurnsSnapshot() {
        renderTurns(lastTurns, lastForceAutoScroll)
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
        if (node.kind == TimelineNodeKind.ASSISTANT_NOTE || node.kind == TimelineNodeKind.SYSTEM_AUX) {
            return false
        }
        if (node.kind == TimelineNodeKind.RESULT) {
            return node.body.contains('\n') || node.body.length > 180
        }
        return node.body.isNotBlank() || !node.command.isNullOrBlank() || !node.toolInput.isNullOrBlank()
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

    private fun bodyColor(node: TimelineNodeViewModel): Color {
        return when (node.kind) {
            TimelineNodeKind.FAILURE -> Colors.FAILURE_TEXT
            TimelineNodeKind.SYSTEM_AUX -> Colors.TEXT_SECONDARY
            else -> Colors.TEXT_PRIMARY
        }
    }

    private fun cardBackground(node: TimelineNodeViewModel): Color {
        return when (node.kind) {
            TimelineNodeKind.FAILURE -> Colors.FAILURE_BG
            TimelineNodeKind.RESULT -> Colors.RESULT_BG
            TimelineNodeKind.ASSISTANT_NOTE -> Colors.NOTE_BG
            TimelineNodeKind.SYSTEM_AUX -> Colors.SYSTEM_BG
            else -> Colors.CARD_BG
        }
    }

    private fun cardBorder(node: TimelineNodeViewModel): Color {
        return when (node.kind) {
            TimelineNodeKind.FAILURE -> Colors.FAILURE_BORDER
            TimelineNodeKind.RESULT -> Colors.RESULT_BORDER
            TimelineNodeKind.ASSISTANT_NOTE -> Colors.NOTE_BORDER
            TimelineNodeKind.SYSTEM_AUX -> Colors.SYSTEM_BORDER
            else -> Colors.CARD_BORDER
        }
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
                BorderFactory.createEmptyBorder(8, 10, 8, 10),
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
                BorderFactory.createEmptyBorder(2, 8, 2, 8),
            )
            font = font.deriveFont(11f)
            addActionListener { action() }
        }
    }

    private fun formatClockTime(epochMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    }

    private fun formatDuration(durationMs: Long): String {
        return when {
            durationMs >= 1000L -> String.format("%.1fs", durationMs / 1000.0)
            else -> "${durationMs}ms"
        }
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
                    border-left: 3px solid ${toCssColor(Colors.CARD_BORDER)};
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
            val availableWidth = (parent?.width ?: 0).takeIf { it > 0 } ?: 520
            setSize(availableWidth, Int.MAX_VALUE / 4)
            val preferred = super.getPreferredSize()
            return Dimension(availableWidth, preferred.height)
        }

        override fun getMaximumSize(): Dimension = preferredSize
    }

    private object Colors {
        val APP_BG = JBColor(0x0B0F14, 0x0B0F14)
        val CARD_BG = JBColor(0x131922, 0x131922)
        val CARD_BORDER = JBColor(0x263444, 0x263444)
        val NOTE_BG = JBColor(0x12161D, 0x12161D)
        val NOTE_BORDER = JBColor(0x2D3A48, 0x2D3A48)
        val RESULT_BG = JBColor(0x121C18, 0x121C18)
        val RESULT_BORDER = JBColor(0x284537, 0x284537)
        val SYSTEM_BG = JBColor(0x11161D, 0x11161D)
        val SYSTEM_BORDER = JBColor(0x26303D, 0x26303D)
        val FAILURE_BG = JBColor(0x221416, 0x221416)
        val FAILURE_BORDER = JBColor(0x6A3238, 0x6A3238)
        val USER_BUBBLE = JBColor(0x2759B6, 0x2759B6)
        val USER_BUBBLE_BORDER = JBColor(0x3C79DD, 0x3C79DD)
        val USER_TEXT = JBColor(0xEFF5FF, 0xEFF5FF)
        val CODE_BG = JBColor(0x0F141B, 0x0F141B)
        val CODE_BORDER = JBColor(0x324154, 0x324154)
        val ACTION_BG = JBColor(0x1A2230, 0x1A2230)
        val ACTION_BORDER = JBColor(0x32455E, 0x32455E)
        val ACTION_TEXT = JBColor(0xC9D9F0, 0xC9D9F0)
        val TEXT_PRIMARY = JBColor(0xDCE6F4, 0xDCE6F4)
        val TEXT_SECONDARY = JBColor(0x9DB0C7, 0x9DB0C7)
        val LINK = JBColor(0x7FB2FF, 0x7FB2FF)
        val META = JBColor(0x73859C, 0x73859C)
        val TIMELINE = JBColor(0x2A3A4D, 0x2A3A4D)
        val SUCCESS = JBColor(0x60C46E, 0x60C46E)
        val WARNING = JBColor(0xE0B65F, 0xE0B65F)
        val FAILURE = JBColor(0xE06B73, 0xE06B73)
        val FAILURE_TEXT = JBColor(0xF2C1C5, 0xF2C1C5)
        val MUTED = JBColor(0x7C8AA0, 0x7C8AA0)
    }
}
