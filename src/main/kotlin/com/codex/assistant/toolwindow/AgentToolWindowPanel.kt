package com.codex.assistant.toolwindow

import com.codex.assistant.model.AgentEvent
import com.codex.assistant.model.ChatMessage
import com.codex.assistant.model.ContextFile
import com.codex.assistant.model.MessageRole
import com.codex.assistant.model.TimelineAction
import com.codex.assistant.model.TimelineActionCodec
import com.codex.assistant.model.label
import com.codex.assistant.provider.EngineCapabilities
import com.codex.assistant.provider.CodexModelCatalog
import com.codex.assistant.service.AgentChatService
import com.codex.assistant.toolwindow.timeline.ConversationTimelineBuilder
import com.codex.assistant.toolwindow.timeline.ConversationTimelinePanel
import com.codex.assistant.toolwindow.timeline.LiveCommandTrace
import com.codex.assistant.toolwindow.timeline.LiveNarrativeTrace
import com.codex.assistant.toolwindow.timeline.LiveToolTrace
import com.codex.assistant.toolwindow.timeline.LiveTurnSnapshot
import com.codex.assistant.toolwindow.timeline.TimelineNodeKind
import com.codex.assistant.toolwindow.timeline.TimelineNodeOrigin
import com.codex.assistant.toolwindow.timeline.TimelineNodeStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.Timer

class AgentToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val chatService = project.getService(AgentChatService::class.java)
    private val approvalUiPolicy = ApprovalUiPolicy()
    private val workspaceState = ToolWindowWorkspaceState()

    private val sessionTitleLabel = JLabel("New Session")
    private val sessionSubtitleLabel = JLabel("Task Console")
    private val backButton = JButton("Back")
    private val historyButton = JButton("History")
    private val settingsButton = JButton("Settings")
    private val streamLabel = JLabel()
    private val attachButton = JButton("Manage Context")
    private val sdkButton = JButton()
    private val modeChip = JButton()
    private val modelChip = JButton()
    private val usageLeftLabel = JLabel("--")
    private val editorContextPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
    private val editorContextLabel = JLabel()
    private val editorContextCloseButton = JButton("\u00D7")
    private val attachStatusLabel = JLabel()
    private var selectedEngineId: String = chatService.defaultEngineId()
    private var selectedModel: String = CodexModelCatalog.defaultModel

    private val timelineBuilder = ConversationTimelineBuilder()
    private val timelinePanel = ConversationTimelinePanel(
        onCopyMessage = { copyMessageToClipboard(it) },
        onOpenFile = { openFileInEditor(it) },
        onRetryTool = { name, input -> retryTool(name, input) },
        onRetryCommand = { command, cwd -> retryCommand(command, cwd) },
        onCopyCommand = { copyCommandToClipboard(it) },
    )
    private val consoleView = JPanel(BorderLayout())
    private val placeholderPanel = JPanel(BorderLayout())
    private val centerCards = JPanel(CardLayout())
    private val workspaceCards = JPanel(CardLayout())
    private val runContextBar = JPanel(BorderLayout())
    private val composerContainer = JPanel(BorderLayout())
    private val sessionHistoryView = SessionHistoryView(
        onOpenSession = { openSessionFromWorkspace(it) },
        onCreateSession = { createSessionFromWorkspace() },
        onDeleteSession = { deleteSessionFromWorkspace(it) },
    )
    private val settingsWorkspaceView = ToolWindowSettingsView(
        onSaved = {
            setStatusMessage("Saved")
            refreshHeaderState()
        },
    )
    private val contextFilesView = ContextFilesView(
        onAddFile = { chooseContextFile() },
        onFilesChanged = { updateAttachedFiles(it) },
    )

    private val inputArea = JBTextArea()
    private val inputScroll = JBScrollPane(inputArea)
    private val actionButton = JButton("Run")
    private val newChatButton = JButton("New Session")

    private val attachedFiles = linkedSetOf<String>()
    private var currentAssistantContentBuffer = StringBuilder()
    private var currentThinkingBuffer = StringBuilder()
    private val currentTimelineActionsBuffer = mutableListOf<TimelineAction>()
    private val currentToolEventsBuffer = mutableListOf<ToolTraceItem>()
    private val currentCommandEventsBuffer = mutableListOf<CommandTraceItem>()
    private val currentNarrativeEventsBuffer = mutableListOf<NarrativeTraceItem>()
    private var activeContentNarrativeId: String? = null
    private var currentFlowSequence: Int = 0
    private var currentStatusText: String = ""
    private var isRunning = false
    private var dismissedEditorContextPath: String? = null
    private var requestStartedAtMs: Long = 0L
    private var expandAllTools: Boolean = true
    private var turnActive: Boolean = false
    private var turnFinalized: Boolean = false
    private var cachedMessagesHtml: String = ""
    private var cachedMessageCount: Int = -1
    private var cachedLastMessageId: String = ""
    private var cachedToolDetailMode: Boolean = expandAllTools
    private val expandedToolMessageIds = linkedSetOf<String>()
    private val expandedThinkingMessageIds = linkedSetOf<String>()
    private val expandedCommandMessageIds = linkedSetOf<String>()
    private val expandedTurnIds = linkedSetOf<String>()
    private val previewRenderTimer = Timer(80) {
        renderPreviewBufferNow()
    }.apply {
        isRepeats = false
    }
    private val loadingTimer = Timer(1000) {
        if (isRunning) {
            refreshRunningStatusLabel()
        }
    }

    init {
        val initialModels = availableModels(selectedEngineId)
        if (selectedModel !in initialModels) {
            selectedModel = initialModels.firstOrNull() ?: CodexModelCatalog.defaultModel
        }
        border = BorderFactory.createEmptyBorder()
        background = AssistantUiTheme.APP_BG

        add(buildHeader(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        add(buildBottom(), BorderLayout.SOUTH)

        actionButton.addActionListener { onPrimaryAction() }
        attachButton.addActionListener { showWorkspace(ToolWindowView.CONTEXT_FILES) }
        sdkButton.addActionListener { showSdkMenu(sdkButton) }
        modelChip.addActionListener { showModelMenu(modelChip) }
        newChatButton.addActionListener { startNewSession() }
        settingsButton.addActionListener { showWorkspace(ToolWindowView.SETTINGS) }
        historyButton.addActionListener { showWorkspace(ToolWindowView.SESSION_HISTORY) }
        backButton.addActionListener { showWorkspace(ToolWindowView.CONSOLE) }
        installInputKeyBindings()
        styleHeaderButton(backButton)
        styleHeaderButton(historyButton)
        styleSettingsButton(settingsButton)
        AssistantUiTheme.toolbarButton(newChatButton)
        styleAttachButton(attachButton)
        styleStatusLabel(streamLabel)
        styleEditorContextPanel(editorContextPanel, editorContextLabel, editorContextCloseButton)
        styleSdkButton(sdkButton)
        styleChip(modeChip)
        styleChip(modelChip)
        stylePrimaryActionButton(actionButton)
        editorContextCloseButton.addActionListener { dismissCurrentEditorContext() }
        refreshChipLabels()

        refreshMessages()
        setRunningState(false)
        installEditorContextListener()
        refreshHeaderState()
    }

    private fun buildHeader(): JComponent {
        val root = JPanel(BorderLayout())
        root.isOpaque = true
        root.background = AssistantUiTheme.CHROME_BG
        root.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AssistantUiTheme.BORDER_SUBTLE),
            BorderFactory.createEmptyBorder(8, 10, 8, 10),
        )

        val titleStack = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            isOpaque = false
            AssistantUiTheme.title(sessionTitleLabel)
            AssistantUiTheme.subtitle(sessionSubtitleLabel)
            add(sessionTitleLabel)
            add(javax.swing.Box.createVerticalStrut(1))
            add(sessionSubtitleLabel)
        }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(backButton)
            add(titleStack)
        }

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(historyButton)
            add(newChatButton)
            add(settingsButton)
        }

        val topRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }

        runContextBar.isOpaque = false
        runContextBar.border = BorderFactory.createEmptyBorder(8, 0, 0, 0)

        val controlRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(sdkButton)
            add(modeChip)
            add(modelChip)
        }
        AssistantUiTheme.meta(usageLeftLabel, AssistantUiTheme.TEXT_SECONDARY)
        val rightStatus = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(usageLeftLabel)
        }

        val stack = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            isOpaque = false
            add(topRow)
            add(runContextBar)
        }
        runContextBar.add(controlRow, BorderLayout.WEST)
        runContextBar.add(rightStatus, BorderLayout.EAST)

        root.add(stack, BorderLayout.CENTER)
        return root
    }

    private fun buildCenter(): JComponent {
        val placeholderContainer = JPanel(BorderLayout())
        placeholderContainer.background = AssistantUiTheme.APP_BG
        val inner = JPanel()
        inner.layout = javax.swing.BoxLayout(inner, javax.swing.BoxLayout.Y_AXIS)
        inner.border = BorderFactory.createEmptyBorder(72, 28, 0, 28)
        inner.isOpaque = false

        val main = JLabel("Execution Workspace")
        main.font = main.font.deriveFont(Font.BOLD, 20f)
        main.foreground = AssistantUiTheme.TEXT_PRIMARY
        main.alignmentX = 0f

        val sub = JLabel("Run a task, inspect commands and file changes, and keep history, settings, and context inside the same plugin workspace.")
        sub.foreground = AssistantUiTheme.TEXT_MUTED
        sub.alignmentX = 0f

        inner.add(main)
        inner.add(javax.swing.Box.createVerticalStrut(6))
        inner.add(sub)

        placeholderContainer.add(inner, BorderLayout.NORTH)
        placeholderPanel.isOpaque = true
        placeholderPanel.background = AssistantUiTheme.APP_BG
        placeholderPanel.add(placeholderContainer, BorderLayout.CENTER)

        centerCards.isOpaque = true
        centerCards.background = AssistantUiTheme.APP_BG
        centerCards.add(placeholderPanel, CARD_PLACEHOLDER)
        centerCards.add(timelinePanel, CARD_MESSAGES)
        consoleView.isOpaque = true
        consoleView.background = AssistantUiTheme.APP_BG
        consoleView.add(centerCards, BorderLayout.CENTER)

        workspaceCards.isOpaque = true
        workspaceCards.background = AssistantUiTheme.APP_BG
        workspaceCards.add(consoleView, WORKSPACE_CONSOLE)
        workspaceCards.add(sessionHistoryView, WORKSPACE_HISTORY)
        workspaceCards.add(settingsWorkspaceView, WORKSPACE_SETTINGS)
        workspaceCards.add(contextFilesView, WORKSPACE_CONTEXT)
        return workspaceCards
    }

    private fun buildBottom(): JComponent {
        composerContainer.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, AssistantUiTheme.BORDER_SUBTLE),
            BorderFactory.createEmptyBorder(8, 10, 10, 10),
        )
        composerContainer.isOpaque = true
        composerContainer.background = AssistantUiTheme.CHROME_BG

        val compose = JPanel(BorderLayout(8, 8))
        compose.background = AssistantUiTheme.CHROME_BG
        compose.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AssistantUiTheme.BORDER_SUBTLE, 1, true),
            BorderFactory.createEmptyBorder(8, 8, 8, 8),
        )

        val metaRow = JPanel(BorderLayout(8, 0))
        metaRow.isOpaque = false

        val attachWrap = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        attachWrap.isOpaque = false
        attachWrap.add(attachButton)
        attachWrap.add(JLabel(" "))
        attachWrap.add(editorContextPanel)

        metaRow.add(attachWrap, BorderLayout.WEST)
        AssistantUiTheme.meta(attachStatusLabel, AssistantUiTheme.TEXT_SECONDARY)
        metaRow.add(attachStatusLabel, BorderLayout.EAST)

        val inputRow = JPanel(BorderLayout(8, 0))
        inputRow.isOpaque = false
        inputArea.toolTipText = "Describe a task, ask for code changes, or explain a file. Enter runs, Shift+Enter adds a newline."
        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.rows = 4
        inputArea.background = AssistantUiTheme.SURFACE_SUBTLE
        inputArea.foreground = AssistantUiTheme.TEXT_PRIMARY
        inputArea.caretColor = AssistantUiTheme.TEXT_PRIMARY
        inputArea.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        inputScroll.border = BorderFactory.createLineBorder(AssistantUiTheme.BORDER_SUBTLE, 1, true)
        inputScroll.background = inputArea.background
        inputScroll.preferredSize = Dimension(300, 104)

        val bottomRow = JPanel(BorderLayout(8, 0))
        bottomRow.isOpaque = false

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        actions.isOpaque = false
        actions.add(actionButton)

        inputRow.add(inputScroll, BorderLayout.CENTER)
        bottomRow.add(streamLabel, BorderLayout.WEST)
        bottomRow.add(actions, BorderLayout.EAST)

        compose.add(metaRow, BorderLayout.NORTH)
        compose.add(inputRow, BorderLayout.CENTER)
        compose.add(bottomRow, BorderLayout.SOUTH)

        composerContainer.add(compose, BorderLayout.CENTER)
        return composerContainer
    }

    private fun openContextDialog() {
        val dialog = ContextFilesDialog(project, attachedFiles)
        if (dialog.showAndGet()) {
            attachedFiles.clear()
            attachedFiles.addAll(dialog.attachedFiles())
            refreshChipLabels()
        }
    }

    private fun submitPrompt() {
        if (isRunning) {
            return
        }
        refreshChipLabels()
        val prompt = inputArea.text.trim()
        if (prompt.isBlank()) {
            return
        }
        startAgentRun(prompt)
        inputArea.text = ""
    }

    private fun startAgentRun(userPrompt: String) {
        val userMessage = ChatMessage(role = MessageRole.USER, content = userPrompt)
        chatService.addMessage(userMessage)
        refreshMessages()

        currentAssistantContentBuffer = StringBuilder()
        currentThinkingBuffer = StringBuilder()
        currentTimelineActionsBuffer.clear()
        currentToolEventsBuffer.clear()
        currentCommandEventsBuffer.clear()
        currentNarrativeEventsBuffer.clear()
        activeContentNarrativeId = null
        currentFlowSequence = 0
        currentStatusText = ""
        turnActive = true
        turnFinalized = false
        requestStartedAtMs = System.currentTimeMillis()
        loadingTimer.start()
        setRunningState(true)
        refreshRunningStatusLabel()

        chatService.runAgent(
            engineId = selectedEngineId,
            model = selectedModel,
            prompt = userPrompt,
            contextFiles = collectContextFiles(),
        ) { action ->
            ApplicationManager.getApplication().invokeLater {
                handleTimelineAction(action)
            }
        }
    }

    private fun handleHyperlinkAction(description: String?) {
        val raw = description ?: return
        if (!raw.startsWith("action://")) return
        val action = raw.substringAfter("action://").substringBefore("?")
        val query = raw.substringAfter("?", "")
        if (query.isBlank()) return
        val params = query.split("&")
            .mapNotNull {
                val idx = it.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = it.substring(0, idx)
                val value = URLDecoder.decode(it.substring(idx + 1), StandardCharsets.UTF_8)
                key to value
            }.toMap()
        when (action) {
            "retry-tool" -> {
                val name = params["name"].orEmpty()
                val input = params["input"].orEmpty()
                retryTool(name, input)
            }

            "copy-message" -> {
                val messageId = params["id"].orEmpty()
                copyMessageToClipboard(messageId)
            }

            "toggle-tools" -> {
                val messageId = params["id"].orEmpty()
                if (messageId.isBlank()) return
                if (!expandedToolMessageIds.add(messageId)) {
                    expandedToolMessageIds.remove(messageId)
                }
                invalidateMessageHtmlCache()
                refreshMessages()
            }

            "toggle-thinking" -> {
                val messageId = params["id"].orEmpty()
                if (messageId.isBlank()) return
                if (!expandedThinkingMessageIds.add(messageId)) {
                    expandedThinkingMessageIds.remove(messageId)
                }
                invalidateMessageHtmlCache()
                refreshMessages()
            }

            "toggle-commands" -> {
                val messageId = params["id"].orEmpty()
                if (messageId.isBlank()) return
                if (!expandedCommandMessageIds.add(messageId)) {
                    expandedCommandMessageIds.remove(messageId)
                }
                invalidateMessageHtmlCache()
                refreshMessages()
            }

            "toggle-turn" -> {
                val turnId = params["id"].orEmpty()
                if (turnId.isBlank()) return
                if (!expandedTurnIds.add(turnId)) {
                    expandedTurnIds.remove(turnId)
                }
                invalidateMessageHtmlCache()
                refreshMessages()
            }

            "open-file" -> {
                val path = params["path"].orEmpty()
                openFileInEditor(path)
            }

            "retry-command" -> {
                val command = params["command"].orEmpty()
                val cwd = params["cwd"].orEmpty()
                retryCommand(command, cwd)
            }

            "copy-command" -> {
                val command = params["command"].orEmpty()
                copyCommandToClipboard(command)
            }
        }
    }

    private fun retryTool(name: String, input: String) {
        if (isRunning) return
        val retryPrompt = buildString {
            append("请重试上一步失败的工具调用。")
            if (name.isNotBlank()) append("\n工具: ").append(name)
            if (input.isNotBlank()) append("\n输入: ").append(input)
            append("\n如果失败原因是权限或环境限制，请先说明并给出替代方案。")
        }
        startAgentRun(retryPrompt)
    }

    private fun copyMessageToClipboard(messageId: String) {
        if (messageId.isBlank()) return
        val message = chatService.messages.firstOrNull { it.id == messageId } ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(message.content))
        setStatusMessage("Copied")
    }

    private fun openFileInEditor(path: String) {
        if (path.isBlank()) return
        val file = resolveVirtualFile(path) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun retryCommand(command: String, cwd: String) {
        if (command.isBlank() || isRunning) return
        val retryPrompt = buildString {
            append("请重试这条命令，并先说明风险再执行：\n")
            append("命令: ").append(command)
            if (cwd.isNotBlank()) append("\n工作目录: ").append(cwd)
        }
        startAgentRun(retryPrompt)
    }

    private fun copyCommandToClipboard(command: String) {
        if (command.isBlank()) return
        CopyPasteManager.getInstance().setContents(StringSelection(command))
        setStatusMessage("Command copied")
    }

    private fun cancelRequest() {
        chatService.cancelCurrent()
        finishTurn("Cancelled", persistIfNeeded = true)
    }

    private fun handleTimelineAction(action: TimelineAction) {
        if (!turnActive && action !is TimelineAction.MarkTurnFailed) {
            return
        }
        if (turnFinalized && action != TimelineAction.FinishTurn && action !is TimelineAction.MarkTurnFailed) {
            return
        }
        when (action) {
            is TimelineAction.AppendNarrative,
            is TimelineAction.AppendThinking,
            is TimelineAction.UpsertTool,
            is TimelineAction.UpsertCommand,
            is TimelineAction.MarkTurnFailed,
            is TimelineAction.CommandProposalReceived,
            -> {
                recordTimelineAction(action)
            }

            is TimelineAction.DiffProposalReceived -> handleDiffProposal(action)
            TimelineAction.FinishTurn -> {
                turnFinalized = true
                persistTimelineMessageIfNeeded()
                finishTurn("Done", persistIfNeeded = true)
            }
        }
    }

    private fun handleDiffProposal(action: TimelineAction.DiffProposalReceived) {
        if (!currentCapabilities().supportsDiffProposal) {
            return
        }
        val vFile = resolveVirtualFile(action.filePath)
        if (vFile == null) {
            chatService.addMessage(ChatMessage(role = MessageRole.SYSTEM, content = "Diff target not found: ${action.filePath}"))
            refreshMessages()
            return
        }

        val document = FileDocumentManager.getInstance().getDocument(vFile)
        if (document == null) {
            chatService.addMessage(ChatMessage(role = MessageRole.SYSTEM, content = "Cannot open target file: ${action.filePath}"))
            refreshMessages()
            return
        }

        if (approvalUiPolicy.shouldApplyDiffProposal()) {
            ApplicationManager.getApplication().runWriteAction {
                document.setText(action.newContent)
                FileDocumentManager.getInstance().saveDocument(document)
            }
            chatService.addMessage(ChatMessage(role = MessageRole.SYSTEM, content = "Applied diff to ${vFile.path}"))
            refreshMessages()
        }
    }

    private fun recordTimelineAction(action: TimelineAction) {
        currentTimelineActionsBuffer.add(action)
        renderPreviewBuffer()
    }

    private fun persistTimelineMessageIfNeeded() {
        val actions = currentTimelineActionsBuffer.toList()
        if (actions.isEmpty()) {
            return
        }
        val assistantContent = buildAssistantContentFromTimelineActions(actions)
        val hasRenderableContent = assistantContent.isNotBlank() ||
            actions.any {
                it is TimelineAction.UpsertTool ||
                    it is TimelineAction.UpsertCommand ||
                    it is TimelineAction.AppendThinking ||
                    it is TimelineAction.MarkTurnFailed
            }
        if (!hasRenderableContent) {
            return
        }
        chatService.addMessage(
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = assistantContent,
                timelineActionsPayload = TimelineActionCodec.encode(actions),
            ),
        )
        refreshMessages()
    }

    private fun buildAssistantContentFromTimelineActions(actions: List<TimelineAction>): String {
        val narratives = actions
            .filterIsInstance<TimelineAction.AppendNarrative>()
            .sortedBy { it.sequence }
            .map { it.text.trim() }
            .filter { it.isNotBlank() }
        if (narratives.isNotEmpty()) {
            return narratives.joinToString("\n\n")
        }

        val failures = actions
            .filterIsInstance<TimelineAction.MarkTurnFailed>()
            .map { it.message.trim() }
            .filter { it.isNotBlank() }
        if (failures.isNotEmpty()) {
            return failures.joinToString("\n\n")
        }

        return when {
            actions.any { it is TimelineAction.UpsertCommand } -> "Execution trace recorded."
            actions.any { it is TimelineAction.UpsertTool } -> "Tool trace recorded."
            else -> ""
        }
    }

    private fun refreshMessages() {
        refreshHeaderState()
        refreshUsageSnapshotLabel()
        renderMessages(streamingText = null, forceAutoScroll = false)
        showMessagesCardIfNeeded()
        refreshNewButtonState()
        if (workspaceState.currentView == ToolWindowView.SESSION_HISTORY) {
            sessionHistoryView.updateSessions(chatService.listSessions(), chatService.getCurrentSessionId())
        }
    }

    private fun refreshHeaderState() {
        if (workspaceState.currentView == ToolWindowView.CONSOLE) {
            sessionTitleLabel.text = chatService.currentSessionTitle()
            sessionSubtitleLabel.text = if (chatService.isCurrentSessionEmpty()) {
                "Task Console"
            } else {
                "Structured execution workspace"
            }
        } else {
            sessionTitleLabel.text = workspaceState.currentTitle
            sessionSubtitleLabel.text = "Inside Codex Assistant"
        }
        backButton.isVisible = workspaceState.showBackAction
        runContextBar.isVisible = workspaceState.showRunContextBar
        composerContainer.isVisible = workspaceState.showComposerDock
        historyButton.isEnabled = workspaceState.currentView != ToolWindowView.SESSION_HISTORY
        settingsButton.isEnabled = workspaceState.currentView != ToolWindowView.SETTINGS
        attachButton.isEnabled = workspaceState.currentView == ToolWindowView.CONSOLE
    }

    private fun showWorkspace(view: ToolWindowView) {
        when (view) {
            ToolWindowView.CONSOLE -> workspaceState.navigateBack()
            ToolWindowView.SESSION_HISTORY -> {
                sessionHistoryView.updateSessions(chatService.listSessions(), chatService.getCurrentSessionId())
                workspaceState.navigateTo(view)
            }
            ToolWindowView.SETTINGS -> {
                settingsWorkspaceView.reload()
                workspaceState.navigateTo(view)
            }
            ToolWindowView.CONTEXT_FILES -> {
                contextFilesView.replaceFiles(attachedFiles)
                workspaceState.navigateTo(view)
            }
        }
        val layout = workspaceCards.layout as CardLayout
        layout.show(
            workspaceCards,
            when (workspaceState.currentView) {
                ToolWindowView.CONSOLE -> WORKSPACE_CONSOLE
                ToolWindowView.SESSION_HISTORY -> WORKSPACE_HISTORY
                ToolWindowView.SETTINGS -> WORKSPACE_SETTINGS
                ToolWindowView.CONTEXT_FILES -> WORKSPACE_CONTEXT
            },
        )
        refreshHeaderState()
    }

    private fun updateAttachedFiles(files: Set<String>) {
        attachedFiles.clear()
        attachedFiles.addAll(files)
        refreshChipLabels()
    }

    private fun chooseContextFile(): String? {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return null
        return file.path
    }

    private fun openSessionFromWorkspace(sessionId: String) {
        if (!chatService.switchSession(sessionId)) {
            return
        }
        resetConversationUi()
        refreshMessages()
        showWorkspace(ToolWindowView.CONSOLE)
    }

    private fun createSessionFromWorkspace() {
        chatService.createSession()
        resetConversationUi()
        refreshMessages()
        showWorkspace(ToolWindowView.CONSOLE)
    }

    private fun deleteSessionFromWorkspace(sessionId: String) {
        if (!chatService.deleteSession(sessionId)) {
            return
        }
        resetConversationUi()
        refreshMessages()
        sessionHistoryView.updateSessions(chatService.listSessions(), chatService.getCurrentSessionId())
    }

    private fun resetConversationUi() {
        currentAssistantContentBuffer = StringBuilder()
        currentThinkingBuffer = StringBuilder()
        currentToolEventsBuffer.clear()
        currentCommandEventsBuffer.clear()
        currentNarrativeEventsBuffer.clear()
        activeContentNarrativeId = null
        currentFlowSequence = 0
        expandedToolMessageIds.clear()
        expandedThinkingMessageIds.clear()
        expandedCommandMessageIds.clear()
        currentStatusText = ""
        requestStartedAtMs = 0L
        loadingTimer.stop()
        previewRenderTimer.stop()
        streamLabel.icon = null
        setStatusMessage("Ready")
        setRunningState(false)
    }

    private fun refreshUsageSnapshotLabel() {
        val snapshot = chatService.currentUsageSnapshot()
        usageLeftLabel.text = snapshot?.headerLabel() ?: "--"
        usageLeftLabel.toolTipText = snapshot?.tooltipText()
    }

    private fun renderPreviewBuffer() {
        if (!previewRenderTimer.isRunning) {
            previewRenderTimer.start()
        } else {
            previewRenderTimer.restart()
        }
    }

    private fun renderPreviewBufferNow() {
        renderMessages(streamingText = null, forceAutoScroll = false)
        showMessagesCard(forceMessages = true)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun renderMessages(streamingText: String?, forceAutoScroll: Boolean) {
        val liveTurn = buildLiveTurnSnapshot()
        val shouldAutoScroll = forceAutoScroll || isNearBottom()
        val turns = timelineBuilder.build(chatService.messages, liveTurn)
        timelinePanel.updateTurns(turns, shouldAutoScroll)
    }

    private fun isNearBottom(thresholdPx: Int = 24): Boolean {
        return timelinePanel.isNearBottom(thresholdPx)
    }

    private fun scrollToBottom() {
        timelinePanel.scrollToBottom()
    }

    private fun showMessagesCardIfNeeded() {
        if (chatService.messages.isEmpty() &&
            currentTimelineActionsBuffer.isEmpty() &&
            currentAssistantContentBuffer.isBlank() &&
            currentThinkingBuffer.isBlank() &&
            currentToolEventsBuffer.isEmpty() &&
            currentCommandEventsBuffer.isEmpty()
        ) {
            showMessagesCard(forceMessages = false)
        } else {
            showMessagesCard(forceMessages = true)
        }
    }

    private fun showMessagesCard(forceMessages: Boolean) {
        val layout = centerCards.layout as CardLayout
        if (forceMessages) {
            layout.show(centerCards, CARD_MESSAGES)
        } else {
            layout.show(centerCards, CARD_PLACEHOLDER)
        }
    }

    private fun refreshChipLabels() {
        val sdkName = chatService.engineDescriptor(selectedEngineId)?.displayName ?: "Codex"
        sdkButton.text = "${ToolWindowUiText.selectionChipLabel(sdkName)} \u25BE"
        sdkButton.toolTipText = "Switch provider"
        modelChip.text = "${ToolWindowUiText.selectionChipLabel(selectedModel)} \u25BE"
        modeChip.text = ToolWindowUiText.selectionChipLabel(approvalUiPolicy.chipLabel)
        modeChip.isEnabled = approvalUiPolicy.isInteractive
        modeChip.toolTipText = "Approval flow will be redesigned later"
        syncEditorContextIndicator()
        val editorContextVisible = editorContextPanel.isVisible
        attachStatusLabel.text = when {
            attachedFiles.isNotEmpty() && editorContextVisible -> "${attachedFiles.size} attached · current file included"
            attachedFiles.isNotEmpty() -> "${attachedFiles.size} file(s) attached"
            editorContextVisible -> "Current file included"
            else -> "Attach files or use the current editor context"
        }
        contextFilesView.replaceFiles(attachedFiles)
    }

    private fun styleChip(button: JButton) {
        AssistantUiTheme.toolbarChip(button)
    }

    private fun styleAttachButton(button: JButton) {
        button.toolTipText = "Manage attached files inside the workspace"
        AssistantUiTheme.toolbarChip(button)
    }

    private fun styleHeaderButton(button: JButton) {
        AssistantUiTheme.toolbarButton(button)
    }

    private fun styleSettingsButton(button: JButton) {
        button.horizontalAlignment = SwingConstants.CENTER
        button.toolTipText = "Open workspace settings"
        AssistantUiTheme.toolbarButton(button)
    }

    private fun styleEditorContextPanel(panel: JPanel, label: JLabel, closeButton: JButton) {
        panel.isOpaque = true
        panel.background = AssistantUiTheme.SURFACE_SUBTLE
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(AssistantUiTheme.BORDER_SUBTLE, 1, true),
            BorderFactory.createEmptyBorder(2, 7, 2, 7),
        )
        label.foreground = AssistantUiTheme.TEXT_PRIMARY
        closeButton.isFocusable = false
        closeButton.border = BorderFactory.createEmptyBorder(0, 2, 0, 0)
        closeButton.background = panel.background
        closeButton.foreground = AssistantUiTheme.TEXT_SECONDARY
        closeButton.toolTipText = "Remove current file context"
        panel.add(label)
        panel.add(closeButton)
    }

    private fun styleSdkButton(button: JButton) {
        button.toolTipText = "Switch provider"
        AssistantUiTheme.toolbarChip(button)
    }

    private fun stylePrimaryActionButton(button: JButton) {
        AssistantUiTheme.compactPrimaryButton(button)
    }

    private fun styleStatusLabel(label: JLabel) {
        label.foreground = AssistantUiTheme.TEXT_SECONDARY
        label.font = label.font.deriveFont(11.5f)
        label.iconTextGap = 6
    }

    private fun setRunningState(running: Boolean) {
        isRunning = running
        actionButton.text = if (running) "Stop" else "Run"
        actionButton.toolTipText = if (running) "Stop the active run" else "Run the task"
        if (running) {
            refreshRunningStatusLabel()
        } else if (streamLabel.text.isBlank()) {
            setStatusMessage("Ready")
        }
    }

    private fun refreshRunningStatusLabel() {
        if (!isRunning) return
        val elapsedMs = requestStartedAtMs.takeIf { it > 0L }?.let { System.currentTimeMillis() - it } ?: 0L
        streamLabel.icon = AnimatedIcon.Default()
        streamLabel.text = ToolWindowUiText.runningStatus(elapsedMs)
        streamLabel.toolTipText = "Current run is still in progress"
    }

    private fun setStatusMessage(message: String) {
        streamLabel.icon = null
        streamLabel.text = message
        streamLabel.toolTipText = message
    }

    private fun onPrimaryAction() {
        if (isRunning) {
            cancelRequest()
        } else {
            submitPrompt()
        }
    }

    private fun showSdkMenu(anchor: JComponent) {
        val menu = JPopupMenu()
        chatService.availableEngines().forEach { descriptor ->
            menu.add(JMenuItem(descriptor.displayName).apply {
                addActionListener {
                    selectedEngineId = descriptor.id
                    val models = availableModels(selectedEngineId)
                    if (!models.contains(selectedModel)) {
                        selectedModel = models.firstOrNull() ?: CodexModelCatalog.defaultModel
                    }
                    refreshChipLabels()
                }
            })
        }
        menu.show(anchor, 0, anchor.height)
    }

    private fun showModelMenu(anchor: JComponent) {
        val menu = JPopupMenu().apply {
            isOpaque = true
            background = JBColor(0x161C24, 0x161C24)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(0x26384F, 0x26384F), 1, true),
                BorderFactory.createEmptyBorder(4, 4, 4, 4),
            )
        }
        availableModels(selectedEngineId).forEach { model ->
            val option = CodexModelCatalog.option(model)
            menu.add(
                createModelMenuItem(
                    model = model,
                    description = option?.description ?: "可用模型",
                    isSelected = model == selectedModel,
                ) {
                    selectedModel = model
                    refreshChipLabels()
                    menu.isVisible = false
                },
            )
        }
        menu.show(anchor, 0, anchor.height)
    }

    private fun availableModels(engineId: String): List<String> {
        return chatService.engineDescriptor(engineId)?.models?.takeIf { it.isNotEmpty() } ?: listOf(CodexModelCatalog.defaultModel)
    }

    private fun createModelMenuItem(
        model: String,
        description: String,
        isSelected: Boolean,
        onSelect: () -> Unit,
    ): JComponent {
        val normalBackground = JBColor(0x161C24, 0x161C24)
        val hoverBackground = JBColor(0x1D2631, 0x1D2631)
        val selectedBackground = JBColor(0x154E7D, 0x154E7D)
        val normalTitle = JBColor(0xE3ECF8, 0xE3ECF8)
        val normalDescription = JBColor(0x7F93AD, 0x7F93AD)
        val selectedTitle = JBColor(0xF4F8FF, 0xF4F8FF)
        val selectedDescription = JBColor(0xC5D8EE, 0xC5D8EE)

        val iconLabel = JLabel("\u25C9").apply {
            foreground = if (isSelected) selectedTitle else JBColor(0xA8BDD6, 0xA8BDD6)
            border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
            font = font.deriveFont(13f)
        }
        val titleLabel = JLabel(model).apply {
            foreground = if (isSelected) selectedTitle else normalTitle
            font = font.deriveFont(Font.BOLD, 14f)
        }
        val descriptionLabel = JLabel(description).apply {
            foreground = if (isSelected) selectedDescription else normalDescription
            font = font.deriveFont(11f)
        }

        val textPanel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            isOpaque = false
            add(titleLabel)
            add(descriptionLabel)
        }

        lateinit var container: JPanel
        val mouseListener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                if (!isSelected) {
                    container.background = hoverBackground
                }
            }

            override fun mouseExited(e: MouseEvent?) {
                if (!isSelected) {
                    container.background = normalBackground
                }
            }

            override fun mousePressed(e: MouseEvent?) {
                onSelect()
            }
        }

        container = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = if (isSelected) selectedBackground else normalBackground
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    if (isSelected) JBColor(0x317DBA, 0x317DBA) else JBColor(0x202B39, 0x202B39),
                    1,
                    true,
                ),
                BorderFactory.createEmptyBorder(8, 10, 8, 10),
            )
            preferredSize = Dimension(248, 52)
            maximumSize = Dimension(248, 52)
            add(iconLabel, BorderLayout.WEST)
            add(textPanel, BorderLayout.CENTER)
        }

        listOf(container, iconLabel, titleLabel, descriptionLabel, textPanel).forEach {
            it.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            it.addMouseListener(mouseListener)
        }
        return container
    }

    private fun currentCapabilities(): EngineCapabilities {
        return chatService.engineDescriptor(selectedEngineId)?.capabilities ?: EngineCapabilities(
            supportsThinking = true,
            supportsToolEvents = true,
            supportsCommandProposal = false,
            supportsDiffProposal = false,
        )
    }

    private fun buildChatHtml(messages: List<ChatMessage>, streamingText: String?): String {
        val items = renderMessageItems(messages)
        val streaming = renderStreamingState(streamingText)

        return """
            <html>
              <head>
                <style>
                  body { font-family: -apple-system, Segoe UI, sans-serif; background:#0C1118; color:#DFE7F2; margin:0; padding:12px 10px 14px 10px; }
                  a { color:#8FBCFF; text-decoration:none; }
                  a:hover { text-decoration:underline; }
                  .meta { font-size:12px; color:#8E9AAF; margin-bottom:6px; }
                  .card { border:1px solid #253244; border-radius:12px; background:#131A24; }
                  .section-card { margin:8px 0 10px 0; border:1px solid #26384F; border-radius:10px; background:#101823; padding:9px 11px; }
                  .section-title { font-size:11px; color:#9AB1CE; margin-bottom:7px; letter-spacing:.2px; }
                  .step-line { position:relative; margin:0 0 8px 0; padding-left:18px; border-left:1px solid #2A3C56; }
                  .step-dot { position:absolute; left:-4px; top:4px; display:inline-block; width:8px; height:8px; border-radius:50%; }
                  .action-card { margin:8px 0; border:1px solid #2C3C52; background:#131C29; border-radius:10px; padding:10px 12px; }
                  .btn-link { font-size:11px; color:#9DC1FF; border:1px solid #3A4D70; background:#1F2B3D; border-radius:8px; padding:2px 8px; text-decoration:none; margin-right:8px; }
                  .turn-row { display:flex; align-items:stretch; gap:8px; margin:4px 0 10px 0; }
                  .timeline-col { width:14px; position:relative; }
                  .timeline-dot { width:8px; height:8px; border-radius:50%; margin:8px 0 0 2px; position:relative; z-index:2; }
                  .timeline-line { position:absolute; left:5px; top:0; bottom:-8px; width:1px; background:#2A3B55; z-index:1; }
                  .turn-main { flex:1; min-width:0; }
                  .turn-summary { border:1px solid #2D3E56; background:#121B28; border-radius:10px; padding:10px 12px; }
                  .turn-summary-title { display:flex; justify-content:space-between; align-items:center; color:#D9E4F4; font-size:13px; }
                  .turn-summary-meta { margin-top:6px; font-size:12px; color:#AFC1D8; line-height:1.45; }
                </style>
              </head>
              <body>
                $items
                $streaming
              </body>
            </html>
        """.trimIndent()
    }

    private fun renderMessageItems(messages: List<ChatMessage>): String {
        val lastMessageId = messages.lastOrNull()?.id.orEmpty()
        val cacheValid = cachedMessageCount == messages.size &&
            cachedLastMessageId == lastMessageId &&
            cachedToolDetailMode == expandAllTools
        if (cacheValid) {
            return cachedMessagesHtml
        }

        val turns = buildTurnViewModels(messages)
        cachedMessagesHtml = turns.mapIndexed { index, turn ->
            renderTurnRow(turn, isLast = index == turns.lastIndex)
        }.joinToString("")
        cachedMessageCount = messages.size
        cachedLastMessageId = lastMessageId
        cachedToolDetailMode = expandAllTools
        return cachedMessagesHtml
    }

    private fun buildTurnViewModels(messages: List<ChatMessage>): List<TurnViewModel> {
        if (messages.isEmpty()) return emptyList()
        val result = mutableListOf<TurnViewModel>()
        var currentUser: ChatMessage? = null
        var currentAssistant: ChatMessage? = null
        val currentSystems = mutableListOf<ChatMessage>()
        var currentTurnId = ""

        fun flush() {
            if (currentUser == null && currentAssistant == null && currentSystems.isEmpty()) return
            val idSource = currentAssistant?.id ?: currentUser?.id ?: "turn-${result.size + 1}"
            result.add(
                TurnViewModel(
                    id = "turn-$idSource",
                    userMessage = currentUser,
                    assistantMessage = currentAssistant,
                    systemMessages = currentSystems.toList(),
                ),
            )
            currentUser = null
            currentAssistant = null
            currentSystems.clear()
            currentTurnId = ""
        }

        messages.forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> {
                    flush()
                    currentUser = msg
                    currentTurnId = "turn-${msg.id}"
                }
                MessageRole.ASSISTANT -> {
                    if (currentUser == null && currentSystems.isNotEmpty() && currentAssistant == null) {
                        currentAssistant = msg
                    } else if (currentAssistant == null) {
                        currentAssistant = msg
                    } else {
                        flush()
                        currentAssistant = msg
                        currentTurnId = "turn-${msg.id}"
                    }
                }
                MessageRole.SYSTEM -> {
                    if (currentUser == null && currentAssistant == null && currentTurnId.isBlank()) {
                        currentTurnId = "turn-${msg.id}"
                    }
                    currentSystems.add(msg)
                }
            }
        }
        flush()
        return result
    }

    private fun renderTurnRow(turn: TurnViewModel, isLast: Boolean): String {
        val dotColor = when {
            turn.systemMessages.any { it.content.contains("Error:", ignoreCase = true) || it.content.contains("失败") } -> "#E86D6D"
            turn.assistantMessage != null -> "#71D37C"
            else -> "#4D6D98"
        }
        val lineHtml = if (isLast) "" else """<div class="timeline-line"></div>"""
        val userHtml = turn.userMessage?.let {
            val t = Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).format(timeFormatter)
            renderUserBubble(it, t)
        }.orEmpty()
        val assistantExpanded = expandedTurnIds.contains(turn.id)
        val turnToggleHtml = if (turn.assistantMessage != null && assistantExpanded) {
            val toggleHref = buildToggleTurnHref(turn.id)
            """<div style="text-align:right; margin:2px 0 4px 0;"><a class="btn-link" href="$toggleHref">收起</a></div>"""
        } else ""
        val assistantHtml = turn.assistantMessage?.let { assistant ->
            val expanded = expandedTurnIds.contains(turn.id)
            if (expanded) {
                val t = Instant.ofEpochMilli(assistant.timestamp).atZone(ZoneId.systemDefault()).format(timeFormatter)
                renderAgentCard(assistant, t, showLegacyToggles = false)
            } else {
                renderCollapsedTurnCard(turn)
            }
        }.orEmpty()
        val showSystems = expandedTurnIds.contains(turn.id) || turn.assistantMessage == null
        val systemHtml = if (showSystems) {
            turn.systemMessages.joinToString("") { msg ->
                val t = Instant.ofEpochMilli(msg.timestamp).atZone(ZoneId.systemDefault()).format(timeFormatter)
                renderSystemEventCard(msg, t)
            }
        } else ""

        return """
            <div class="turn-row">
              <div class="timeline-col">
                <div class="timeline-dot" style="background:$dotColor;"></div>
                $lineHtml
              </div>
              <div class="turn-main">
                $userHtml
                $turnToggleHtml
                $assistantHtml
                $systemHtml
              </div>
            </div>
        """.trimIndent()
    }

    private fun renderCollapsedTurnCard(turn: TurnViewModel): String {
        val assistant = turn.assistantMessage ?: return ""
        val sections = extractSections(assistant.content)
        val response = sections["response"].orEmpty()
        val tools = parseToolLines(sections["tools"].orEmpty())
        val commands = parseCommandLines(sections["commands"].orEmpty())
        val fallback = if (sections.isEmpty()) assistant.content else ""
        val responseText = if (response.isNotBlank()) response else fallback
        val effectiveTools = if (tools.isNotEmpty()) tools else inferToolLinesFromResponse(responseText)
        val effectiveCommands = if (commands.isNotEmpty()) commands else inferCommandLinesFromResponse(responseText)
        val changedFiles = extractChangedFiles(effectiveTools)

        val plus = changedFiles.mapNotNull { it.delta.takeIf { d -> d.startsWith("+") }?.removePrefix("+")?.toIntOrNull() }.sum()
        val minus = changedFiles.mapNotNull { it.delta.takeIf { d -> d.startsWith("-") }?.removePrefix("-")?.toIntOrNull() }.sum()
        val title = when {
            changedFiles.isNotEmpty() -> "编辑文件 (${changedFiles.size})"
            effectiveCommands.isNotEmpty() -> "执行命令 (${effectiveCommands.size})"
            else -> "任务结果"
        }
        val summary = responseText
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(120)
            ?: "已完成一次任务处理。"
        val deltaText = buildString {
            if (plus > 0) append(" +").append(plus)
            if (minus > 0) {
                if (isNotBlank()) append(" ")
                append("-").append(minus)
            }
        }.ifBlank { " 无文件增减" }
        val toggleHref = buildToggleTurnHref(turn.id)

        return """
            <div style="margin: 8px 0 14px 0;">
              <div class="turn-summary">
                <div class="turn-summary-title">
                  <span>$title<span style="color:#8FA5C1; margin-left:8px;">$deltaText</span></span>
                  <a class="btn-link" href="$toggleHref">展开</a>
                </div>
                <div class="turn-summary-meta">${escapeHtml(summary)}</div>
              </div>
            </div>
        """.trimIndent()
    }

    private fun renderUserBubble(msg: ChatMessage, timeText: String): String {
        val copyHref = buildCopyHref(msg.id)
        return """
            <div style="margin: 10px 0 12px 0; text-align:right;">
              <div style="font-size:12px; color:#9EA8B8; margin-bottom:6px;">$timeText <a href="$copyHref" style="color:#9EA8B8; text-decoration:none; margin-left:6px;">⧉</a></div>
              <div style="display:inline-block; max-width:80%; background:#2F68C5; color:#F2F7FF; border-radius:14px; padding:12px 14px; box-shadow:0 1px 0 rgba(0,0,0,.25);">
                <div style="font-size:15px; line-height:1.45; text-align:left;">${renderStructuredContent(msg.content)}</div>
              </div>
            </div>
        """.trimIndent()
    }

    private fun renderAgentCard(msg: ChatMessage, timeText: String, showLegacyToggles: Boolean = true): String {
        val sections = extractSections(msg.content)
        val response = sections["response"].orEmpty()
        val thinking = sections["thinking"].orEmpty()
        val tools = parseToolLines(sections["tools"].orEmpty())
        val commands = parseCommandLines(sections["commands"].orEmpty())
        val fallback = if (sections.isEmpty()) msg.content else ""
        val responseText = if (response.isNotBlank()) response else fallback

        val thinkingExpanded = expandedThinkingMessageIds.contains(msg.id)
        val thinkingToggle = buildToggleHref("toggle-thinking", msg.id)
        val toolsExpanded = expandAllTools || expandedToolMessageIds.contains(msg.id)
        val toolsToggle = buildToggleHref("toggle-tools", msg.id)
        val commandsExpanded = expandedCommandMessageIds.contains(msg.id)
        val commandsToggle = buildToggleHref("toggle-commands", msg.id)

        val actionBarHtml = if (!showLegacyToggles) "" else """
            <div style="margin:0 0 8px 0; font-size:11px; color:#8FB6F4; line-height:1.4;">
              <a href="$toolsToggle" style="color:#8FB6F4;">${if (toolsExpanded) "收起工具详情" else "展开工具详情"}</a>
              <span style="color:#4E607A; margin:0 6px;">|</span>
              <a href="$commandsToggle" style="color:#8FB6F4;">${if (commandsExpanded) "收起命令详情" else "展开命令详情"}</a>
              <span style="color:#4E607A; margin:0 6px;">|</span>
              <a href="$thinkingToggle" style="color:#8FB6F4;">${if (thinkingExpanded) "收起思考" else "展开思考"}</a>
            </div>
        """.trimIndent()

        val effectiveTools = if (tools.isNotEmpty()) tools else inferToolLinesFromResponse(responseText)
        val effectiveCommands = if (commands.isNotEmpty()) commands else inferCommandLinesFromResponse(responseText)
        val toolRowItems = effectiveTools.mapIndexed { index, line ->
            val meta = parseToolMeta(line)
            FlowStepRow(
                sequence = meta.sequence,
                startedAtMs = meta.startedAtMs,
                html = renderToolStepRow(index + 1, line, toolsExpanded),
                order = index,
            )
        }
        val commandRowItems = effectiveCommands.mapIndexed { index, line ->
            val meta = parseCommandMeta(line)
            FlowStepRow(
                sequence = meta.sequence,
                startedAtMs = meta.startedAtMs,
                html = renderCommandStepRow(index + 1, line, commandsExpanded),
                order = 1_000 + index,
            )
        }
        val mergedRows = (toolRowItems + commandRowItems)
            .sortedWith(
                compareBy<FlowStepRow> { it.sequence ?: Int.MAX_VALUE }
                    .thenBy { it.startedAtMs ?: Long.MAX_VALUE }
                    .thenBy { it.order },
            )
            .joinToString("") { it.html }
        val processHtml = if (mergedRows.isBlank()) "" else """
            <div class="section-card">
              <div class="section-title">执行流程</div>
              $mergedRows
            </div>
        """.trimIndent()

        val summaryHtml = if (responseText.isBlank()) "" else """
            <div class="section-card">
              <div class="section-title">结果摘要</div>
              <div style="font-size:14px; color:#D8E1EE; line-height:1.5;">${renderStructuredContent(responseText)}</div>
            </div>
        """.trimIndent()

        val changedFiles = extractChangedFiles(effectiveTools)
        val changedFileCards = if (changedFiles.isEmpty()) "" else {
            val cards = changedFiles.joinToString("") { file ->
                val openHref = buildOpenFileHref(file.path)
                val retryHref = buildRetryHref("edit_file", file.path)
                val diffHref = toolsToggle
                val delta = if (file.delta.isBlank()) "" else " ${escapeHtml(file.delta)}"
                """
                <div class="action-card">
                  <div style="display:flex; justify-content:space-between; align-items:center;">
                    <div style="font-size:13px; color:#D8E3F3;">编辑文件 · <strong>${escapeHtml(file.name)}</strong>$delta</div>
                    <span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:#71D37C;"></span>
                  </div>
                  <div style="margin-top:5px; font-size:11px; color:#8FA5C1;">${escapeHtml(file.path)}</div>
                  <div style="margin-top:7px;">
                    <a class="btn-link" href="$diffHref">Diff</a>
                    <a class="btn-link" href="$retryHref">重试</a>
                    <a class="btn-link" href="$openHref">打开文件</a>
                  </div>
                </div>
                """.trimIndent()
            }
            """
            <div class="section-card">
              <div class="section-title">修改文件</div>
              $cards
            </div>
            """.trimIndent()
        }

        val commandCards = if (effectiveCommands.isEmpty()) "" else {
            val cards = effectiveCommands.mapIndexed { index, line ->
                renderCommandCard(line, commandsExpanded, index)
            }.joinToString("")
            """
            <div class="section-card">
              <div class="section-title">命令执行</div>
              $cards
            </div>
            """.trimIndent()
        }

        val thinkingHtml = if (thinking.isBlank()) {
            ""
        } else if (thinkingExpanded) {
            """
            <div class="section-card">
              <div class="section-title">思考过程 <a href="$thinkingToggle" style="margin-left:6px; color:#8DAEDB;">收起</a></div>
              <div style="font-size:12px; color:#D6DDE8; line-height:1.4;">${renderStructuredContent(thinking)}</div>
            </div>
            """.trimIndent()
        } else {
            """<div style="margin:6px 0 0 0; font-size:11px; color:#93A6BF;">思考过程已折叠 <a href="$thinkingToggle" style="margin-left:6px; color:#8DAEDB;">展开</a></div>"""
        }

        val copyHref = buildCopyHref(msg.id)

        return """
            <div style="margin: 8px 0 14px 0;">
              <div style="font-size:12px; color:#9EA8B8; margin-bottom:6px;">${escapeHtml(msg.role.label())} $timeText <a href="$copyHref" style="color:#9EA8B8; text-decoration:none; margin-left:6px;">⧉</a></div>
              <div style="max-width:94%; padding:10px 12px;" class="card">
                $actionBarHtml
                $processHtml
                $summaryHtml
                $changedFileCards
                $commandCards
                $thinkingHtml
              </div>
            </div>
        """.trimIndent()
    }

    private fun renderToolStepRow(index: Int, toolLine: String, expanded: Boolean): String {
        val meta = parseToolMeta(toolLine)
        val statusLabel = when (meta.status) {
            "done" -> "完成"
            "failed" -> "失败"
            else -> "执行中"
        }
        val dot = when (meta.status) {
            "done" -> "#71D37C"
            "failed" -> "#E86D6D"
            else -> "#E2C06B"
        }
        val timing = buildString {
            meta.startedAtMs?.let { append(formatClockTime(it)) }
            meta.durationMs?.let {
                if (isNotBlank()) append(" · ")
                append(formatDuration(it))
            }
        }
        val details = if (!expanded) "" else {
            toolLine.split(" | ")
                .filter { it.startsWith("in: ") || it.startsWith("out: ") }
                .joinToString(" · ")
        }
        return """
            <div class="step-line">
              <span class="step-dot" style="background:$dot;"></span>
              <div style="font-size:12px; color:#C8D6EA;">工具 $index · ${escapeHtml(meta.name)} · $statusLabel</div>
              ${if (timing.isBlank()) "" else """<div style="font-size:10px; color:#8EA4C2; margin-top:2px;">$timing</div>"""}
              ${if (details.isBlank()) "" else """<div style="font-size:11px; color:#9AAFC8; margin-top:3px;">${escapeHtml(details)}</div>"""}
            </div>
        """.trimIndent()
    }

    private fun renderCommandStepRow(index: Int, commandLine: String, expanded: Boolean): String {
        val meta = parseCommandMeta(commandLine)
        val status = meta.status.lowercase()
        val statusLabel = when (status) {
            "done" -> "已完成"
            "failed" -> "失败"
            "running" -> "执行中"
            "skipped" -> "已跳过"
            else -> "待确认"
        }
        val dot = when (status) {
            "done" -> "#71D37C"
            "failed" -> "#E86D6D"
            "running" -> "#E2C06B"
            "skipped" -> "#8E9AAF"
            else -> "#B5C3D8"
        }
        val timing = buildString {
            meta.startedAtMs?.let { append(formatClockTime(it)) }
            meta.durationMs?.let {
                if (isNotBlank()) append(" · ")
                append(formatDuration(it))
            }
        }
        val detailHtml = if (!expanded) {
            ""
        } else {
            buildString {
                if (meta.cwd.isNotBlank()) append("""<div style="font-size:11px; color:#9AAFC8; margin-top:3px;">目录: ${escapeHtml(meta.cwd)}</div>""")
                meta.exitCode?.let { code ->
                    val c = if (code == 0) "#71D37C" else "#E86D6D"
                    append("""<div style="font-size:11px; color:$c; margin-top:3px;">exit code: $code</div>""")
                }
                if (meta.output.isNotBlank()) append("""<div style="font-size:11px; color:#9AAFC8; margin-top:3px;">输出: ${escapeHtml(meta.output)}</div>""")
                if (meta.command.isNotBlank()) {
                    val copyHref = buildCopyCommandHref(meta.command)
                    val retryHref = buildRetryCommandHref(meta.command, meta.cwd)
                    append("""<div style="margin-top:4px;"><a href="$copyHref" style="font-size:11px; color:#8FB6F4; margin-right:8px;">复制命令</a><a href="$retryHref" style="font-size:11px; color:#8FB6F4;">重试</a></div>""")
                }
            }
        }
        return """
            <div class="step-line">
              <span class="step-dot" style="background:$dot;"></span>
              <div style="font-size:12px; color:#C8D6EA;">命令 $index · $statusLabel · ${escapeHtml(meta.command.ifBlank { "待确认命令" }.take(80))}</div>
              ${if (timing.isBlank()) "" else """<div style="font-size:10px; color:#8EA4C2; margin-top:2px;">$timing</div>"""}
              $detailHtml
            </div>
        """.trimIndent()
    }

    private fun renderSystemEventCard(msg: ChatMessage, timeText: String): String {
        val copyHref = buildCopyHref(msg.id)
        val (riskLabel, riskColor) = systemRiskTag(msg.content)
        val riskChip = if (riskLabel.isBlank()) "" else {
            """<span style="display:inline-block; margin-right:8px; font-size:11px; border-radius:999px; padding:1px 8px; color:$riskColor; border:1px solid $riskColor;">$riskLabel</span>"""
        }
        return """
            <div style="margin: 8px 0 12px 0;">
              <div class="meta">${riskChip}系统消息 $timeText <a href="$copyHref" style="color:#9EA8B8; text-decoration:none; margin-left:6px;">⧉</a></div>
              <div style="max-width:94%; border:1px dashed #33465E; background:#0F151F; border-radius:10px; padding:8px 10px;">
                <div style="font-size:12px; color:#AFC0D8; line-height:1.45;">${renderStructuredContent(msg.content)}</div>
              </div>
            </div>
        """.trimIndent()
    }

    private fun renderToolSummaryBar(
        toolLine: String,
        toolsExpanded: Boolean,
        toolsToggleHref: String,
        canExpand: Boolean,
        expandLabel: String,
    ): String {
        val parts = toolLine.split(" | ").map { it.trim() }
        val status = parts.firstOrNull()?.lowercase().orEmpty()
        val name = parts.getOrNull(2).orEmpty().ifBlank { "tool" }
        val input = parts.firstOrNull { it.startsWith("in: ") }?.removePrefix("in: ").orEmpty()
        val output = parts.firstOrNull { it.startsWith("out: ") }?.removePrefix("out: ").orEmpty()
        val summaryLabel = toolSummaryLabel(name, input, output)
        val isEdit = summaryLabel.contains("编辑")

        val pathMatch = Regex("""([A-Za-z0-9_./-]+\.(kt|java|kts|xml|md|json|yaml|yml|js|ts|tsx|jsx|py|go|rs))""").find("$input $output")
        val filePath = pathMatch?.groupValues?.get(1).orEmpty()
        val fileName = filePath.let { if (it.isBlank()) "" else File(it).name }
        val delta = Regex("""([+-]\d+)""").find(output)?.groupValues?.get(1).orEmpty()
        val fileHtml = if (fileName.isBlank()) {
            ""
        } else {
            val openHref = buildOpenFileHref(filePath)
            """<a href="$openHref" style="color:#62A8FF; font-weight:600; margin-left:6px;">${escapeHtml(fileName)}</a>"""
        }
        val deltaHtml = if (delta.isBlank()) "" else """<span style="color:#7EDC8A; font-weight:600; margin-left:6px;">${escapeHtml(delta)}</span>"""
        val diffChip = if (isEdit) {
            """<span style="font-size:11px; color:#D6DDEA; border:1px solid #3A4351; background:#252B35; border-radius:6px; padding:2px 7px; margin-right:6px;">改动</span>"""
        } else ""
        val title = if (isEdit) "编辑文件" else summaryLabel
        val expandLink = if (canExpand) {
            val tone = if (toolsExpanded) "#AAC2E6" else "#8FB6F4"
            """<a href="$toolsToggleHref" style="font-size:11px; color:$tone; margin-right:8px;">$expandLabel</a>"""
        } else ""
        val dotColor = when (status) {
            "done" -> "#4FCC6A"
            "failed" -> "#E86D6D"
            else -> "#E2C06B"
        }

        return """
            <div style="max-width:94%; border:1px solid #2A3140; border-radius:10px; background:#151922; padding:10px 12px; margin-bottom:8px;">
              <div style="display:flex; justify-content:space-between; align-items:center;">
                <div style="color:#D6DEEC; font-size:13px;">✎ ${escapeHtml(title)}$fileHtml$deltaHtml</div>
                <div>$expandLink$diffChip<span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:$dotColor;"></span></div>
              </div>
            </div>
        """.trimIndent()
    }

    private fun inferToolLineFromContent(text: String): String? {
        if (text.isBlank()) return null
        val filePatterns = listOf(
            Regex("""修改文件\s*[:：]\s*([^\s`]+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|filepath|path)\s*[:：]\s*([^\s`]+)""", RegexOption.IGNORE_CASE),
        )
        val filePath = filePatterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) } ?: return null
        val normalizedPath = filePath.trim().trim('`', '"', '\'')
        if (normalizedPath.isBlank()) return null
        return "done | id:inferred | write | in: $normalizedPath | out: inferred"
    }

    private fun renderStreamingState(streamingText: String?): String {
        if (!isRunning) return ""

        val elapsedSeconds = elapsedSeconds()
        val status = currentStatusText.ifBlank { "正在生成响应..." }
        val spinnerFrames = arrayOf("◜", "◠", "◝", "◞", "◡", "◟")
        val frame = spinnerFrames[elapsedSeconds % spinnerFrames.size]
        val timerText = "已用 ${elapsedSeconds}s"
        val liveFlowRows = buildList {
            currentToolEventsBuffer.forEachIndexed { index, trace ->
                val label = when {
                    trace.failed -> "失败"
                    trace.done -> "完成"
                    else -> "执行中"
                }
                val dot = when {
                    trace.failed -> "#E86D6D"
                    trace.done -> "#71D37C"
                    else -> "#E2C06B"
                }
                add(
                    FlowStepRow(
                        sequence = trace.sequence,
                        startedAtMs = trace.startedAtMs.takeIf { it > 0L },
                        html = """
                            <div class="step-line" style="margin-bottom:6px;">
                              <span class="step-dot" style="background:$dot;"></span>
                              <div style="font-size:11px; color:#C7D6EA;">工具 ${index + 1} · ${escapeHtml(trace.name)} · $label</div>
                            </div>
                        """.trimIndent(),
                        order = index,
                    ),
                )
            }
            currentCommandEventsBuffer.forEachIndexed { index, cmd ->
                val label = when (cmd.status) {
                    CommandStatus.DONE -> "已完成"
                    CommandStatus.FAILED -> "失败"
                    CommandStatus.RUNNING -> "执行中"
                    CommandStatus.SKIPPED -> "已跳过"
                    CommandStatus.PENDING -> "待确认"
                }
                val dot = when (cmd.status) {
                    CommandStatus.DONE -> "#71D37C"
                    CommandStatus.FAILED -> "#E86D6D"
                    CommandStatus.RUNNING -> "#E2C06B"
                    CommandStatus.SKIPPED -> "#8E9AAF"
                    CommandStatus.PENDING -> "#B5C3D8"
                }
                add(
                    FlowStepRow(
                        sequence = cmd.sequence,
                        startedAtMs = cmd.startedAtMs.takeIf { it > 0L },
                        html = """
                            <div class="step-line" style="margin-bottom:6px;">
                              <span class="step-dot" style="background:$dot;"></span>
                              <div style="font-size:11px; color:#C7D6EA;">命令 ${index + 1} · ${escapeHtml(cmd.command.ifBlank { "待确认命令" }.take(48))} · $label</div>
                            </div>
                        """.trimIndent(),
                        order = 1_000 + index,
                    ),
                )
            }
        }.sortedWith(
            compareBy<FlowStepRow> { it.sequence ?: Int.MAX_VALUE }
                .thenBy { it.startedAtMs ?: Long.MAX_VALUE }
                .thenBy { it.order },
        )
        val liveFlowHtml = if (liveFlowRows.isEmpty()) "" else """
            <div class="section-card" style="margin-top:8px;">
              <div class="section-title">执行流程（实时）</div>
              ${liveFlowRows.joinToString("") { it.html }}
            </div>
        """.trimIndent()
        val contentHtml = if (streamingText.isNullOrBlank()) "" else """
            <div class="section-card" style="margin-top:8px;">
              <div class="section-title">结果摘要（实时）</div>
              ${renderStructuredContent(streamingText)}
            </div>
        """.trimIndent()

        return """
            <div style="margin: 10px 0 6px 0; border:1px solid #304057; border-radius:12px; background:#131A24; padding:10px 12px;">
              <div style="font-size:11px; color:#8FA8C7; margin-bottom:6px;">处理中</div>
              <table style="border-collapse:collapse; margin-bottom:6px;">
                <tr>
                  <td style="font-size:28px; color:#8D98AA; padding-right:8px; vertical-align:middle;">$frame</td>
                  <td style="font-size:14px; color:#C9D2E0; vertical-align:middle;">
                    ${escapeHtml(status)} <span style="color:#9FA9B9; margin-left:6px;">$timerText</span>
                  </td>
                </tr>
              </table>
              $liveFlowHtml
              $contentHtml
            </div>
        """.trimIndent()
    }

    private fun buildStreamingPreviewMessage(): String {
        val content = currentAssistantContentBuffer.toString().trim()
        val thinking = currentThinkingBuffer.toString().trim()
        return buildAssistantStructuredMessage(
            content = content,
            thinking = thinking,
            tools = currentToolEventsBuffer,
            commands = currentCommandEventsBuffer,
            narratives = currentNarrativeEventsBuffer,
            includeThinking = true,
        )
    }

    private fun buildLiveTurnSnapshot(): LiveTurnSnapshot? {
        if (turnFinalized) {
            return null
        }
        if (currentTimelineActionsBuffer.isNotEmpty()) {
            return LiveTurnSnapshot(
                statusText = currentStatusText,
                actions = currentTimelineActionsBuffer.toList(),
                isRunning = isRunning,
                startedAtMs = requestStartedAtMs.takeIf { it > 0L },
            )
        }
        val content = currentAssistantContentBuffer.toString().trim()
        val thinking = currentThinkingBuffer.toString().trim()
        val hasLiveContent = turnActive ||
            currentStatusText.isNotBlank() ||
            content.isNotBlank() ||
            thinking.isNotBlank() ||
            currentToolEventsBuffer.isNotEmpty() ||
            currentCommandEventsBuffer.isNotEmpty()
        if (!hasLiveContent) {
            return null
        }
        return LiveTurnSnapshot(
            statusText = currentStatusText,
            assistantContent = content,
            thinking = thinking,
            notes = currentNarrativeEventsBuffer
                .filter { it.body.isNotBlank() }
                .map { trace ->
                    LiveNarrativeTrace(
                        id = trace.id,
                        body = trace.body.trim(),
                        sequence = trace.sequence,
                        origin = trace.origin,
                        kind = TimelineNodeKind.ASSISTANT_NOTE,
                        timestamp = trace.startedAtMs.takeIf { it > 0L },
                    )
                },
            tools = currentToolEventsBuffer.map { trace ->
                LiveToolTrace(
                    id = trace.id,
                    name = trace.name,
                    input = trace.input,
                    output = trace.output,
                    status = when {
                        trace.failed -> TimelineNodeStatus.FAILED
                        trace.done -> TimelineNodeStatus.SUCCESS
                        else -> TimelineNodeStatus.RUNNING
                    },
                    sequence = trace.sequence,
                    startedAtMs = trace.startedAtMs.takeIf { it > 0L },
                )
            },
            commands = currentCommandEventsBuffer.map { trace ->
                LiveCommandTrace(
                    id = trace.id,
                    command = trace.command,
                    cwd = trace.cwd,
                    output = trace.output,
                    status = when (trace.status) {
                        CommandStatus.DONE -> TimelineNodeStatus.SUCCESS
                        CommandStatus.FAILED -> TimelineNodeStatus.FAILED
                        CommandStatus.SKIPPED -> TimelineNodeStatus.SKIPPED
                        CommandStatus.PENDING,
                        CommandStatus.RUNNING,
                        -> TimelineNodeStatus.RUNNING
                    },
                    sequence = trace.sequence,
                    exitCode = trace.exitCode,
                    startedAtMs = trace.startedAtMs.takeIf { it > 0L },
                )
            },
            isRunning = isRunning,
            startedAtMs = requestStartedAtMs.takeIf { it > 0L },
        )
    }

    private fun buildAssistantStructuredMessage(
        content: String,
        thinking: String,
        tools: List<ToolTraceItem>,
        commands: List<CommandTraceItem>,
        narratives: List<NarrativeTraceItem>,
        includeThinking: Boolean,
    ): String {
        val sections = mutableListOf<String>()
        if (includeThinking && thinking.isNotBlank()) {
            sections.add("### Thinking\n$thinking")
        }
        if (content.isNotBlank()) {
            sections.add("### Response\n$content")
        }
        val narrativeLines = serializeNarratives(narratives)
        if (narrativeLines.isNotEmpty()) {
            sections.add("### Narrative\n${narrativeLines.joinToString("\n") { "- $it" }}")
        }
        if (tools.isNotEmpty()) {
            val toolLines = tools.joinToString("\n") { "- ${serializeToolTrace(it)}" }
            sections.add("### Tools\n$toolLines")
        }
        if (commands.isNotEmpty()) {
            val commandLines = commands.joinToString("\n") { "- ${serializeCommandTrace(it)}" }
            sections.add("### Commands\n$commandLines")
        }
        return sections.joinToString("\n\n").trim()
    }

    private fun serializeNarratives(narratives: List<NarrativeTraceItem>): List<String> {
        val cleaned = narratives
            .map { it.copy(body = it.body.trim()) }
            .filter { it.body.isNotBlank() }
        if (cleaned.isEmpty()) return emptyList()
        val lastContentIndex = cleaned.indexOfLast { it.source == NarrativeSource.CONTENT }
        return cleaned.mapIndexed { index, trace ->
            val kind = if (index == lastContentIndex) "result" else "note"
            val body64 = Base64.getEncoder().encodeToString(trace.body.toByteArray(StandardCharsets.UTF_8))
            buildString {
                append(kind)
                append(" | id:").append(trace.id)
                append(" | seq:").append(trace.sequence)
                append(" | origin:").append(
                    when (trace.origin) {
                        TimelineNodeOrigin.EVENT -> "event"
                        TimelineNodeOrigin.INFERRED_RESPONSE -> "inferred_response"
                    },
                )
                if (trace.startedAtMs > 0L) {
                    append(" | ts:").append(trace.startedAtMs)
                }
                append(" | body64:").append(body64)
            }
        }
    }

    private fun registerNarrativeContent(text: String) {
        if (text.isBlank()) return
        val trace = activeContentNarrativeId
            ?.let { id -> currentNarrativeEventsBuffer.firstOrNull { it.id == id } }
            ?: NarrativeTraceItem(
                id = "note-${currentNarrativeEventsBuffer.size + 1}",
                sequence = nextFlowSequence(),
                origin = TimelineNodeOrigin.EVENT,
                source = NarrativeSource.CONTENT,
                startedAtMs = System.currentTimeMillis(),
            ).also {
                currentNarrativeEventsBuffer.add(it)
                activeContentNarrativeId = it.id
            }
        trace.body += text
    }

    private fun registerNarrativeStatus(status: String) {
        if (!shouldRecordStatusAsNarrative(status)) {
            return
        }
        closeActiveContentNarrative()
        val previous = currentNarrativeEventsBuffer.lastOrNull()
        if (previous?.source == NarrativeSource.STATUS && previous.body == status) {
            return
        }
        currentNarrativeEventsBuffer.add(
            NarrativeTraceItem(
                id = "note-${currentNarrativeEventsBuffer.size + 1}",
                sequence = nextFlowSequence(),
                body = status,
                origin = TimelineNodeOrigin.EVENT,
                source = NarrativeSource.STATUS,
                startedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun closeActiveContentNarrative() {
        activeContentNarrativeId = null
    }

    private fun shouldRecordStatusAsNarrative(status: String): Boolean {
        return status !in setOf(
            "正在生成响应...",
            "正在准备会话...",
            "正在执行步骤...",
            "连接中断，正在重试...",
        )
    }

    private fun extractSections(content: String): Map<String, String> {
        val pattern = Regex("(?m)^###\\s+(Thinking|Response|Tools|Commands)\\s*$")
        val matches = pattern.findAll(content).toList()
        if (matches.isEmpty()) return emptyMap()
        val sections = linkedMapOf<String, String>()
        matches.forEachIndexed { index, match ->
            val name = match.groupValues[1].lowercase()
            val start = match.range.last + 1
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else content.length
            sections[name] = content.substring(start, end).trim()
        }
        return sections
    }

    private fun parseToolLines(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (it.startsWith("- ")) it.removePrefix("- ").trim() else it }
    }

    private fun parseCommandLines(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (it.startsWith("- ")) it.removePrefix("- ").trim() else it }
    }

    private fun inferToolLinesFromResponse(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val rows = mutableListOf<String>()
        val filePattern = Regex("""([A-Za-z0-9_./-]+\.(kt|kts|java|xml|md|json|yaml|yml|js|ts|tsx|jsx|py|go|rs))""")
        filePattern.findAll(text).forEach { match ->
            val path = match.groupValues[1]
            if (rows.none { it.contains(path) }) {
                rows.add("done | id:infer-tool-${rows.size + 1} | read_file | in: $path | out: inferred")
            }
        }
        if (rows.isEmpty() && text.contains("修改文件")) {
            rows.add("done | id:infer-tool-1 | edit_file | in: from response | out: inferred")
        }
        return rows
    }

    private fun inferCommandLinesFromResponse(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val lines = text.lines().map { it.trim() }
        val rows = mutableListOf<String>()
        lines.forEach { line ->
            if (line.isBlank()) return@forEach
            val looksLikeCmd = line.startsWith("$ ") ||
                line.contains("/bin/zsh -lc") ||
                line.contains("javac ") ||
                line.contains("java ") ||
                line.contains("gradle") ||
                line.contains("find ") ||
                line.contains("grep ") ||
                line.contains("rg ")
            if (looksLikeCmd) {
                val command = line.removePrefix("$ ").trim()
                rows.add("command | id:infer-cmd-${rows.size + 1} | status:done | cmd: $command | out: inferred")
            }
        }
        return rows
    }

    private fun parseToolMeta(toolLine: String): ToolMeta {
        val parts = toolLine.split(" | ").map { it.trim() }
        val status = parts.firstOrNull()?.lowercase().orEmpty()
        val name = parts.getOrNull(2).orEmpty().ifBlank { "tool" }
        val sequence = parts.firstOrNull { it.startsWith("seq:") }
            ?.removePrefix("seq:")
            ?.trim()
            ?.toIntOrNull()
        val startedAtMs = parts.firstOrNull { it.startsWith("ts:") }
            ?.removePrefix("ts:")
            ?.trim()
            ?.toLongOrNull()
        val durationMs = parts.firstOrNull { it.startsWith("dur:") }
            ?.removePrefix("dur:")
            ?.trim()
            ?.toLongOrNull()
        return ToolMeta(
            status = status,
            name = name,
            sequence = sequence,
            startedAtMs = startedAtMs,
            durationMs = durationMs,
        )
    }

    private fun parseCommandMeta(commandLine: String): CommandMeta {
        val parts = commandLine.split(" | ").map { it.trim() }
        val id = parts.firstOrNull { it.startsWith("id:") }?.removePrefix("id:").orEmpty()
        val status = parts.firstOrNull { it.startsWith("status:") }?.removePrefix("status:").orEmpty()
        val sequence = parts.firstOrNull { it.startsWith("seq:") }?.removePrefix("seq:")?.trim()?.toIntOrNull()
        val command = parts.firstOrNull { it.startsWith("cmd: ") }?.removePrefix("cmd: ").orEmpty()
        val cwd = parts.firstOrNull { it.startsWith("cwd: ") }?.removePrefix("cwd: ").orEmpty()
        val startedAtMs = parts.firstOrNull { it.startsWith("ts:") }?.removePrefix("ts:")?.trim()?.toLongOrNull()
        val durationMs = parts.firstOrNull { it.startsWith("dur:") }?.removePrefix("dur:")?.trim()?.toLongOrNull()
        val exitCode = parts.firstOrNull { it.startsWith("exit:") }?.removePrefix("exit:")?.trim()?.toIntOrNull()
        val output = parts.firstOrNull { it.startsWith("out: ") }?.removePrefix("out: ").orEmpty()
        return CommandMeta(
            id = id,
            status = status,
            sequence = sequence,
            command = command,
            cwd = cwd,
            startedAtMs = startedAtMs,
            durationMs = durationMs,
            exitCode = exitCode,
            output = output,
        )
    }

    private fun formatClockTime(epochMs: Long): String {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timeFormatter)
    }

    private fun formatDuration(durationMs: Long): String {
        return ToolWindowUiText.formatDuration(durationMs)
    }

    private fun renderToolCard(toolLine: String, showDetails: Boolean, stepIndex: Int): String {
        val parts = toolLine.split(" | ").map { it.trim() }
        val status = parts.firstOrNull()?.lowercase().orEmpty()
        val id = parts.getOrNull(1).orEmpty().removePrefix("id:")
        val name = parts.getOrNull(2).orEmpty().ifBlank { "tool" }
        val startedAtMs = parts.firstOrNull { it.startsWith("ts:") }?.removePrefix("ts:")?.trim()?.toLongOrNull()
        val durationMs = parts.firstOrNull { it.startsWith("dur:") }?.removePrefix("dur:")?.trim()?.toLongOrNull()
        val input = parts.firstOrNull { it.startsWith("in: ") }?.removePrefix("in: ").orEmpty()
        val output = parts.firstOrNull { it.startsWith("out: ") }?.removePrefix("out: ").orEmpty()
        val details = buildString {
            if (input.isNotBlank()) append("输入: ").append(input)
            if (output.isNotBlank()) {
                if (isNotBlank()) append(" · ")
                append("输出: ").append(output)
            }
        }.ifBlank { toolLine }
        val escapedLine = escapeHtml(details)
        val isFailure = status == "failed" || toolLine.contains("失败")
        val isDone = status == "done" || toolLine.contains("完成")
        val dotColor = when {
            isFailure -> "#E86D6D"
            isDone -> "#71D37C"
            else -> "#E2C06B"
        }
        val showDiffBadge = details.contains("编辑") ||
            details.contains("patch", ignoreCase = true) ||
            details.contains("diff", ignoreCase = true) ||
            details.contains("write", ignoreCase = true) ||
            details.contains(".kt") || details.contains(".java")

        val badge = if (showDiffBadge) {
            """<span style="font-size:11px; color:#D6DDEA; border:1px solid #3A4351; background:#252B35; border-radius:8px; padding:2px 8px; margin-right:8px;">改动</span>"""
        } else ""
        val runningBadge = if (status == "running") {
            """<span style="font-size:11px; color:#E7CF8A; border:1px solid #5A4B24; background:#2F2918; border-radius:8px; padding:2px 8px; margin-right:8px;">执行中</span>"""
        } else ""
        val summaryLabel = toolSummaryLabel(name, input, output)

        val isShellTool = name.contains("bash", ignoreCase = true) ||
            name.contains("shell", ignoreCase = true) ||
            name.contains("command", ignoreCase = true) ||
            input.startsWith("sh ") || input.startsWith("bash ")
        val shellBlock = if (showDetails && isShellTool && input.isNotBlank()) {
            """
            <div style="margin-top:8px; padding:8px 10px; border-radius:8px; border:1px solid #394150; background:#171B22; font-family: Menlo, Consolas, monospace; font-size:12px; color:#DDE6F5;">$ ${escapeHtml(input)}</div>
            """.trimIndent()
        } else ""
        val exitCodeMatch = Regex("""exit code[:\s]+\[?(-?\d+)\]?""", RegexOption.IGNORE_CASE).find(output)
        val exitCodeHtml = if (showDetails && exitCodeMatch != null) {
            val code = exitCodeMatch.groupValues[1]
            val codeColor = if (code == "0") "#71D37C" else "#E86D6D"
            """<div style="margin-top:6px; font-size:12px; color:$codeColor;">exit code: $code</div>"""
        } else ""
        val retryLink = if (isFailure && id.isNotBlank()) {
            val href = buildRetryHref(name, input)
            """<a href="$href" style="font-size:11px; color:#9DC1FF; border:1px solid #3A4D70; background:#1F2B3D; border-radius:8px; padding:2px 8px; text-decoration:none; margin-right:8px;">重试</a>"""
        } else ""
        val detailHtml = if (showDetails) {
            """<div style="color:#97A3B8; font-size:12px;">$escapedLine</div>"""
        } else {
            """<div style="color:#97A3B8; font-size:12px;">${escapeHtml(name)} · ${if (isFailure) "失败" else if (isDone) "完成" else "执行中"}</div>"""
        }
        val timingLabel = buildString {
            if (startedAtMs != null) {
                append("开始 ").append(formatClockTime(startedAtMs))
            }
            if (durationMs != null) {
                if (isNotBlank()) append(" · ")
                append("耗时 ").append(formatDuration(durationMs))
            }
        }
        val timingHtml = if (timingLabel.isBlank()) "" else {
            """<div style="color:#8EA4C2; font-size:11px; margin-bottom:6px;">$timingLabel</div>"""
        }

        return """
            <div style="margin:8px 0; border:1px solid #2A3140; background:#141821; border-radius:10px; padding:10px 12px;">
              <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:6px;">
                <div style="color:#D6DEEC; font-size:13px;">步骤 $stepIndex · ${renderInlineFormatting(summaryLabel)}</div>
                <div>$retryLink$badge$runningBadge<span style="display:inline-block; width:9px; height:9px; border-radius:50%; background:$dotColor;"></span></div>
              </div>
              $timingHtml
              $detailHtml
              $shellBlock
              $exitCodeHtml
            </div>
        """.trimIndent()
    }

    private fun renderCommandCard(commandLine: String, showDetails: Boolean, index: Int): String {
        val meta = parseCommandMeta(commandLine)
        val statusLabel = when (meta.status.lowercase()) {
            "running" -> "执行中"
            "done" -> "已完成"
            "failed" -> "失败"
            "skipped" -> "已跳过"
            else -> "待确认"
        }
        val dotColor = when (meta.status.lowercase()) {
            "running" -> "#E2C06B"
            "done" -> "#71D37C"
            "failed" -> "#E86D6D"
            "skipped" -> "#8E9AAF"
            else -> "#B5C3D8"
        }
        val timingLabel = buildString {
            meta.startedAtMs?.let { append("开始 ").append(formatClockTime(it)) }
            meta.durationMs?.let {
                if (isNotBlank()) append(" · ")
                append("耗时 ").append(formatDuration(it))
            }
        }
        val timingHtml = if (timingLabel.isBlank()) "" else """<div style="font-size:11px; color:#8EA4C2; margin-top:2px;">$timingLabel</div>"""
        val retryHref = if (meta.command.isBlank()) "" else buildRetryCommandHref(meta.command, meta.cwd)
        val copyHref = if (meta.command.isBlank()) "" else buildCopyCommandHref(meta.command)
        val actionsHtml = if (!showDetails) {
            ""
        } else {
            buildString {
                if (retryHref.isNotBlank()) append("""<a class="btn-link" href="$retryHref">重试</a>""")
                if (copyHref.isNotBlank()) append("""<a class="btn-link" href="$copyHref">复制命令</a>""")
            }
        }
        val detailHtml = if (!showDetails) "" else {
            val commandBlock = if (meta.command.isBlank()) "" else """
                <div style="margin-top:8px; padding:8px 10px; border-radius:8px; border:1px solid #394150; background:#171B22; font-family: Menlo, Consolas, monospace; font-size:12px; color:#DDE6F5;">$ ${escapeHtml(meta.command)}</div>
            """.trimIndent()
            val cwdBlock = if (meta.cwd.isBlank()) "" else """<div style="font-size:11px; color:#94A7C1; margin-top:6px;">目录: ${escapeHtml(meta.cwd)}</div>"""
            val exitBlock = if (meta.exitCode == null) "" else {
                val codeColor = if (meta.exitCode == 0) "#71D37C" else "#E86D6D"
                """<div style="font-size:11px; color:$codeColor; margin-top:6px;">exit code: ${meta.exitCode}</div>"""
            }
            val outputBlock = if (meta.output.isBlank()) "" else """<div style="font-size:12px; color:#A8B8CE; margin-top:6px;">输出: ${escapeHtml(meta.output)}</div>"""
            "$commandBlock$cwdBlock$exitBlock$outputBlock"
        }
        return """
            <div class="action-card">
              <div style="display:flex; justify-content:space-between; align-items:center;">
                <div style="color:#D6DEEC; font-size:13px;">命令 ${index + 1} · ${escapeHtml(statusLabel)}</div>
                <div><span style="display:inline-block; width:9px; height:9px; border-radius:50%; background:$dotColor;"></span></div>
              </div>
              $timingHtml
              <div style="font-size:12px; color:#B9C9DE; margin-top:6px;">${escapeHtml(meta.command.ifBlank { "（无命令文本）" })}</div>
              <div style="margin-top:6px;">$actionsHtml</div>
              $detailHtml
            </div>
        """.trimIndent()
    }

    private fun registerToolCall(event: AgentEvent.ToolCall) {
        closeActiveContentNarrative()
        val trace = findOrCreateToolTrace(event.callId, event.name)
        trace.input = compactToolText(event.input)
        trace.done = false
        trace.failed = false
        if (trace.startedAtMs == 0L) {
            trace.startedAtMs = System.currentTimeMillis()
        }
        trace.finishedAtMs = 0L
    }

    private fun registerToolResult(event: AgentEvent.ToolResult) {
        closeActiveContentNarrative()
        val trace = findOrCreateToolTrace(event.callId, event.name)
        if (!event.output.isNullOrBlank()) {
            trace.output = compactToolText(event.output)
        }
        trace.done = true
        trace.failed = event.isError
        if (trace.startedAtMs == 0L) {
            trace.startedAtMs = System.currentTimeMillis()
        }
        trace.finishedAtMs = System.currentTimeMillis()
    }

    private fun findOrCreateToolTrace(callId: String?, name: String): ToolTraceItem {
        if (!callId.isNullOrBlank()) {
            currentToolEventsBuffer.firstOrNull { it.id == callId }?.let { return it }
        }
        currentToolEventsBuffer.lastOrNull {
            !it.done && it.name.equals(name, ignoreCase = true)
        }?.let { return it }

        val newTrace = ToolTraceItem(
            id = callId ?: "tool-${currentToolEventsBuffer.size + 1}",
            name = name,
            sequence = nextFlowSequence(),
        )
        currentToolEventsBuffer.add(newTrace)
        return newTrace
    }

    private fun serializeToolTrace(trace: ToolTraceItem): String {
        val status = when {
            trace.failed -> "failed"
            trace.done -> "done"
            else -> "running"
        }
        val parts = mutableListOf(status, "id:${trace.id}", trace.name)
        parts.add("seq:${trace.sequence}")
        if (trace.startedAtMs > 0L) {
            parts.add("ts:${trace.startedAtMs}")
        }
        if (trace.finishedAtMs > 0L && trace.startedAtMs > 0L) {
            parts.add("dur:${(trace.finishedAtMs - trace.startedAtMs).coerceAtLeast(0L)}")
        }
        if (trace.input.isNotBlank()) {
            parts.add("in: ${trace.input}")
        }
        if (trace.output.isNotBlank()) {
            parts.add("out: ${trace.output}")
        }
        return parts.joinToString(" | ")
    }

    private fun serializeCommandTrace(trace: CommandTraceItem): String {
        val parts = mutableListOf("command", "id:${trace.id}", "status:${trace.status}")
        parts.add("seq:${trace.sequence}")
        parts.add("cmd: ${trace.command}")
        if (trace.cwd.isNotBlank()) {
            parts.add("cwd: ${trace.cwd}")
        }
        if (trace.startedAtMs > 0L) {
            parts.add("ts:${trace.startedAtMs}")
        }
        if (trace.finishedAtMs > 0L && trace.startedAtMs > 0L) {
            parts.add("dur:${(trace.finishedAtMs - trace.startedAtMs).coerceAtLeast(0L)}")
        }
        trace.exitCode?.let { parts.add("exit:$it") }
        if (trace.output.isNotBlank()) {
            parts.add("out: ${trace.output}")
        }
        return parts.joinToString(" | ")
    }

    private fun nextFlowSequence(): Int {
        currentFlowSequence += 1
        return currentFlowSequence
    }

    private fun compactToolText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.replace("\n", " ").replace(Regex("\\s+"), " ").trim().take(220)
    }

    private fun buildRetryHref(name: String, input: String): String {
        val n = URLEncoder.encode(name, StandardCharsets.UTF_8)
        val i = URLEncoder.encode(input, StandardCharsets.UTF_8)
        return "action://retry-tool?name=$n&input=$i"
    }

    private fun buildCopyHref(messageId: String): String {
        val id = URLEncoder.encode(messageId, StandardCharsets.UTF_8)
        return "action://copy-message?id=$id"
    }

    private fun buildToggleHref(action: String, messageId: String): String {
        val id = URLEncoder.encode(messageId, StandardCharsets.UTF_8)
        return "action://$action?id=$id"
    }

    private fun buildToggleTurnHref(turnId: String): String {
        val id = URLEncoder.encode(turnId, StandardCharsets.UTF_8)
        return "action://toggle-turn?id=$id"
    }

    private fun buildOpenFileHref(path: String): String {
        val encoded = URLEncoder.encode(path, StandardCharsets.UTF_8)
        return "action://open-file?path=$encoded"
    }

    private fun buildRetryCommandHref(command: String, cwd: String): String {
        val c = URLEncoder.encode(command, StandardCharsets.UTF_8)
        val d = URLEncoder.encode(cwd, StandardCharsets.UTF_8)
        return "action://retry-command?command=$c&cwd=$d"
    }

    private fun buildCopyCommandHref(command: String): String {
        val c = URLEncoder.encode(command, StandardCharsets.UTF_8)
        return "action://copy-command?command=$c"
    }

    private fun extractChangedFiles(tools: List<String>): List<ChangedFileRef> {
        if (tools.isEmpty()) return emptyList()
        val found = linkedMapOf<String, ChangedFileRef>()
        tools.forEach { line ->
            val lowered = line.lowercase()
            val likelyMutation = lowered.contains(" write ") ||
                lowered.contains(" edit ") ||
                lowered.contains(" patch ") ||
                lowered.contains(" diff ") ||
                lowered.contains(" apply ")
            if (!likelyMutation) return@forEach
            val path = extractFilePathFromToolLine(line) ?: return@forEach
            val cleanPath = path.trim().trim('`', '"', '\'')
            if (cleanPath.isBlank()) return@forEach
            val delta = Regex("""([+-]\d+)""").find(line)?.groupValues?.get(1).orEmpty()
            found[cleanPath] = ChangedFileRef(
                path = cleanPath,
                name = File(cleanPath).name.ifBlank { cleanPath },
                delta = delta,
            )
        }
        return found.values.toList()
    }

    private fun extractFilePathFromToolLine(toolLine: String): String? {
        val pathPattern = Regex("""(?:in|out):\s*([^|]+?\.(?:kt|kts|java|xml|md|json|yaml|yml|js|ts|tsx|jsx|py|go|rs))""", RegexOption.IGNORE_CASE)
        return pathPattern.find(toolLine)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun systemRiskTag(content: String): Pair<String, String> {
        val lowered = content.lowercase()
        return when {
            lowered.contains("rm ") || lowered.contains("delete") || lowered.contains("drop table") ->
                "高风险操作" to "#E58A8A"
            lowered.contains("command:") || lowered.contains("exit code") ->
                "命令执行" to "#E2C06B"
            lowered.contains("applied diff") || lowered.contains("diff") ->
                "代码变更" to "#7FB2FF"
            else -> "" to ""
        }
    }

    private fun toolSummaryLabel(name: String, input: String, output: String): String {
        val lowered = "$name $input $output".lowercase()
        val isEdit = lowered.contains("edit") || lowered.contains("write") ||
            lowered.contains("patch") || lowered.contains("diff") || lowered.contains("apply")
        if (!isEdit) return name

        val pathMatch = Regex("""([A-Za-z0-9_./-]+\.(kt|java|kts|xml|md|json|yaml|yml|js|ts|tsx|jsx|py|go|rs))""").find("$input $output")
        val fileName = pathMatch?.groupValues?.get(1)?.let { File(it).name }
        val delta = Regex("""([+-]\d+)""").find(output)?.groupValues?.get(1)
        val suffix = listOfNotNull(fileName, delta).joinToString(" ")
        return if (suffix.isBlank()) "编辑文件" else "编辑文件 $suffix"
    }

    private fun elapsedSeconds(): Int {
        if (!isRunning || requestStartedAtMs <= 0L) return 0
        return ((System.currentTimeMillis() - requestStartedAtMs) / 1000L).toInt()
    }

    private fun normalizeStatus(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return "正在生成响应..."
        return when {
            value.contains("turn.started", ignoreCase = true) -> "正在生成响应..."
            value.contains("thread.started", ignoreCase = true) -> "正在准备会话..."
            value.contains("item.started", ignoreCase = true) -> "正在执行步骤..."
            value.contains("turn.completed", ignoreCase = true) -> ""
            value.contains("thread.completed", ignoreCase = true) -> ""
            value.contains("item.completed", ignoreCase = true) -> ""
            value.contains("reconnecting", ignoreCase = true) -> "连接中断，正在重试..."
            else -> value
        }
    }

    private fun finishTurn(doneText: String, persistIfNeeded: Boolean) {
        val shouldRefreshFiles = hasLikelyFileMutationInTurn()
        val totalDurationMs = requestStartedAtMs.takeIf { it > 0L }?.let { System.currentTimeMillis() - it }
        if (persistIfNeeded && !turnFinalized) {
            closeActiveContentNarrative()
            val content = currentAssistantContentBuffer.toString().trim()
            val thinking = currentThinkingBuffer.toString().trim()
            val assistantMessage = buildAssistantStructuredMessage(
                content = content,
                thinking = thinking,
                tools = currentToolEventsBuffer,
                commands = currentCommandEventsBuffer,
                narratives = currentNarrativeEventsBuffer,
                includeThinking = true,
            )
            if (assistantMessage.isNotBlank()) {
                chatService.addMessage(ChatMessage(role = MessageRole.ASSISTANT, content = assistantMessage))
                refreshMessages()
            }
        }

        turnActive = false
        loadingTimer.stop()
        previewRenderTimer.stop()
        currentAssistantContentBuffer = StringBuilder()
        currentThinkingBuffer = StringBuilder()
        currentTimelineActionsBuffer.clear()
        currentToolEventsBuffer.clear()
        currentCommandEventsBuffer.clear()
        currentNarrativeEventsBuffer.clear()
        activeContentNarrativeId = null
        currentFlowSequence = 0
        currentStatusText = ""
        requestStartedAtMs = 0L
        setStatusMessage(ToolWindowUiText.finishedStatus(doneText, totalDurationMs))
        setRunningState(false)
        renderMessages(streamingText = null, forceAutoScroll = false)
        maybeRefreshProjectFiles(force = shouldRefreshFiles)
        showMessagesCardIfNeeded()
    }

    private fun hasLikelyFileMutationInTurn(): Boolean {
        val merged = if (currentTimelineActionsBuffer.isNotEmpty()) {
            currentTimelineActionsBuffer.joinToString(" ") { action ->
                when (action) {
                    is TimelineAction.UpsertTool -> "${action.name} ${action.input} ${action.output}"
                    is TimelineAction.UpsertCommand -> "${action.command} ${action.output}"
                    is TimelineAction.DiffProposalReceived -> "${action.filePath} ${action.newContent}"
                    else -> ""
                }
            }
        } else {
            if (currentToolEventsBuffer.isEmpty() && currentCommandEventsBuffer.isEmpty()) return false
            currentToolEventsBuffer.joinToString(" ") {
                "${it.name} ${it.input} ${it.output}"
            } + " " + currentCommandEventsBuffer.joinToString(" ") {
                "${it.command} ${it.output}"
            }
        }
        val lowered = merged.lowercase()
        if (lowered.isBlank()) return false
        if (mutationKeywords.any { lowered.contains(it) }) return true
        return Regex("""\.(kt|kts|java|xml|md|json|yaml|yml|js|ts|tsx|jsx|py|go|rs)\b""").containsMatchIn(lowered)
    }

    private fun maybeRefreshProjectFiles(force: Boolean = false) {
        if (!force || project.isDisposed) return
        val baseDirPath = project.basePath ?: return
        val baseDir = LocalFileSystem.getInstance().findFileByPath(baseDirPath) ?: return
        VfsUtil.markDirtyAndRefresh(false, true, true, baseDir)

        val fileManager = FileDocumentManager.getInstance()
        FileEditorManager.getInstance(project).selectedFiles.forEach { file ->
            val document = fileManager.getDocument(file) ?: return@forEach
            if (!fileManager.isFileModified(file)) {
                fileManager.reloadFromDisk(document)
            }
        }
    }

    private fun renderStructuredContent(text: String): String {
        if (text.isBlank()) return ""
        val lines = text.replace("\r\n", "\n").split('\n')
        val html = StringBuilder()
        val paragraphLines = mutableListOf<String>()
        val listItems = mutableListOf<String>()
        var listType: String? = null
        var inCodeBlock = false
        var codeLang = ""
        val codeLines = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraphLines.isEmpty()) return
            val paragraph = paragraphLines.joinToString("<br/>") { renderInlineFormatting(it) }
            html.append("""<div style="margin: 0 0 6px 0;">$paragraph</div>""")
            paragraphLines.clear()
        }

        fun flushList() {
            if (listType == null || listItems.isEmpty()) return
            val listHtml = listItems.joinToString("") { "<li style=\"margin:2px 0;\">$it</li>" }
            html.append("<$listType style=\"margin: 4px 0 8px 18px; padding: 0;\">$listHtml</$listType>")
            listType = null
            listItems.clear()
        }

        fun flushCode() {
            if (!inCodeBlock) return
            val label = if (codeLang.isBlank()) "" else """<div style="font-size:10px; color:#9FB0C5; margin-bottom:4px;">${escapeHtml(codeLang)}</div>"""
            val code = escapeHtml(codeLines.joinToString("\n"))
            html.append("""<div style="margin:6px 0; background:#1D2128; border:1px solid #3A4352; border-radius:8px; padding:8px;">$label<pre style="margin:0; white-space:pre-wrap; font-family: Menlo, Consolas, monospace; font-size:11px; color:#D9E2F1;">$code</pre></div>""")
            inCodeBlock = false
            codeLang = ""
            codeLines.clear()
        }

        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                flushParagraph()
                flushList()
                if (inCodeBlock) {
                    flushCode()
                } else {
                    inCodeBlock = true
                    codeLang = trimmed.removePrefix("```").trim()
                }
                return@forEach
            }

            if (inCodeBlock) {
                codeLines.add(line)
                return@forEach
            }

            if (trimmed.isEmpty()) {
                flushParagraph()
                flushList()
                return@forEach
            }

            val headingMatch = Regex("^(#{1,3})\\s+(.+)$").find(trimmed)
            if (headingMatch != null) {
                flushParagraph()
                flushList()
                val level = headingMatch.groupValues[1].length
                val size = when (level) {
                    1 -> "16px"
                    2 -> "14px"
                    else -> "13px"
                }
                html.append("""<div style="margin:8px 0 6px 0; font-weight:600; font-size:$size;">${renderInlineFormatting(headingMatch.groupValues[2])}</div>""")
                return@forEach
            }

            if (trimmed.startsWith(">")) {
                flushParagraph()
                flushList()
                val quote = trimmed.removePrefix(">").trim()
                html.append("""<div style="margin:6px 0; border-left:3px solid #5D6C7F; padding:2px 0 2px 8px; color:#C9D3E2;">${renderInlineFormatting(quote)}</div>""")
                return@forEach
            }

            val unordered = Regex("^[-*]\\s+(.+)$").find(trimmed)
            if (unordered != null) {
                flushParagraph()
                if (listType != "ul") {
                    flushList()
                    listType = "ul"
                }
                listItems.add(renderInlineFormatting(unordered.groupValues[1]))
                return@forEach
            }

            val ordered = Regex("^\\d+[.)]\\s+(.+)$").find(trimmed)
            if (ordered != null) {
                flushParagraph()
                if (listType != "ol") {
                    flushList()
                    listType = "ol"
                }
                listItems.add(renderInlineFormatting(ordered.groupValues[1]))
                return@forEach
            }

            flushList()
            paragraphLines.add(line)
        }

        flushParagraph()
        flushList()
        flushCode()
        return html.toString()
    }

    private fun renderInlineFormatting(text: String): String {
        var html = escapeHtml(text)
        html = html.replace(Regex("`([^`]+)`")) { match ->
            "<code style=\"font-family: Menlo, Consolas, monospace; background:#1D2128; border:1px solid #3A4352; border-radius:4px; padding:1px 4px;\">${match.groupValues[1]}</code>"
        }
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*")) { match ->
            "<strong>${match.groupValues[1]}</strong>"
        }
        return html
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun collectContextFiles(): List<ContextFile> {
        val files = mutableListOf<ContextFile>()

        val editorContext = currentEditorContextFile()
        if (editorContext != null) {
            val pathOnly = editorContext.path.substringBefore("#L")
            if (dismissedEditorContextPath != pathOnly) {
                files.add(editorContext)
            }
        }

        attachedFiles.forEach { path ->
            val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return@forEach
            if (vf.length > maxContextFileBytes) return@forEach
            val content = readVirtualFile(vf) ?: return@forEach
            if (files.none { it.path == path }) {
                files.add(ContextFile(path = path, content = content))
            }
        }

        return files
    }

    private fun currentEditorContextFile(): ContextFile? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val document = editor.document
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return null
        val selectedText = editor.selectionModel.selectedText
        val hasSelection = !selectedText.isNullOrBlank()
        val content = if (hasSelection) selectedText!! else truncateContextContent(document.text)

        val path = if (hasSelection) {
            val startLine = document.getLineNumber(editor.selectionModel.selectionStart) + 1
            val endLine = document.getLineNumber(editor.selectionModel.selectionEnd) + 1
            "${vFile.path}#L$startLine-$endLine"
        } else {
            vFile.path
        }
        return ContextFile(path = path, content = content)
    }

    private fun syncEditorContextIndicator() {
        val selectedFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        if (selectedFile == null) {
            editorContextPanel.isVisible = false
            dismissedEditorContextPath = null
            return
        }
        val path = selectedFile.path
        if (dismissedEditorContextPath != null && dismissedEditorContextPath != path) {
            dismissedEditorContextPath = null
        }
        if (dismissedEditorContextPath == path) {
            editorContextPanel.isVisible = false
            return
        }
        editorContextLabel.text = selectedFile.name
        editorContextPanel.toolTipText = "Current file context: ${selectedFile.path}"
        editorContextPanel.isVisible = true
    }

    private fun dismissCurrentEditorContext() {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        dismissedEditorContextPath = file.path
        syncEditorContextIndicator()
    }

    private fun installEditorContextListener() {
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    ApplicationManager.getApplication().invokeLater { refreshChipLabels() }
                }
            },
        )
    }

    private fun readVirtualFile(file: VirtualFile): String? {
        if (file.length > maxContextFileBytes) return null
        return runCatching {
            truncateContextContent(String(file.contentsToByteArray(), file.charset))
        }.getOrNull()
    }

    private fun truncateContextContent(raw: String): String {
        if (raw.length <= maxContextChars) return raw
        return raw.take(maxContextChars) + "\n\n...[truncated for context]"
    }

    private fun invalidateMessageHtmlCache() {
        cachedMessagesHtml = ""
        cachedMessageCount = -1
        cachedLastMessageId = ""
        cachedToolDetailMode = expandAllTools
    }

    private fun resolveVirtualFile(path: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val absolute = if (FileUtil.isAbsolute(path)) path else FileUtil.join(basePath, path)
        return LocalFileSystem.getInstance().findFileByPath(absolute)
    }

    companion object {
        private const val CARD_PLACEHOLDER = "placeholder"
        private const val CARD_MESSAGES = "messages"
        private const val WORKSPACE_CONSOLE = "workspace-console"
        private const val WORKSPACE_HISTORY = "workspace-history"
        private const val WORKSPACE_SETTINGS = "workspace-settings"
        private const val WORKSPACE_CONTEXT = "workspace-context"
        private const val maxContextChars = 120_000
        private const val maxContextFileBytes = 200_000L
        private val mutationKeywords: Set<String> = setOf(
            "write",
            "edit",
            "patch",
            "diff",
            "apply",
            "rename",
            "delete",
            "create",
            "modify",
            "save",
            "update",
        )
        private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    private data class ToolTraceItem(
        val id: String,
        val name: String,
        val sequence: Int,
        var input: String = "",
        var output: String = "",
        var done: Boolean = false,
        var failed: Boolean = false,
        var startedAtMs: Long = 0L,
        var finishedAtMs: Long = 0L,
    )

    private data class ChangedFileRef(
        val path: String,
        val name: String,
        val delta: String,
    )

    private data class TurnViewModel(
        val id: String,
        val userMessage: ChatMessage?,
        val assistantMessage: ChatMessage?,
        val systemMessages: List<ChatMessage>,
    )

    private data class ToolMeta(
        val status: String,
        val name: String,
        val sequence: Int?,
        val startedAtMs: Long?,
        val durationMs: Long?,
    )

    private data class CommandTraceItem(
        val id: String,
        val command: String,
        val cwd: String,
        val sequence: Int,
        var status: CommandStatus = CommandStatus.PENDING,
        var startedAtMs: Long = 0L,
        var finishedAtMs: Long = 0L,
        var exitCode: Int? = null,
        var output: String = "",
    )

    private data class NarrativeTraceItem(
        val id: String,
        val sequence: Int,
        var body: String = "",
        val origin: TimelineNodeOrigin,
        val source: NarrativeSource,
        val startedAtMs: Long = 0L,
    )

    private enum class CommandStatus {
        PENDING,
        RUNNING,
        DONE,
        FAILED,
        SKIPPED,
    }

    private enum class NarrativeSource {
        STATUS,
        CONTENT,
    }

    private data class CommandMeta(
        val id: String,
        val status: String,
        val sequence: Int?,
        val command: String,
        val cwd: String,
        val startedAtMs: Long?,
        val durationMs: Long?,
        val exitCode: Int?,
        val output: String,
    )

    private data class FlowStepRow(
        val sequence: Int?,
        val startedAtMs: Long?,
        val html: String,
        val order: Int,
    )

    private fun clearConversation() {
        chatService.clearMessages()
        resetConversationUi()
        refreshMessages()
        showWorkspace(ToolWindowView.CONSOLE)
    }

    private fun startNewSession() {
        if (chatService.isCurrentSessionEmpty()) {
            return
        }
        val confirm = Messages.showYesNoDialog(
            project,
            "Create a new session? Current session history will remain in Session History.",
            "New Session",
            "Create",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) {
            return
        }
        chatService.createSession()
        resetConversationUi()
        refreshMessages()
        refreshNewButtonState()
        showWorkspace(ToolWindowView.CONSOLE)
    }

    private fun refreshNewButtonState() {
        newChatButton.isEnabled = !chatService.isCurrentSessionEmpty()
    }

    private fun showSettingsMenu(anchor: JComponent) {
        val menu = JPopupMenu()
        menu.add(JMenuItem("Session History").apply {
            addActionListener { openSessionHistoryDialog() }
        })
        menu.show(anchor, 0, anchor.height)
    }

    private fun openSessionHistoryDialog() {
        val dialog = SessionHistoryDialog(
            project = project,
            sessions = chatService.listSessions(),
            currentSessionId = chatService.getCurrentSessionId(),
        )
        if (!dialog.showAndGet()) {
            return
        }
        dialog.deletedSessionIds().forEach { chatService.deleteSession(it) }
        if (dialog.createNewRequested()) {
            chatService.createSession()
        } else {
            dialog.selectedSessionId()?.let { chatService.switchSession(it) }
        }
        resetConversationUi()
        refreshMessages()
    }

    private fun installInputKeyBindings() {
        val inputMap = inputArea.getInputMap(WHEN_FOCUSED)
        val actionMap = inputArea.actionMap
        inputMap.put(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "sendPrompt")
        actionMap.put("sendPrompt", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                submitPrompt()
            }
        })
        inputMap.put(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break")
    }
}

