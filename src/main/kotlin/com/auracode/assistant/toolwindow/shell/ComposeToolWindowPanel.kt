package com.auracode.assistant.toolwindow.shell

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.context.EditorContextProvider
import com.auracode.assistant.context.SmartFileSearchService
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.notification.ChatCompletionNotificationService
import com.auracode.assistant.notification.IdeAttentionState
import com.auracode.assistant.notification.IdeAttentionStateProvider
import com.auracode.assistant.settings.skills.SkillsManagementAdapterRegistry
import com.auracode.assistant.settings.skills.SkillsRuntimeService
import com.auracode.assistant.toolwindow.execution.ApprovalAreaStore
import com.auracode.assistant.toolwindow.submission.SubmissionAreaStore
import com.auracode.assistant.toolwindow.shell.SidePanelAreaStore
import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.ToolWindowCoordinator
import com.auracode.assistant.toolwindow.eventing.ToolWindowEventHub
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.external.ExternalRequestRouter
import com.auracode.assistant.toolwindow.sessions.SessionTabsAreaStore
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptStore
import com.auracode.assistant.toolwindow.sessions.SessionTabCoordinator
import com.auracode.assistant.toolwindow.sessions.SessionAttentionStore
import com.auracode.assistant.toolwindow.execution.ExecutionStatusAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationFileChange
import com.auracode.assistant.toolwindow.conversation.ConversationFileChangePreview
import com.auracode.assistant.toolwindow.shared.assistantMaterialColors
import com.auracode.assistant.toolwindow.shared.assistantPalette
import com.auracode.assistant.toolwindow.shared.assistantTypography
import com.auracode.assistant.toolwindow.shared.currentIdeDarkTheme
import com.auracode.assistant.toolwindow.shared.resolveEffectiveTheme
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import java.awt.BorderLayout
import java.awt.KeyboardFocusManager
import javax.swing.JPanel

