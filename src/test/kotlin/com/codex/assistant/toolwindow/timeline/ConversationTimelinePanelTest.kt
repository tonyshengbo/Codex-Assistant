package com.codex.assistant.toolwindow.timeline

import com.codex.assistant.model.ChatMessage
import com.codex.assistant.model.MessageRole
import java.awt.Component
import java.awt.Container
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationTimelinePanelTest {
    @Test
    fun `user prompts render as task request blocks instead of chat bubbles`() {
        val panel = ConversationTimelinePanel(
            onCopyMessage = {},
            onOpenFile = {},
            onRetryTool = { _, _ -> },
            onRetryCommand = { _, _ -> },
            onCopyCommand = {},
        )

        panel.updateTurns(
            turns = listOf(
                TimelineTurnViewModel(
                    id = "turn-task",
                    userMessage = ChatMessage(
                        id = "user-task",
                        role = MessageRole.USER,
                        content = "Refine the timeline layout to match the IDE plugin style.",
                        timestamp = 1_000L,
                    ),
                    nodes = listOf(
                        TimelineNodeViewModel(
                            id = "result-task",
                            kind = TimelineNodeKind.RESULT,
                            title = "Result",
                            body = "Working on it.",
                            status = TimelineNodeStatus.RUNNING,
                            expanded = true,
                        ),
                    ),
                    isRunning = true,
                    footerStatus = TimelineNodeStatus.RUNNING,
                    statusText = "Loading",
                ),
            ),
            forceAutoScroll = false,
        )

        panel.setSize(520, 700)
        layoutHierarchy(panel)

        val mainPanel = firstTurnMainPanel(panel)
        val taskBlock = mainPanel.components.filterIsInstance<JPanel>().first()

        assertTrue(collectLabels(taskBlock).contains("Task"))
        assertFalse(containsRightAlignedBubble(taskBlock))
    }

    @Test
    fun `turn rows do not reserve a dedicated left timeline gutter`() {
        val panel = ConversationTimelinePanel(
            onCopyMessage = {},
            onOpenFile = {},
            onRetryTool = { _, _ -> },
            onRetryCommand = { _, _ -> },
            onCopyCommand = {},
        )

        panel.updateTurns(
            turns = listOf(
                TimelineTurnViewModel(
                    id = "turn-1",
                    userMessage = ChatMessage(
                        id = "user-1",
                        role = MessageRole.USER,
                        content = "Inspect the current layout",
                        timestamp = 1_000L,
                    ),
                    nodes = listOf(
                        TimelineNodeViewModel(
                            id = "result-1",
                            kind = TimelineNodeKind.RESULT,
                            title = "Result",
                            body = "Completed.",
                            status = TimelineNodeStatus.SUCCESS,
                            expanded = true,
                        ),
                    ),
                    isRunning = false,
                    footerStatus = TimelineNodeStatus.SUCCESS,
                    statusText = "Done",
                ),
            ),
            forceAutoScroll = false,
        )

        val contentPanel = panel.components
            .filterIsInstance<javax.swing.JScrollPane>()
            .single()
            .viewport
            .view as JPanel
        val turnPanel = contentPanel.components.filterIsInstance<JPanel>().first(::isTurnPanel)

        assertEquals(1, turnPanel.componentCount)
    }

    @Test
    fun `turn content renders nodes in direct sequence without summary grouping`() {
        val panel = ConversationTimelinePanel(
            onCopyMessage = {},
            onOpenFile = {},
            onRetryTool = { _, _ -> },
            onRetryCommand = { _, _ -> },
            onCopyCommand = {},
        )

        panel.updateTurns(
            turns = listOf(
                TimelineTurnViewModel(
                    id = "turn-2",
                    userMessage = null,
                    nodes = listOf(
                        TimelineNodeViewModel(
                            id = "note-1",
                            kind = TimelineNodeKind.ASSISTANT_NOTE,
                            title = "Assistant",
                            body = "I inspected the file.",
                            status = TimelineNodeStatus.SUCCESS,
                            expanded = true,
                        ),
                        TimelineNodeViewModel(
                            id = "tool-1",
                            kind = TimelineNodeKind.TOOL_STEP,
                            title = "read_file",
                            body = "src/App.kt",
                            status = TimelineNodeStatus.SUCCESS,
                            expanded = false,
                        ),
                        TimelineNodeViewModel(
                            id = "result-1",
                            kind = TimelineNodeKind.RESULT,
                            title = "Result",
                            body = "Done.",
                            status = TimelineNodeStatus.SUCCESS,
                            expanded = true,
                        ),
                    ),
                    isRunning = false,
                    footerStatus = TimelineNodeStatus.SUCCESS,
                    statusText = "Done",
                ),
            ),
            forceAutoScroll = false,
        )

        val contentPanel = panel.components
            .filterIsInstance<javax.swing.JScrollPane>()
            .single()
            .viewport
            .view as JPanel
        val turnPanel = contentPanel.components.filterIsInstance<JPanel>().first(::isTurnPanel)
        val mainPanel = turnPanel.components.single() as JPanel

        assertEquals(7, mainPanel.componentCount)
        assertFalse(collectLabels(mainPanel).contains("Assistant Summary"))
        assertFalse(collectLabels(mainPanel).contains("Execution Trace"))
    }

    @Test
    fun `markdown views shrink with narrower viewport after resize`() {
        val panel = ConversationTimelinePanel(
            onCopyMessage = {},
            onOpenFile = {},
            onRetryTool = { _, _ -> },
            onRetryCommand = { _, _ -> },
            onCopyCommand = {},
        )

        panel.updateTurns(
            turns = listOf(
                TimelineTurnViewModel(
                    id = "turn-3",
                    userMessage = ChatMessage(
                        id = "user-3",
                        role = MessageRole.USER,
                        content = "This is a long user prompt that should wrap when the tool window becomes narrower.",
                        timestamp = 3_000L,
                    ),
                    nodes = listOf(
                        TimelineNodeViewModel(
                            id = "result-3",
                            kind = TimelineNodeKind.RESULT,
                            title = "Result",
                            body = "This is a long result body that should also wrap instead of preserving an older preferred width.",
                            status = TimelineNodeStatus.SUCCESS,
                            expanded = true,
                        ),
                    ),
                    isRunning = false,
                    footerStatus = TimelineNodeStatus.SUCCESS,
                    statusText = "Done",
                ),
            ),
            forceAutoScroll = false,
        )

        panel.setSize(900, 700)
        layoutHierarchy(panel)
        panel.setSize(320, 700)
        layoutHierarchy(panel)

        val viewportWidth = panel.components
            .filterIsInstance<javax.swing.JScrollPane>()
            .single()
            .viewport
            .extentSize
            .width
        val widestMarkdown = collectEditors(panel).maxOf { it.preferredSize.width }

        assertTrue(widestMarkdown <= viewportWidth, "markdown preferred width should shrink to viewport width")
    }

    @Test
    fun `expanding command details does not widen timeline beyond viewport`() {
        val panel = ConversationTimelinePanel(
            onCopyMessage = {},
            onOpenFile = {},
            onRetryTool = { _, _ -> },
            onRetryCommand = { _, _ -> },
            onCopyCommand = {},
        )

        fun commandTurn(expanded: Boolean) = listOf(
            TimelineTurnViewModel(
                id = "turn-4",
                userMessage = null,
                nodes = listOf(
                    TimelineNodeViewModel(
                        id = "command-4",
                        kind = TimelineNodeKind.COMMAND_STEP,
                        title = "Command",
                        body = "Command output line that should wrap inside the viewport.",
                        status = TimelineNodeStatus.RUNNING,
                        expanded = expanded,
                        command = "/bin/zsh -lc 'cd /Users/tonysheng/StudioProject/Codex-Assistant && ./gradlew runIde --stacktrace'",
                        cwd = "/Users/tonysheng/StudioProject/Codex-Assistant/.worktrees/ui-structured-console",
                    ),
                ),
                isRunning = true,
                footerStatus = TimelineNodeStatus.RUNNING,
                statusText = "Loading",
            ),
        )

        panel.updateTurns(
            turns = commandTurn(expanded = false),
            forceAutoScroll = false,
        )

        panel.setSize(360, 700)
        layoutHierarchy(panel)
        panel.updateTurns(
            turns = commandTurn(expanded = true),
            forceAutoScroll = false,
        )
        layoutHierarchy(panel)

        val scrollPane = panel.components
            .filterIsInstance<javax.swing.JScrollPane>()
            .single()
        val viewportWidth = scrollPane.viewport.extentSize.width
        val contentWidth = (scrollPane.viewport.view as JPanel).preferredSize.width
        val widestTextArea = collectTextAreas(panel).maxOf { it.preferredSize.width }

        assertTrue(contentWidth <= viewportWidth, "expanded command card should stay within viewport width")
        assertTrue(widestTextArea <= viewportWidth, "expanded command text should wrap within viewport width")
    }

    @Test
    fun `command cards toggle by clicking the card without dedicated expand buttons`() {
        val panel = ConversationTimelinePanel(
            onCopyMessage = {},
            onOpenFile = {},
            onRetryTool = { _, _ -> },
            onRetryCommand = { _, _ -> },
            onCopyCommand = {},
        )

        panel.updateTurns(
            turns = listOf(
                TimelineTurnViewModel(
                    id = "turn-4b",
                    userMessage = null,
                    nodes = listOf(
                        TimelineNodeViewModel(
                            id = "command-4b",
                            kind = TimelineNodeKind.COMMAND_STEP,
                            title = "Command",
                            body = "Command output line that should only appear after expanding the card.",
                            status = TimelineNodeStatus.SUCCESS,
                            expanded = false,
                            command = "/bin/zsh -lc 'cd /Users/tonysheng/StudioProject/Codex-Assistant && ./gradlew test'",
                            cwd = "/Users/tonysheng/StudioProject/Codex-Assistant/.worktrees/ui-structured-console",
                        ),
                    ),
                    isRunning = false,
                    footerStatus = TimelineNodeStatus.SUCCESS,
                    statusText = "Done",
                ),
            ),
            forceAutoScroll = false,
        )

        panel.setSize(480, 700)
        layoutHierarchy(panel)

        assertFalse(collectButtons(panel).any { it.text == "Expand" || it.text == "Collapse" })
        assertFalse(collectLabels(firstNodeCard(panel)).contains("COMMAND"))

        click(firstNodeCard(panel))
        layoutHierarchy(panel)

        assertTrue(collectLabels(firstNodeCard(panel)).contains("COMMAND"))
    }

    @Test
    fun `text explanation cards stay expanded and do not expose collapse affordance`() {
        val panel = ConversationTimelinePanel(
            onCopyMessage = {},
            onOpenFile = {},
            onRetryTool = { _, _ -> },
            onRetryCommand = { _, _ -> },
            onCopyCommand = {},
        )

        panel.updateTurns(
            turns = listOf(
                TimelineTurnViewModel(
                    id = "turn-4c",
                    userMessage = null,
                    nodes = listOf(
                        TimelineNodeViewModel(
                            id = "result-4c",
                            kind = TimelineNodeKind.RESULT,
                            title = "Result",
                            body = "First line of explanation.\nSecond line should stay visible.",
                            status = TimelineNodeStatus.SUCCESS,
                            expanded = false,
                        ),
                    ),
                    isRunning = false,
                    footerStatus = TimelineNodeStatus.SUCCESS,
                    statusText = "Done",
                ),
            ),
            forceAutoScroll = false,
        )

        panel.setSize(480, 700)
        layoutHierarchy(panel)

        assertFalse(collectButtons(panel).any { it.text == "Expand" || it.text == "Collapse" })
        assertTrue(collectEditorMarkup(panel).any { it.contains("Second line should stay visible.") })

        click(firstNodeCard(panel))
        layoutHierarchy(panel)

        assertTrue(collectEditorMarkup(panel).any { it.contains("Second line should stay visible.") })
    }

    @Test
    fun `lightweight nodes render as inline log rows instead of opaque cards`() {
        val panel = ConversationTimelinePanel(
            onCopyMessage = {},
            onOpenFile = {},
            onRetryTool = { _, _ -> },
            onRetryCommand = { _, _ -> },
            onCopyCommand = {},
        )

        panel.updateTurns(
            turns = listOf(
                TimelineTurnViewModel(
                    id = "turn-inline",
                    userMessage = null,
                    nodes = listOf(
                        TimelineNodeViewModel(
                            id = "assistant-inline",
                            kind = TimelineNodeKind.ASSISTANT_NOTE,
                            title = "Assistant",
                            body = "Inspecting the current tool window layout.",
                            status = TimelineNodeStatus.SUCCESS,
                            expanded = true,
                        ),
                        TimelineNodeViewModel(
                            id = "result-inline",
                            kind = TimelineNodeKind.RESULT,
                            title = "Result",
                            body = "Updated the render path.",
                            status = TimelineNodeStatus.SUCCESS,
                            expanded = true,
                        ),
                        TimelineNodeViewModel(
                            id = "failure-inline",
                            kind = TimelineNodeKind.FAILURE,
                            title = "Failure",
                            body = "The command exited with code 1.",
                            status = TimelineNodeStatus.FAILED,
                            expanded = true,
                        ),
                        TimelineNodeViewModel(
                            id = "system-inline",
                            kind = TimelineNodeKind.SYSTEM_AUX,
                            title = "System",
                            body = "Applied diff to src/App.kt",
                            status = TimelineNodeStatus.SUCCESS,
                            expanded = true,
                        ),
                    ),
                    isRunning = false,
                    footerStatus = TimelineNodeStatus.SUCCESS,
                    statusText = "Done",
                ),
            ),
            forceAutoScroll = false,
        )

        panel.setSize(520, 700)
        layoutHierarchy(panel)

        assertFalse(findNodeCard(panel, "assistant-inline").isOpaque)
        assertFalse(findNodeCard(panel, "result-inline").isOpaque)
        assertFalse(findNodeCard(panel, "failure-inline").isOpaque)
        assertFalse(findNodeCard(panel, "system-inline").isOpaque)
    }

    @Test
    fun `command details shrink with narrower viewport after resize`() {
        val panel = ConversationTimelinePanel(
            onCopyMessage = {},
            onOpenFile = {},
            onRetryTool = { _, _ -> },
            onRetryCommand = { _, _ -> },
            onCopyCommand = {},
        )

        panel.updateTurns(
            turns = listOf(
                TimelineTurnViewModel(
                    id = "turn-5",
                    userMessage = null,
                    nodes = listOf(
                        TimelineNodeViewModel(
                            id = "command-5",
                            kind = TimelineNodeKind.COMMAND_STEP,
                            title = "Command",
                            body = "Command output line that should wrap inside the viewport after a resize.",
                            status = TimelineNodeStatus.SUCCESS,
                            expanded = true,
                            command = "/bin/zsh -lc 'cd /Users/tonysheng/StudioProject/Codex-Assistant && ./gradlew runIde --stacktrace'",
                            cwd = "/Users/tonysheng/StudioProject/Codex-Assistant/.worktrees/ui-structured-console",
                        ),
                    ),
                    isRunning = false,
                    footerStatus = TimelineNodeStatus.SUCCESS,
                    statusText = "Done",
                ),
            ),
            forceAutoScroll = false,
        )

        panel.setSize(900, 700)
        layoutHierarchy(panel)
        panel.setSize(320, 700)
        layoutHierarchy(panel)

        val viewportWidth = panel.components
            .filterIsInstance<javax.swing.JScrollPane>()
            .single()
            .viewport
            .extentSize
            .width
        val horizontalScrollBar = panel.components
            .filterIsInstance<javax.swing.JScrollPane>()
            .single()
            .horizontalScrollBar
        val widestTextArea = collectTextAreas(panel).maxOf { it.preferredSize.width }
        val contentPanel = panel.components
            .filterIsInstance<javax.swing.JScrollPane>()
            .single()
            .viewport
            .view as JPanel
        val maxRightEdge = maxRightEdge(contentPanel)

        assertTrue(widestTextArea <= viewportWidth, "expanded command text should shrink to viewport width")
        assertTrue(maxRightEdge <= viewportWidth, "expanded command layout should stay within viewport bounds")
        assertTrue(
            horizontalScrollBar.maximum <= horizontalScrollBar.visibleAmount,
            "expanded command layout should not require horizontal scrolling",
        )
    }

    @Test
    fun `rerender after resize uses current narrow width for markdown layouts`() {
        val panel = ConversationTimelinePanel(
            onCopyMessage = {},
            onOpenFile = {},
            onRetryTool = { _, _ -> },
            onRetryCommand = { _, _ -> },
            onCopyCommand = {},
        )

        fun turn(expanded: Boolean) = listOf(
            TimelineTurnViewModel(
                id = "turn-6",
                userMessage = ChatMessage(
                    id = "user-6",
                    role = MessageRole.USER,
                    content = "This is a long user prompt that should still wrap after the tool window is narrowed and a command row is expanded.",
                    timestamp = 6_000L,
                ),
                nodes = listOf(
                    TimelineNodeViewModel(
                        id = "assistant-6",
                        kind = TimelineNodeKind.ASSISTANT_NOTE,
                        title = "Assistant",
                        body = "I am preparing to inspect the file and then run a command.",
                        status = TimelineNodeStatus.SUCCESS,
                        expanded = true,
                    ),
                    TimelineNodeViewModel(
                        id = "command-6",
                        kind = TimelineNodeKind.COMMAND_STEP,
                        title = "Command",
                        body = "Command output that should remain wrapped.",
                        status = TimelineNodeStatus.SUCCESS,
                        expanded = expanded,
                        command = "/bin/zsh -lc 'cd /Users/tonysheng/StudioProject/Codex-Assistant && ./gradlew runIde --stacktrace'",
                        cwd = "/Users/tonysheng/StudioProject/Codex-Assistant/.worktrees/ui-structured-console",
                    ),
                ),
                isRunning = false,
                footerStatus = TimelineNodeStatus.SUCCESS,
                statusText = "Done",
            ),
        )

        panel.updateTurns(
            turns = turn(expanded = false),
            forceAutoScroll = false,
        )
        panel.setSize(900, 700)
        layoutHierarchy(panel)

        panel.setSize(320, 700)
        panel.updateTurns(
            turns = turn(expanded = true),
            forceAutoScroll = false,
        )
        layoutHierarchy(panel)

        val scrollPane = panel.components
            .filterIsInstance<javax.swing.JScrollPane>()
            .single()
        val viewportWidth = scrollPane.viewport.extentSize.width
        val horizontalScrollBar = scrollPane.horizontalScrollBar
        val contentPanel = scrollPane.viewport.view as JPanel
        val maxRightEdge = maxRightEdge(contentPanel)
        val overflowing = overflowingComponents(contentPanel, viewportWidth).joinToString(" | ")

        assertTrue(
            maxRightEdge <= viewportWidth,
            "rerendered markdown should honor the current narrow viewport (right edge=$maxRightEdge, viewport=$viewportWidth, overflow=$overflowing)",
        )
        assertTrue(
            horizontalScrollBar.maximum <= horizontalScrollBar.visibleAmount,
            "rerender after resize should not introduce horizontal scrolling (max=${horizontalScrollBar.maximum}, visible=${horizontalScrollBar.visibleAmount})",
        )
    }

    @Test
    fun `toggling an execution card keeps its top anchored in the viewport`() {
        val panel = ConversationTimelinePanel(
            onCopyMessage = {},
            onOpenFile = {},
            onRetryTool = { _, _ -> },
            onRetryCommand = { _, _ -> },
            onCopyCommand = {},
        )

        panel.updateTurns(
            turns = listOf(
                TimelineTurnViewModel(
                    id = "turn-anchor",
                    userMessage = null,
                    nodes = List(8) { index ->
                        TimelineNodeViewModel(
                            id = "note-$index",
                            kind = TimelineNodeKind.ASSISTANT_NOTE,
                            title = "Assistant",
                            body = "Narrative block $index that adds vertical height to the timeline.",
                            status = TimelineNodeStatus.SUCCESS,
                            expanded = true,
                        )
                    } + TimelineNodeViewModel(
                        id = "command-anchor",
                        kind = TimelineNodeKind.COMMAND_STEP,
                        title = "Command",
                        body = "Output line 1\nOutput line 2\nOutput line 3\nOutput line 4",
                        status = TimelineNodeStatus.SUCCESS,
                        expanded = false,
                        command = "/bin/zsh -lc 'echo anchored'",
                        cwd = "/tmp",
                    ),
                    isRunning = false,
                    footerStatus = TimelineNodeStatus.SUCCESS,
                    statusText = "Done",
                ),
            ),
            forceAutoScroll = false,
        )

        panel.setSize(420, 260)
        layoutHierarchy(panel)

        val scrollPane = panel.components.filterIsInstance<javax.swing.JScrollPane>().single()
        val targetBefore = findNodeCard(panel, "command-anchor")
        scrollPane.verticalScrollBar.value = (targetBefore.y - 24).coerceAtLeast(0)
        layoutHierarchy(panel)

        val anchoredBefore = findNodeCard(panel, "command-anchor").y - scrollPane.viewport.viewPosition.y

        click(findNodeCard(panel, "command-anchor"))
        layoutHierarchy(panel)

        val anchoredAfter = findNodeCard(panel, "command-anchor").y - scrollPane.viewport.viewPosition.y

        assertEquals(anchoredBefore, anchoredAfter, "expanded execution card should keep the same top anchor")
    }

    private fun isTurnPanel(component: JPanel): Boolean {
        return component.components.any { child: Component ->
            child is JPanel && child.layout is javax.swing.BoxLayout
        }
    }

    private fun collectLabels(component: Component): List<String> {
        return when (component) {
            is JLabel -> listOf(component.text)
            is JPanel -> component.components.flatMap(::collectLabels)
            else -> emptyList()
        }
    }

    private fun collectEditors(component: Component): List<JEditorPane> {
        return when (component) {
            is JEditorPane -> listOf(component)
            is Container -> component.components.flatMap(::collectEditors)
            else -> emptyList()
        }
    }

    private fun collectTextAreas(component: Component): List<JTextArea> {
        return when (component) {
            is JTextArea -> listOf(component)
            is Container -> component.components.flatMap(::collectTextAreas)
            else -> emptyList()
        }
    }

    private fun collectButtons(component: Component): List<JButton> {
        return when (component) {
            is JButton -> listOf(component)
            is Container -> component.components.flatMap(::collectButtons)
            else -> emptyList()
        }
    }

    private fun collectEditorMarkup(component: Component): List<String> {
        return when (component) {
            is JEditorPane -> listOf(component.text)
            is Container -> component.components.flatMap(::collectEditorMarkup)
            else -> emptyList()
        }
    }

    private fun firstNodeCard(panel: ConversationTimelinePanel): JPanel {
        val mainPanel = firstTurnMainPanel(panel)
        return mainPanel.components.filterIsInstance<JPanel>().first()
    }

    private fun firstTurnMainPanel(panel: ConversationTimelinePanel): JPanel {
        val contentPanel = panel.components
            .filterIsInstance<javax.swing.JScrollPane>()
            .single()
            .viewport
            .view as JPanel
        val turnPanel = contentPanel.components.filterIsInstance<JPanel>().first(::isTurnPanel)
        return turnPanel.components.single() as JPanel
    }

    private fun findNodeCard(panel: ConversationTimelinePanel, nodeId: String): JPanel {
        val contentPanel = panel.components
            .filterIsInstance<javax.swing.JScrollPane>()
            .single()
            .viewport
            .view as JPanel
        return findNamedPanel(contentPanel, "node-card-$nodeId")
            ?: error("Unable to find node card for $nodeId")
    }

    private fun findNamedPanel(component: Component, name: String): JPanel? {
        return when {
            component is JPanel && component.name == name -> component
            component is Container -> component.components.firstNotNullOfOrNull { child ->
                findNamedPanel(child, name)
            }
            else -> null
        }
    }

    private fun click(component: Component) {
        val event = MouseEvent(
            component,
            MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(),
            0,
            8,
            8,
            1,
            false,
        )
        component.mouseListeners.forEach { it.mouseClicked(event) }
    }

    private fun layoutHierarchy(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.validate()
            component.components.forEach(::layoutHierarchy)
        }
    }

    private fun containsRightAlignedBubble(component: Component): Boolean {
        return when (component) {
            is JPanel -> {
                val layout = component.layout
                val selfMatches = layout is FlowLayout && layout.alignment == FlowLayout.RIGHT
                selfMatches || component.components.any(::containsRightAlignedBubble)
            }
            is Container -> component.components.any(::containsRightAlignedBubble)
            else -> false
        }
    }

    private fun maxRightEdge(component: Component, offsetX: Int = 0): Int {
        val absoluteRightEdge = offsetX + component.x + component.width
        return if (component is Container && component.componentCount > 0) {
            maxOf(absoluteRightEdge, component.components.maxOf { child ->
                maxRightEdge(child, offsetX + component.x)
            })
        } else {
            absoluteRightEdge
        }
    }

    private fun overflowingComponents(
        component: Component,
        viewportWidth: Int,
        offsetX: Int = 0,
    ): List<String> {
        val absoluteX = offsetX + component.x
        val rightEdge = absoluteX + component.width
        val current = if (rightEdge > viewportWidth) {
            listOf("${component.javaClass.simpleName}(x=$absoluteX,w=${component.width},right=$rightEdge)")
        } else {
            emptyList()
        }
        return if (component is Container && component.componentCount > 0) {
            current + component.components.flatMap { child ->
                overflowingComponents(child, viewportWidth, absoluteX)
            }
        } else {
            current
        }
    }
}