private class SessionHistoryDialog(
    private val project: Project,
    sessions: List<AgentChatService.SessionSummary>,
    currentSessionId: String,
) : DialogWrapper(project) {
    private data class Row(val id: String, val title: String, val subtitle: String) {
        override fun toString(): String = "$title  ·  $subtitle"
    }

    private val model = DefaultListModel<Row>()
    private val list = JBList(model)
    private val deletedSessionIds = linkedSetOf<String>()
    private var createNewRequested = false

    init {
        title = "Session History"
        sessions.forEach { s ->
            model.addElement(
                Row(
                    id = s.id,
                    title = s.title,
                    subtitle = "${s.messageCount} msg",
                ),
            )
        }
        val idx = (0 until model.size()).firstOrNull { model.get(it).id == currentSessionId } ?: 0
        if (idx >= 0 && model.size() > 0) {
            list.selectedIndex = idx
        }
        init()
    }

    fun selectedSessionId(): String? {
        return list.selectedValue?.id
    }

    fun deletedSessionIds(): Set<String> = deletedSessionIds

    fun createNewRequested(): Boolean = createNewRequested

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(8, 8))
        root.preferredSize = Dimension(560, 360)
        root.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        val listPane = JBScrollPane(list)
        listPane.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(0x324861, 0x324861), 1, true),
            BorderFactory.createEmptyBorder(4, 4, 4, 4),
        )

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        val newButton = JButton("New Session")
        val deleteButton = JButton("Delete Selected")
        deleteButton.foreground = JBColor(0xF29B9B, 0xF29B9B)
        buttons.add(newButton)
        buttons.add(deleteButton)

        newButton.addActionListener {
            createNewRequested = true
            close(OK_EXIT_CODE)
        }

        deleteButton.addActionListener {
            val row = list.selectedValue ?: return@addActionListener
            deletedSessionIds.add(row.id)
            val idx = list.selectedIndex
            if (idx >= 0) {
                model.remove(idx)
            }
            if (model.size() > 0) {
                list.selectedIndex = minOf(idx, model.size() - 1)
            }
        }

        root.add(listPane, BorderLayout.CENTER)
        root.add(buttons, BorderLayout.SOUTH)
        return root
    }
}