class ComposeToolWindowPanel(
    project: Project,
    toolWindow: ToolWindow,
) : JPanel(BorderLayout()), Disposable {
    private val hostToolWindow = toolWindow
    private var toolWindowAnchor by mutableStateOf(hostToolWindow.anchor ?: ToolWindowAnchor.RIGHT)
    private val chatService = project.getService(AgentChatService::class.java)
    private val editorContextProvider = EditorContextProvider.getInstance(project)
    private val smartFileSearchService = SmartFileSearchService.getInstance(project)
    private val settingsService = AgentSettingsService.getInstance()
    private val eventHub = ToolWindowEventHub()

    private val sessionTabsStore = SessionTabsAreaStore()
    private val executionStatusStore = ExecutionStatusAreaStore()
    private val conversationStore = ConversationAreaStore()
    private val skillsRuntimeService = SkillsRuntimeService(
        adapterRegistry = SkillsManagementAdapterRegistry(settingsService),
    )
    private val submissionStore = SubmissionAreaStore(
        availableSkillsProvider = {
            skillsRuntimeService.enabledSlashSkills(
                engineId = chatService.defaultEngineId(),
                cwd = chatService.currentWorkingDirectory(),
            )
        },
    )
    private val sidePanelStore = SidePanelAreaStore()
    private val approvalStore = ApprovalAreaStore()
    private val toolUserInputPromptStore = ToolUserInputPromptStore()
    private val sessionAttentionStore = SessionAttentionStore()
    private val externalRequestRouter = project.getService(ExternalRequestRouter::class.java)
    private val externalRequestRegistration = externalRequestRouter.registerHandler { request ->
        eventHub.publishUiIntent(UiIntent.SubmitExternalRequest(request))
    }

    private lateinit var sessionTabCoordinator: SessionTabCoordinator
    private val attentionStateProvider = IdeAttentionStateProvider {
        IdeAttentionState(
            isIdeFrameFocused = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow != null,
            isToolWindowVisible = hostToolWindow.isVisible,
            activeSessionId = chatService.getCurrentSessionId(),
        )
    }
    private val completionNotificationService = ChatCompletionNotificationService(
        settingsService = settingsService,
        attentionStateProvider = attentionStateProvider,
        attentionStore = sessionAttentionStore,
    )

    private val coordinator = ToolWindowCoordinator(
        chatService = chatService,
        settingsService = settingsService,
        eventHub = eventHub,
        sessionTabsStore = sessionTabsStore,
        executionStatusStore = executionStatusStore,
        conversationStore = conversationStore,
        submissionStore = submissionStore,
        sidePanelStore = sidePanelStore,
        approvalStore = approvalStore,
        toolUserInputPromptStore = toolUserInputPromptStore,
        completionNotificationService = completionNotificationService,
        sessionAttentionStore = sessionAttentionStore,
        skillsRuntimeService = skillsRuntimeService,
        pickAttachments = {
            val app = ApplicationManager.getApplication()
            var selected: List<String> = emptyList()
            val chooserAction = Runnable {
                val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                selected = FileChooser.chooseFiles(descriptor, project, null).map { it.path }
            }
            if (app.isDispatchThread) {
                chooserAction.run()
            } else {
                app.invokeAndWait(chooserAction)
            }
            selected
        },
        pickExportPath = { suggestedFileName ->
            val app = ApplicationManager.getApplication()
            var selected: String? = null
            val chooserAction = Runnable {
                val descriptor = FileSaverDescriptor(
                    AuraCodeBundle.message("drawer.history.export.dialog.title"),
                    AuraCodeBundle.message("drawer.history.export.dialog.description"),
                    "md",
                )
                val baseDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                selected = FileChooserFactory.getInstance()
                    .createSaveFileDialog(descriptor, project)
                    .save(baseDir, suggestedFileName)
                    ?.file
                    ?.absolutePath
            }
            if (app.isDispatchThread) {
                chooserAction.run()
            } else {
                app.invokeAndWait(chooserAction)
            }
            selected
        },
        searchProjectFiles = { query, limit ->
            smartFileSearchService.searchByName(query = query, limit = limit)
        },
        openConversationFileChange = { change ->
            openConversationFileChange(project, change)
        },
        openConversationFilePath = { path ->
            openConversationFilePath(project, path)
        },
        revealPathInFileManager = { path ->
            revealPathInFileManager(path)
        },
        openSessionInNewTab = { sessionId ->
            sessionTabCoordinator.openExistingSessionInNewTab(sessionId)
        },
        onSessionSnapshotPublished = {
            if (::sessionTabCoordinator.isInitialized) {
                sessionTabCoordinator.refresh()
            }
        },
    )

    private val composePanel = ComposePanel()

    init {
        sessionTabCoordinator = SessionTabCoordinator(
            chatService = chatService,
            sessionAttentionStore = sessionAttentionStore,
            toolWindowProvider = { toolWindow as? ToolWindowEx },
            onStatus = { message -> eventHub.publish(AppEvent.StatusTextUpdated(message)) },
            onBeforeSessionActivated = { sessionId -> coordinator.captureSessionState(sessionId) },
            onSessionActivated = { coordinator.onSessionActivated() },
        )
        sessionTabCoordinator.initialize()
        updateToolWindowText()

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    publishFocusedContextSnapshot()
                }
            },
        )
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(event: SelectionEvent) {
                    val currentEditor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
                    if (currentEditor == null || currentEditor != event.editor) return
                    publishFocusedContextSnapshot()
                }
            },
            this,
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                settingsService.notifyAppearanceChanged()
            },
        )
        publishFocusedContextSnapshot()

        add(composePanel, BorderLayout.CENTER)
        composePanel.setContent {
            val sessionTabsState by sessionTabsStore.state.collectAsState()
            val executionStatusState by executionStatusStore.state.collectAsState()
            val conversationState by conversationStore.state.collectAsState()
            val submissionState by submissionStore.state.collectAsState()
            val sidePanelState by sidePanelStore.state.collectAsState()
            val approvalState by approvalStore.state.collectAsState()
            val toolUserInputPromptState by toolUserInputPromptStore.state.collectAsState()
            val languageVersion by settingsService.languageVersion.collectAsState()
            val appearanceVersion by settingsService.appearanceVersion.collectAsState()
            val themeMode = sidePanelState.themeMode
            val effectiveTheme = resolveEffectiveTheme(themeMode, currentIdeDarkTheme())
            val palette = assistantPalette(effectiveTheme)

            MaterialTheme(
                colors = assistantMaterialColors(effectiveTheme, palette),
                typography = assistantTypography(),
            ) {
                LaunchedEffect(languageVersion, appearanceVersion) {
                    refreshWindowChrome()
                }
                key(languageVersion, appearanceVersion) {
                    Surface(modifier = Modifier) {
                        ToolWindowScreen(
                            sessionTabsState = sessionTabsState,
                            executionStatusState = executionStatusState,
                            conversationState = conversationState,
                            submissionState = submissionState,
                            sidePanelState = sidePanelState,
                            approvalState = approvalState,
                            toolUserInputPromptState = toolUserInputPromptState,
                            anchor = toolWindowAnchor,
                            themeMode = themeMode,
                            onIntent = ::dispatchIntent,
                        )
                    }
                }
            }
        }
    }

    private fun refreshWindowChrome() {
        val app = ApplicationManager.getApplication()
        val action = Runnable {
            updateToolWindowText()
            sessionTabCoordinator.refresh()
            toolWindowAnchor = hostToolWindow.anchor ?: ToolWindowAnchor.RIGHT
            revalidate()
            repaint()
            composePanel.revalidate()
            composePanel.repaint()
        }
        if (app.isDispatchThread) {
            action.run()
        } else {
            app.invokeLater(action)
        }
    }

    private fun updateToolWindowText() {
        val title = AuraCodeBundle.message("plugin.name")
        hostToolWindow.setTitle(title)
        hostToolWindow.setStripeTitle(title)
    }

    private fun dispatchIntent(intent: UiIntent) {
        when (intent) {
            is UiIntent.UpdateDocument -> {
                submissionStore.applyDocumentUpdate(intent.value)?.let { request ->
                    request.mention?.let { mention ->
                        eventHub.publishUiIntent(
                            UiIntent.RequestMentionSuggestions(
                                query = mention.query,
                                documentVersion = mention.documentVersion,
                            ),
                        )
                    }
                    request.agentQuery?.let { query ->
                        eventHub.publishUiIntent(
                            UiIntent.RequestAgentSuggestions(
                                query = query,
                                documentVersion = request.documentVersion,
                            ),
                        )
                    }
                }
            }
            is UiIntent.SelectSlashCommand -> {
                when (resolveSlashCommandDispatch(intent.command)) {
                    SlashCommandDispatch.PUBLISH_ONLY -> eventHub.publishUiIntent(intent)
                    SlashCommandDispatch.START_NEW_SESSION -> {
                        // Reuse the existing new-session entrypoint so slash and header behavior stay identical.
                        eventHub.publishUiIntent(intent)
                        sessionTabCoordinator.startNewSession()
                    }
                }
            }
            UiIntent.NewSession -> sessionTabCoordinator.startNewSession()
            UiIntent.NewTab -> sessionTabCoordinator.startNewWindowTab()
            is UiIntent.SwitchSession -> sessionTabCoordinator.switchToSession(intent.sessionId)
            else -> eventHub.publishUiIntent(intent)
        }
    }

    override fun dispose() {
        externalRequestRegistration.dispose()
        coordinator.dispose()
    }

    private fun publishFocusedContextSnapshot() {
        eventHub.publishUiIntent(
            UiIntent.UpdateFocusedContextFile(editorContextProvider.getFocusedContextSnapshot()),
        )
    }

    private fun openConversationFileChange(
        project: Project,
        change: ConversationFileChange,
    ) {
        val app = ApplicationManager.getApplication()
        val action = Runnable {
            val vFile = LocalFileSystem.getInstance().findFileByPath(change.path)
            val resolved = ConversationFileChangePreview.resolve(change)
            val oldContent = resolved.oldContent
            val newContent = resolved.newContent
            if (!oldContent.isNullOrBlank() || !newContent.isNullOrBlank()) {
                val contentFactory = DiffContentFactory.getInstance()
                val left = contentFactory.create(project, oldContent.orEmpty(), vFile?.fileType)
                val right = contentFactory.create(project, newContent.orEmpty(), vFile?.fileType)
                val request = SimpleDiffRequest(
                    change.displayName,
                    left,
                    right,
                    "Current",
                    "Proposed",
                )
                DiffManager.getInstance().showDiff(project, request)
                return@Runnable
            }
            vFile?.let { OpenFileDescriptor(project, it).navigate(true) }
        }
        if (app.isDispatchThread) {
            action.run()
        } else {
            app.invokeLater(action)
        }
    }

    private fun openConversationFilePath(
        project: Project,
        path: String,
    ) {
        val app = ApplicationManager.getApplication()
        val action = Runnable {
            LocalFileSystem.getInstance()
                .findFileByPath(path)
                ?.let { OpenFileDescriptor(project, it).navigate(true) }
        }
        if (app.isDispatchThread) {
            action.run()
        } else {
            app.invokeLater(action)
            }
        }
    }

    private fun revealPathInFileManager(path: String): Boolean {
        return runCatching {
            val target = java.io.File(path).takeIf { it.exists() } ?: return false
            val revealTarget = if (target.isDirectory) target else target.parentFile ?: target
            if (!java.awt.Desktop.isDesktopSupported()) return false
            java.awt.Desktop.getDesktop().open(revealTarget)
            true
        }.getOrDefault(false)
    }
