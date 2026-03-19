package com.codex.assistant.toolwindow.bootstrap

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
import com.codex.assistant.service.AgentChatService
import com.codex.assistant.context.EditorContextProvider
import com.codex.assistant.context.SmartFileSearchService
import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.settings.AgentSettingsService
import com.codex.assistant.toolwindow.composer.ComposerAreaStore
import com.codex.assistant.toolwindow.drawer.RightDrawerAreaStore
import com.codex.assistant.toolwindow.eventing.AppEvent
import com.codex.assistant.toolwindow.eventing.ToolWindowCoordinator
import com.codex.assistant.toolwindow.eventing.ToolWindowEventHub
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.header.HeaderAreaStore
import com.codex.assistant.toolwindow.session.SessionTabCoordinator
import com.codex.assistant.toolwindow.status.StatusAreaStore
import com.codex.assistant.toolwindow.timeline.TimelineAreaStore
import com.codex.assistant.toolwindow.timeline.TimelineFileChange
import com.codex.assistant.toolwindow.shared.assistantMaterialColors
import com.codex.assistant.toolwindow.shared.assistantPalette
import com.codex.assistant.toolwindow.shared.assistantTypography
import com.codex.assistant.toolwindow.shared.currentIdeDarkTheme
import com.codex.assistant.toolwindow.shared.resolveEffectiveTheme
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
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

    private val headerStore = HeaderAreaStore()
    private val statusStore = StatusAreaStore()
    private val timelineStore = TimelineAreaStore()
    private val composerStore = ComposerAreaStore()
    private val rightDrawerStore = RightDrawerAreaStore()

    private lateinit var sessionTabCoordinator: SessionTabCoordinator

    private val coordinator = ToolWindowCoordinator(
        chatService = chatService,
        settingsService = settingsService,
        eventHub = eventHub,
        headerStore = headerStore,
        statusStore = statusStore,
        timelineStore = timelineStore,
        composerStore = composerStore,
        rightDrawerStore = rightDrawerStore,
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
        searchProjectFiles = { query, limit ->
            smartFileSearchService.searchByName(query = query, limit = limit)
        },
        openTimelineFileChange = { change ->
            openTimelineFileChange(project, change)
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
            toolWindowProvider = { toolWindow as? ToolWindowEx },
            isRunning = { timelineStore.state.value.isRunning },
            onStatus = { message -> eventHub.publish(AppEvent.StatusTextUpdated(message)) },
            onSessionActivated = { coordinator.onSessionActivated() },
        )
        sessionTabCoordinator.initialize()
        updateToolWindowText()

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    eventHub.publishUiIntent(UiIntent.UpdateFocusedContextFile(event.newFile?.path))
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            LafManagerListener.TOPIC,
            LafManagerListener {
                settingsService.notifyAppearanceChanged()
            },
        )
        eventHub.publishUiIntent(UiIntent.UpdateFocusedContextFile(editorContextProvider.getCurrentFile()))

        add(composePanel, BorderLayout.CENTER)
        composePanel.setContent {
            val headerState by headerStore.state.collectAsState()
            val statusState by statusStore.state.collectAsState()
            val timelineState by timelineStore.state.collectAsState()
            val composerState by composerStore.state.collectAsState()
            val rightDrawerState by rightDrawerStore.state.collectAsState()
            val languageVersion by settingsService.languageVersion.collectAsState()
            val appearanceVersion by settingsService.appearanceVersion.collectAsState()
            val themeMode = rightDrawerState.themeMode
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
                            headerState = headerState,
                            statusState = statusState,
                            timelineState = timelineState,
                            composerState = composerState,
                            rightDrawerState = rightDrawerState,
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
        val title = CodexBundle.message("plugin.name")
        hostToolWindow.setTitle(title)
        hostToolWindow.setStripeTitle(title)
    }

    private fun dispatchIntent(intent: UiIntent) {
        when (intent) {
            is UiIntent.UpdateDocument -> {
                composerStore.applyDocumentUpdate(intent.value)?.let { request ->
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
            UiIntent.NewSession -> sessionTabCoordinator.startNewSession()
            UiIntent.NewTab -> sessionTabCoordinator.startNewWindowTab()
            is UiIntent.SwitchSession -> sessionTabCoordinator.switchToSession(intent.sessionId)
            else -> eventHub.publishUiIntent(intent)
        }
    }

    override fun dispose() {
        coordinator.dispose()
    }

    private fun openTimelineFileChange(
        project: Project,
        change: TimelineFileChange,
    ) {
        val app = ApplicationManager.getApplication()
        val action = Runnable {
            val vFile = LocalFileSystem.getInstance().findFileByPath(change.path)
            val oldContent = change.oldContent
            val newContent = change.newContent
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
}