private class ContextFilesDialog(
    private val project: Project,
    initialAttachedFiles: Set<String>,
) : DialogWrapper(project) {
    private val filesModel = DefaultListModel<String>()
    private val filesList = JBList(filesModel)

    init {
        title = "Context Files"
        initialAttachedFiles.forEach(filesModel::addElement)
        init()
    }

    fun attachedFiles(): Set<String> = (0 until filesModel.size()).map { filesModel.get(it) }.toSet()

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(8, 8))
        root.preferredSize = Dimension(540, 360)
        root.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        val attach = JButton("Attach File")
        val remove = JButton("Remove Selected")
        val clear = JButton("Clear All")
        actions.add(attach)
        actions.add(remove)
        actions.add(clear)

        filesList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        val listPane = JBScrollPane(filesList)
        listPane.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(0x324861, 0x324861), 1, true),
            BorderFactory.createEmptyBorder(4, 4, 4, 4),
        )

        attach.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            val file = FileChooser.chooseFile(descriptor, project, null) ?: return@addActionListener
            if ((0 until filesModel.size()).none { filesModel[it] == file.path }) {
                filesModel.addElement(file.path)
            }
        }

        remove.addActionListener {
            val idx = filesList.selectedIndex
            if (idx >= 0) {
                filesModel.remove(idx)
            }
        }

        clear.addActionListener {
            filesModel.clear()
        }

        root.add(listPane, BorderLayout.CENTER)
        root.add(actions, BorderLayout.SOUTH)
        return root
    }
}
