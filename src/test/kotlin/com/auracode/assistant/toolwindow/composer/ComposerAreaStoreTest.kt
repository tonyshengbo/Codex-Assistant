package com.auracode.assistant.toolwindow.composer

import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.UnifiedApprovalRequestKind
import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.model.ContextFile
import com.auracode.assistant.model.TurnUsageSnapshot
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.provider.codex.CodexModelCatalog
import com.auracode.assistant.settings.SavedAgentDefinition
import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.ComposerMode
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.approval.PendingApprovalRequestUiModel
import com.auracode.assistant.toolwindow.plan.PlanCompletionPromptUiModel
import com.auracode.assistant.toolwindow.timeline.TimelineFileChange
import com.auracode.assistant.toolwindow.timeline.TimelineFileChangeKind
import com.auracode.assistant.toolwindow.timeline.TimelineMutation
import com.auracode.assistant.toolwindow.toolinput.ToolUserInputOptionUiModel
import com.auracode.assistant.toolwindow.toolinput.ToolUserInputPromptUiModel
import com.auracode.assistant.toolwindow.toolinput.ToolUserInputQuestionUiModel
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ComposerAreaStoreTest {
    @Test
    fun `session snapshot switches composer engine and shows empty session hint`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                selectedEngineId = "codex",
                availableEngines = listOf(
                    EngineDescriptor(
                        id = "codex",
                        displayName = "Codex",
                        models = listOf("gpt-5.4"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = true,
                            supportsCommandProposal = true,
                            supportsDiffProposal = true,
                        ),
                    ),
                    EngineDescriptor(
                        id = "claude",
                        displayName = "Claude",
                        models = listOf("claude-sonnet-4-6"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = false,
                            supportsCommandProposal = false,
                            supportsDiffProposal = false,
                        ),
                    ),
                ),
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
                selectedModel = "gpt-5.4",
            ),
        )

        store.onEvent(
            AppEvent.SessionSnapshotUpdated(
                sessions = listOf(
                    AgentChatService.SessionSummary(
                        id = "s1",
                        title = "Claude Session",
                        updatedAt = 1L,
                        messageCount = 0,
                        remoteConversationId = "",
                        providerId = "claude",
                    ),
                ),
                activeSessionId = "s1",
            ),
        )

        assertEquals("claude", store.state.value.selectedEngineId)
        assertEquals("claude-sonnet-4-6", store.state.value.selectedModel)
        assertEquals(
            "This session is empty. Your first message will start it on Claude.",
            store.state.value.emptyStateHint,
        )
    }

    @Test
    fun `session snapshot clears empty session hint once the conversation has history`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                selectedEngineId = "claude",
                availableEngines = listOf(
                    EngineDescriptor(
                        id = "claude",
                        displayName = "Claude",
                        models = listOf("claude-sonnet-4-6"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = false,
                            supportsCommandProposal = false,
                            supportsDiffProposal = false,
                        ),
                    ),
                ),
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
                selectedModel = "claude-sonnet-4-6",
            ),
        )

        store.onEvent(
            AppEvent.SessionSnapshotUpdated(
                sessions = listOf(
                    AgentChatService.SessionSummary(
                        id = "s1",
                        title = "Claude Session",
                        updatedAt = 1L,
                        messageCount = 2,
                        remoteConversationId = "",
                        providerId = "claude",
                    ),
                ),
                activeSessionId = "s1",
            ),
        )

        assertNull(store.state.value.emptyStateHint)
    }

    @Test
    fun `selecting engine switches composer model to that engine default immediately`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                selectedEngineId = "codex",
                availableEngines = listOf(
                    EngineDescriptor(
                        id = "codex",
                        displayName = "Codex",
                        models = listOf("gpt-5.4"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = true,
                            supportsCommandProposal = true,
                            supportsDiffProposal = true,
                        ),
                    ),
                    EngineDescriptor(
                        id = "claude",
                        displayName = "Claude",
                        models = listOf("claude-sonnet-4-6"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = false,
                            supportsCommandProposal = false,
                            supportsDiffProposal = false,
                        ),
                    ),
                ),
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
                selectedModel = "gpt-5.4",
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectEngine("claude")))

        assertEquals("claude", store.state.value.selectedEngineId)
        assertEquals("claude-sonnet-4-6", store.state.value.selectedModel)
    }

    @Test
    fun `requesting engine switch from a populated session opens a confirmation dialog instead of changing engine`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                selectedEngineId = "codex",
                availableEngines = listOf(
                    EngineDescriptor(
                        id = "codex",
                        displayName = "Codex",
                        models = listOf("gpt-5.4"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = true,
                            supportsCommandProposal = true,
                            supportsDiffProposal = true,
                        ),
                    ),
                    EngineDescriptor(
                        id = "claude",
                        displayName = "Claude",
                        models = listOf("claude-sonnet-4-6"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = false,
                            supportsCommandProposal = false,
                            supportsDiffProposal = false,
                        ),
                    ),
                ),
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
                selectedModel = "gpt-5.4",
            ),
        )
        store.onEvent(
            AppEvent.SessionSnapshotUpdated(
                sessions = listOf(
                    AgentChatService.SessionSummary(
                        id = "s1",
                        title = "Codex Session",
                        updatedAt = 1L,
                        messageCount = 1,
                        remoteConversationId = "",
                        providerId = "codex",
                    ),
                ),
                activeSessionId = "s1",
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.RequestEngineSwitch("claude")))

        assertEquals("codex", store.state.value.selectedEngineId)
        assertEquals(
            EngineSwitchConfirmationState(
                targetEngineId = "claude",
                targetEngineLabel = "Claude",
            ),
            store.state.value.engineSwitchConfirmation,
        )
    }

    @Test
    fun `dismissing engine switch confirmation clears the pending dialog`() {
        val store = ComposerAreaStore()
        store.restoreState(
            ComposerAreaState(
                selectedEngineId = "codex",
                availableEngines = listOf(
                    EngineDescriptor(
                        id = "codex",
                        displayName = "Codex",
                        models = listOf("gpt-5.4"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = true,
                            supportsCommandProposal = true,
                            supportsDiffProposal = true,
                        ),
                    ),
                    EngineDescriptor(
                        id = "claude",
                        displayName = "Claude",
                        models = listOf("claude-sonnet-4-6"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = false,
                            supportsCommandProposal = false,
                            supportsDiffProposal = false,
                        ),
                    ),
                ),
                activeSessionMessageCount = 3,
                engineSwitchConfirmation = EngineSwitchConfirmationState(
                    targetEngineId = "claude",
                    targetEngineLabel = "Claude",
                ),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.DismissEngineSwitchDialog))

        assertNull(store.state.value.engineSwitchConfirmation)
        assertEquals("codex", store.state.value.selectedEngineId)
    }

    @Test
    fun `settings snapshot switches composer engine and model list`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                selectedEngineId = "claude",
                availableEngines = listOf(
                    EngineDescriptor(
                        id = "codex",
                        displayName = "Codex",
                        models = listOf("gpt-5.4"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = true,
                            supportsCommandProposal = true,
                            supportsDiffProposal = true,
                        ),
                    ),
                    EngineDescriptor(
                        id = "claude",
                        displayName = "Claude",
                        models = listOf("claude-sonnet-4-6", "claude-opus-4-1"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = false,
                            supportsCommandProposal = false,
                            supportsDiffProposal = false,
                        ),
                    ),
                ),
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
                selectedModel = "claude-sonnet-4-6",
            ),
        )

        assertEquals("claude", store.state.value.selectedEngineId)
        assertEquals(listOf("claude-sonnet-4-6", "claude-opus-4-1"), store.state.value.modelOptions.map { it.id })
        assertEquals("claude-sonnet-4-6", store.state.value.selectedModel)
    }

    @Test
    fun `conversation capabilities expose plan mode hint for unsupported engine`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                selectedEngineId = "claude",
                availableEngines = listOf(
                    EngineDescriptor(
                        id = "claude",
                        displayName = "Claude",
                        models = listOf("claude-sonnet-4-6"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = false,
                            supportsCommandProposal = false,
                            supportsDiffProposal = false,
                        ),
                    ),
                ),
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
                selectedModel = "claude-sonnet-4-6",
            ),
        )

        store.onEvent(
            AppEvent.ConversationCapabilitiesUpdated(
                ConversationCapabilities(
                    supportsStructuredHistory = false,
                    supportsHistoryPagination = false,
                    supportsPlanMode = false,
                    supportsApprovalRequests = false,
                    supportsToolUserInput = true,
                    supportsResume = true,
                    supportsAttachments = true,
                    supportsImageInputs = true,
                ),
            ),
        )

        assertFalse(store.state.value.planModeAvailable)
        assertEquals("Plan mode is not available for Claude yet.", store.state.value.capabilityHint)
        assertEquals("Plan mode is not available for Claude yet.", store.state.value.disabledCapabilityReason)
    }

    @Test
    fun `focused selection context overrides whole-file focus and keeps manual entries after it`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateFocusedContextFile(
                    FocusedContextSnapshot(
                        path = "/tmp/Feature.kt",
                        selectedText = "fun selected() = true",
                        startLine = 12,
                        endLine = 14,
                    ),
                ),
            ),
        )
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.AddContextFiles(
                    listOf("/tmp/Manual.kt"),
                ),
            ),
        )

        val entries = store.state.value.contextEntries
        assertEquals(2, entries.size)
        assertTrue(entries.first().isSelectionContext)
        assertEquals(12, entries.first().startLine)
        assertEquals(14, entries.first().endLine)
        assertEquals("Feature.kt:12-14", entries.first().displayName)
        assertEquals("/tmp/Manual.kt", entries.last().path)
    }

    @Test
    fun `disabling auto context clears focused entry and ignores later focused updates`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateFocusedContextFile(
                    FocusedContextSnapshot(path = "/tmp/Focused.kt"),
                ),
            ),
        )
        assertEquals("/tmp/Focused.kt", store.state.value.focusedContextEntry?.path)

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = false,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
            ),
        )

        assertFalse(store.state.value.autoContextEnabled)
        assertNull(store.state.value.focusedContextEntry)
        assertTrue(store.state.value.contextEntries.isEmpty())

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateFocusedContextFile(
                    FocusedContextSnapshot(path = "/tmp/Ignored.kt"),
                ),
            ),
        )

        assertNull(store.state.value.focusedContextEntry)
        assertTrue(store.state.value.contextEntries.isEmpty())
    }

    @Test
    fun `file change timeline mutations no longer populate edited files`() {
        val store = ComposerAreaStore()
        val started = TimelineMutation.UpsertFileChange(
            sourceId = "request-1:item-5",
            title = "File Changes",
            changes = listOf(
                TimelineFileChange(
                    sourceScopedId = "request-1:item-5:0",
                    path = "/tmp/hello.java",
                    displayName = "hello.java",
                    kind = TimelineFileChangeKind.UPDATE,
                    timestamp = 100L,
                    oldContent = "fun a() = 1\n",
                    newContent = "fun a() = 2\n",
                    addedLines = 2,
                    deletedLines = 1,
                ),
            ),
            status = ItemStatus.RUNNING,
        )
        val completed = started.copy(status = ItemStatus.SUCCESS)

        store.onEvent(AppEvent.TimelineMutationApplied(started))
        store.onEvent(AppEvent.TimelineMutationApplied(completed))

        assertTrue(store.state.value.editedFiles.isEmpty())
        assertEquals(0, store.state.value.editedFilesSummary.total)
    }

    @Test
    fun `turn diff updated accumulates files by path across turns`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.TurnDiffUpdated(
                threadId = "thread-1",
                turnId = "turn-1",
                diff = """
                    diff --git a//tmp/Foo.kt b//tmp/Foo.kt
                    --- a//tmp/Foo.kt
                    +++ b//tmp/Foo.kt
                    @@ -1 +1,2 @@
                    -fun a() = 1
                    +fun a() = 2
                    +fun b() = 3
                """.trimIndent(),
            ),
        )
        store.onEvent(
            AppEvent.TurnDiffUpdated(
                threadId = "thread-1",
                turnId = "turn-2",
                diff = """
                    diff --git a//tmp/Foo.kt b//tmp/Foo.kt
                    --- a//tmp/Foo.kt
                    +++ b//tmp/Foo.kt
                    @@ -1,2 +1,3 @@
                     fun a() = 2
                     fun b() = 3
                    +fun c() = 4
                    diff --git a//tmp/Bar.kt b//tmp/Bar.kt
                    new file mode 100644
                    --- /dev/null
                    +++ b//tmp/Bar.kt
                    @@ -0,0 +1 @@
                    +class Bar
                """.trimIndent(),
            ),
        )

        val files = store.state.value.editedFiles
        assertEquals(listOf("/tmp/Bar.kt", "/tmp/Foo.kt"), files.map { it.path })
        assertEquals(2, store.state.value.editedFilesSummary.total)
    }

    @Test
    fun `duplicate turn diff snapshots are ignored`() {
        val store = ComposerAreaStore()
        val diff = """
            diff --git a//tmp/Foo.kt b//tmp/Foo.kt
            --- a//tmp/Foo.kt
            +++ b//tmp/Foo.kt
            @@ -1 +1 @@
            -fun a() = 1
            +fun a() = 2
        """.trimIndent()

        store.onEvent(AppEvent.TurnDiffUpdated(threadId = "thread-1", turnId = "turn-1", diff = diff))
        store.onEvent(AppEvent.TurnDiffUpdated(threadId = "thread-1", turnId = "turn-1", diff = diff))

        val aggregate = store.state.value.editedFiles.single()
        assertEquals(1, store.state.value.editedFilesSummary.total)
        assertEquals("/tmp/Foo.kt", aggregate.path)
    }

    @Test
    fun `create file diff is represented from turn diff only`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.TurnDiffUpdated(
                threadId = "thread-1",
                turnId = "turn-1",
                diff = """
                    diff --git a//tmp/Created.kt b//tmp/Created.kt
                    new file mode 100644
                    --- /dev/null
                    +++ b//tmp/Created.kt
                    @@ -0,0 +1 @@
                    +class Created
                """.trimIndent(),
            ),
        )

        val aggregate = store.state.value.editedFiles.single()
        assertEquals(TimelineFileChangeKind.CREATE, aggregate.parsedDiff.kind)
        assertEquals("turn-1", aggregate.turnId)
        assertEquals("thread-1", aggregate.threadId)
    }

    @Test
    fun `turn diff updated replaces same path with latest turn diff`() {
        val store = ComposerAreaStore()
        val workingDir = createTempDirectory("edited-files-baseline")
        val file = workingDir.resolve("Foo.kt")

        Files.writeString(file, "fun a() = 2\n")
        store.onEvent(
            AppEvent.TurnDiffUpdated(
                threadId = "thread-1",
                turnId = "turn-1",
                diff = """
                    diff --git a/${file} b/${file}
                    --- a/${file}
                    +++ b/${file}
                    @@ -1 +1 @@
                    -fun a() = 1
                    +fun a() = 2
                """.trimIndent(),
            ),
        )

        Files.writeString(file, "fun a() = 3\n")
        store.onEvent(
            AppEvent.TurnDiffUpdated(
                threadId = "thread-1",
                turnId = "turn-2",
                diff = """
                    diff --git a/${file} b/${file}
                    --- a/${file}
                    +++ b/${file}
                    @@ -1 +1 @@
                    -fun a() = 2
                    +fun a() = 3
                """.trimIndent(),
            ),
        )

        val aggregate = store.state.value.editedFiles.single()
        assertEquals("turn-2", aggregate.turnId)
        assertEquals("thread-1", aggregate.threadId)
        assertEquals("fun a() = 2\n", aggregate.parsedDiff.oldContent)
        assertEquals("fun a() = 3\n", aggregate.parsedDiff.newContent)
    }

    @Test
    fun `turn diff remains visible when file change arrives before it`() {
        val store = ComposerAreaStore()
        val workingDir = createTempDirectory("edited-files-diff-priority-first")
        val file = workingDir.resolve("Foo.kt")
        Files.writeString(file, "fun a() = 2\nfun b() = 3\n")

        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.UpsertFileChange(
                    sourceId = "request-1:item-1",
                    title = "File Changes",
                    changes = listOf(
                        TimelineFileChange(
                            sourceScopedId = "request-1:item-1:0",
                            path = file.toString(),
                            displayName = "Foo.kt",
                            kind = TimelineFileChangeKind.UPDATE,
                            timestamp = 10L,
                            oldContent = "fun a() = 1\n",
                            newContent = "fun a() = 2\n",
                            addedLines = 1,
                            deletedLines = 1,
                        ),
                    ),
                    status = ItemStatus.SUCCESS,
                    turnId = "turn-1",
                ),
            ),
        )

        store.onEvent(
            AppEvent.TurnDiffUpdated(
                threadId = "thread-1",
                turnId = "turn-1",
                diff = """
                    diff --git a/${file} b/${file}
                    --- a/${file}
                    +++ b/${file}
                    @@ -1 +1,2 @@
                    -fun a() = 1
                    +fun a() = 2
                    +fun b() = 3
                """.trimIndent(),
            ),
        )

        val aggregate = store.state.value.editedFiles.single()
        assertEquals("turn-1", aggregate.turnId)
        assertEquals(2, aggregate.latestAddedLines)
        assertEquals(1, aggregate.latestDeletedLines)
        assertEquals("fun a() = 2\nfun b() = 3\n", aggregate.parsedDiff.newContent)
        assertEquals("fun a() = 1\n", aggregate.parsedDiff.oldContent)
    }

    @Test
    fun `turn diff remains visible when file change arrives after it`() {
        val store = ComposerAreaStore()
        val workingDir = createTempDirectory("edited-files-diff-priority-second")
        val file = workingDir.resolve("Foo.kt")
        Files.writeString(file, "fun a() = 2\nfun b() = 3\n")

        store.onEvent(
            AppEvent.TurnDiffUpdated(
                threadId = "thread-1",
                turnId = "turn-1",
                diff = """
                    diff --git a/${file} b/${file}
                    --- a/${file}
                    +++ b/${file}
                    @@ -1 +1,2 @@
                    -fun a() = 1
                    +fun a() = 2
                    +fun b() = 3
                """.trimIndent(),
            ),
        )

        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.UpsertFileChange(
                    sourceId = "request-1:item-2",
                    title = "File Changes",
                    changes = listOf(
                        TimelineFileChange(
                            sourceScopedId = "request-1:item-2:0",
                            path = file.toString(),
                            displayName = "Foo.kt",
                            kind = TimelineFileChangeKind.UPDATE,
                            timestamp = 20L,
                            oldContent = "fun a() = 1\n",
                            newContent = "fun a() = 2\n",
                            addedLines = 1,
                            deletedLines = 1,
                        ),
                    ),
                    status = ItemStatus.SUCCESS,
                    turnId = "turn-1",
                ),
            ),
        )

        val aggregate = store.state.value.editedFiles.single()
        assertEquals("turn-1", aggregate.turnId)
        assertEquals(2, aggregate.latestAddedLines)
        assertEquals(1, aggregate.latestDeletedLines)
        assertEquals("fun a() = 2\nfun b() = 3\n", aggregate.parsedDiff.newContent)
        assertEquals("fun a() = 1\n", aggregate.parsedDiff.oldContent)
    }

    @Test
    fun `plan completion event switches composer into native completion state`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.PlanCompletionPromptUpdated(
                PlanCompletionPromptUiModel(
                    turnId = "turn-1",
                    threadId = "thread-1",
                    body = """
                        # Ship plan mode

                        Summary

                        Keep the timeline flat and move plan confirmation into composer.
                    """.trimIndent(),
                    preferredExecutionMode = ComposerMode.APPROVAL,
                ),
            ),
        )

        assertEquals("Ship plan mode", store.state.value.planCompletion?.planTitle)
        assertEquals("", store.state.value.planCompletion?.revisionDraft)
        assertEquals(PlanCompletionAction.EXECUTE, store.state.value.planCompletion?.selectedAction)
    }

    @Test
    fun `running plan updates populate composer state and keep normal composer content available`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.RunningPlanUpdated(
                ComposerRunningPlanState(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    explanation = "Executing plan",
                    steps = listOf(
                        ComposerRunningPlanStep(step = "Inspect logs", status = ComposerRunningPlanStepStatus.COMPLETED),
                        ComposerRunningPlanStep(step = "Update composer", status = ComposerRunningPlanStepStatus.IN_PROGRESS),
                        ComposerRunningPlanStep(step = "Verify tests", status = ComposerRunningPlanStepStatus.PENDING),
                    ),
                ),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.InputChanged("Still editable")))

        val runningPlan = assertNotNull(store.state.value.runningPlan)
        assertEquals("thread-1", runningPlan.threadId)
        assertEquals("turn-1", runningPlan.turnId)
        assertEquals(ComposerRunningPlanStepStatus.IN_PROGRESS, runningPlan.steps[1].status)
        assertEquals("Still editable", store.state.value.inputText)
        assertNull(store.state.value.activeInteractionCard)
        assertTrue(store.state.value.runningPlanExpanded)
    }

    @Test
    fun `running plan toggle updates expanded state and preserves it for same turn`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.RunningPlanUpdated(
                ComposerRunningPlanState(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    explanation = "Executing plan",
                    steps = listOf(
                        ComposerRunningPlanStep(step = "Inspect logs", status = ComposerRunningPlanStepStatus.IN_PROGRESS),
                    ),
                ),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleRunningPlanExpanded))
        assertFalse(store.state.value.runningPlanExpanded)

        store.onEvent(
            AppEvent.RunningPlanUpdated(
                ComposerRunningPlanState(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    explanation = "Executing plan",
                    steps = listOf(
                        ComposerRunningPlanStep(step = "Inspect logs", status = ComposerRunningPlanStepStatus.COMPLETED),
                        ComposerRunningPlanStep(step = "Update composer", status = ComposerRunningPlanStepStatus.IN_PROGRESS),
                    ),
                ),
            ),
        )

        assertFalse(store.state.value.runningPlanExpanded)
        assertEquals(2, store.state.value.runningPlan?.steps?.size)
    }

    @Test
    fun `new running plan turn resets expanded state to default open`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.RunningPlanUpdated(
                ComposerRunningPlanState(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    explanation = "Executing plan",
                    steps = listOf(
                        ComposerRunningPlanStep(step = "Inspect logs", status = ComposerRunningPlanStepStatus.IN_PROGRESS),
                    ),
                ),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleRunningPlanExpanded))
        assertFalse(store.state.value.runningPlanExpanded)

        store.onEvent(
            AppEvent.RunningPlanUpdated(
                ComposerRunningPlanState(
                    threadId = "thread-1",
                    turnId = "turn-2",
                    explanation = "Executing another plan",
                    steps = listOf(
                        ComposerRunningPlanStep(step = "Start over", status = ComposerRunningPlanStepStatus.IN_PROGRESS),
                    ),
                ),
            ),
        )

        assertTrue(store.state.value.runningPlanExpanded)
    }

    @Test
    fun `plan completion clears running plan state`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.RunningPlanUpdated(
                ComposerRunningPlanState(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    explanation = null,
                    steps = listOf(
                        ComposerRunningPlanStep(step = "Inspect logs", status = ComposerRunningPlanStepStatus.IN_PROGRESS),
                    ),
                ),
            ),
        )

        store.onEvent(
            AppEvent.PlanCompletionPromptUpdated(
                PlanCompletionPromptUiModel(
                    turnId = "turn-1",
                    threadId = "thread-1",
                    body = "- [pending] Ship plan mode",
                    preferredExecutionMode = ComposerMode.APPROVAL,
                ),
            ),
        )

        assertNull(store.state.value.runningPlan)
        assertNotNull(store.state.value.planCompletion)
    }

    @Test
    fun `conversation reset clears running plan state`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.RunningPlanUpdated(
                ComposerRunningPlanState(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    explanation = "Executing plan",
                    steps = listOf(
                        ComposerRunningPlanStep(step = "Inspect logs", status = ComposerRunningPlanStepStatus.IN_PROGRESS),
                    ),
                ),
            ),
        )

        store.onEvent(AppEvent.ConversationReset)

        assertNull(store.state.value.runningPlan)
        assertTrue(store.state.value.runningPlanExpanded)
    }

    @Test
    fun `dismissing plan completion keeps current mode and clears completion state`() {
        val store = ComposerAreaStore()
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.TogglePlanMode))
        store.onEvent(
            AppEvent.PlanCompletionPromptUpdated(
                PlanCompletionPromptUiModel(
                    turnId = "turn-1",
                    threadId = "thread-1",
                    body = "- [pending] Ship plan mode",
                    preferredExecutionMode = ComposerMode.APPROVAL,
                ),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.DismissPlanCompletionPrompt))

        assertTrue(store.state.value.planEnabled)
        assertNull(store.state.value.planCompletion)
    }

    @Test
    fun `approval request is surfaced through composer state and clears on resolve`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.ApprovalRequested(
                PendingApprovalRequestUiModel(
                    requestId = "approval-1",
                    turnId = "turn-1",
                    itemId = "item-1",
                    kind = UnifiedApprovalRequestKind.COMMAND,
                    title = "Run command",
                    body = "./gradlew test",
                    command = "./gradlew test",
                    cwd = ".",
                ),
            ),
        )

        assertEquals("approval-1", store.state.value.approvalPrompt?.requestId)

        store.onEvent(AppEvent.ApprovalResolved("approval-1"))

        assertNull(store.state.value.approvalPrompt)
    }

    @Test
    fun `tool user input request is surfaced through composer state and clears on resolve`() {
        val store = ComposerAreaStore()

        store.onEvent(AppEvent.ToolUserInputRequested(toolUserInputPrompt()))

        assertEquals("req-1", store.state.value.toolUserInputPrompt?.requestId)

        store.onEvent(AppEvent.ToolUserInputResolved("req-1"))

        assertNull(store.state.value.toolUserInputPrompt)
    }

    @Test
    fun `composer interaction priority prefers approval over tool input over plan completion`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.PlanCompletionPromptUpdated(
                PlanCompletionPromptUiModel(
                    turnId = "turn-1",
                    threadId = "thread-1",
                    body = "- [pending] Ship plan mode",
                    preferredExecutionMode = ComposerMode.APPROVAL,
                ),
            ),
        )
        store.onEvent(AppEvent.ToolUserInputRequested(toolUserInputPrompt()))
        store.onEvent(
            AppEvent.ApprovalRequested(
                PendingApprovalRequestUiModel(
                    requestId = "approval-1",
                    turnId = "turn-1",
                    itemId = "item-1",
                    kind = UnifiedApprovalRequestKind.COMMAND,
                    title = "Run command",
                    body = "./gradlew test",
                ),
            ),
        )

        val card = store.state.value.activeInteractionCard
        assertNotNull(card)
        assertEquals(ComposerInteractionCardKind.APPROVAL, card.kind)
    }

    @Test
    fun `plan completion selection defaults to execute and rotates with keyboard intents`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.PlanCompletionPromptUpdated(
                PlanCompletionPromptUiModel(
                    turnId = "turn-1",
                    threadId = "thread-1",
                    body = "- [pending] Ship plan mode",
                    preferredExecutionMode = ComposerMode.APPROVAL,
                ),
            ),
        )

        assertEquals(PlanCompletionAction.EXECUTE, store.state.value.planCompletion?.selectedAction)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MovePlanCompletionSelectionNext))
        assertEquals(PlanCompletionAction.CANCEL, store.state.value.planCompletion?.selectedAction)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MovePlanCompletionSelectionNext))
        assertEquals(PlanCompletionAction.REVISION, store.state.value.planCompletion?.selectedAction)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MovePlanCompletionSelectionPrevious))
        assertEquals(PlanCompletionAction.CANCEL, store.state.value.planCompletion?.selectedAction)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MovePlanCompletionSelectionPrevious))
        assertEquals(PlanCompletionAction.EXECUTE, store.state.value.planCompletion?.selectedAction)
    }

    @Test
    fun `settings snapshot syncs custom models into composer state`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = listOf("gpt-4.1-custom"),
                selectedModel = "gpt-4.1-custom",
                selectedReasoning = "high",
            ),
        )

        assertEquals(listOf("gpt-4.1-custom"), store.state.value.customModelIds)
        assertEquals("gpt-4.1-custom", store.state.value.selectedModel)
        assertEquals(com.auracode.assistant.toolwindow.eventing.ComposerReasoning.HIGH, store.state.value.selectedReasoning)
        assertTrue(store.state.value.modelOptions.any { it.id == "gpt-4.1-custom" && it.isCustom })
    }

    @Test
    fun `conversation reset preserves selected model and reasoning restored from settings`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = listOf("gpt-4.1-custom"),
                selectedModel = "gpt-4.1-custom",
                selectedReasoning = "high",
            ),
        )

        store.onEvent(AppEvent.ConversationReset)

        assertEquals("gpt-4.1-custom", store.state.value.selectedModel)
        assertEquals(com.auracode.assistant.toolwindow.eventing.ComposerReasoning.HIGH, store.state.value.selectedReasoning)
    }

    @Test
    fun `settings snapshot restores max reasoning from persisted xhigh effort`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
                selectedReasoning = "xhigh",
            ),
        )

        assertEquals(com.auracode.assistant.toolwindow.eventing.ComposerReasoning.MAX, store.state.value.selectedReasoning)
    }

    @Test
    fun `settings snapshot restores selected agents from persisted ids`() {
        val store = ComposerAreaStore()
        val reviewer = SavedAgentDefinition(
            id = "agent-1",
            name = "Reviewer",
            prompt = "Review the answer before replying.",
        )
        val planner = SavedAgentDefinition(
            id = "agent-2",
            name = "Planner",
            prompt = "Create the execution plan first.",
        )

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = listOf(reviewer, planner),
                selectedAgentIds = listOf("agent-2", "agent-1"),
            ),
        )

        assertEquals(listOf("agent-2", "agent-1"), store.state.value.agentEntries.map { it.id })
        assertEquals(listOf("Planner", "Reviewer"), store.state.value.agentEntries.map { it.name })
    }

    @Test
    fun `conversation reset preserves selected agents restored from settings`() {
        val store = ComposerAreaStore()
        val reviewer = SavedAgentDefinition(
            id = "agent-1",
            name = "Reviewer",
            prompt = "Review the answer before replying.",
        )

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = listOf(reviewer),
                selectedAgentIds = listOf("agent-1"),
            ),
        )

        store.onEvent(AppEvent.ConversationReset)

        assertEquals(listOf("agent-1"), store.state.value.agentEntries.map { it.id })
        assertEquals(listOf("Reviewer"), store.state.value.agentEntries.map { it.name })
    }

    @Test
    fun `conversation reset preserves configuration but restores default mode flags`() {
        val store = ComposerAreaStore()
        val reviewer = SavedAgentDefinition(
            id = "agent-1",
            name = "Reviewer",
            prompt = "Review the answer before replying.",
        )

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = listOf(reviewer),
                selectedAgentIds = listOf("agent-1"),
                customModelIds = listOf("gpt-4.1-custom"),
                selectedModel = "gpt-4.1-custom",
                selectedReasoning = "high",
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.TogglePlanMode))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleExecutionMode))
        store.onEvent(AppEvent.ConversationReset)

        assertEquals("gpt-4.1-custom", store.state.value.selectedModel)
        assertEquals(com.auracode.assistant.toolwindow.eventing.ComposerReasoning.HIGH, store.state.value.selectedReasoning)
        assertEquals(listOf("agent-1"), store.state.value.agentEntries.map { it.id })
        assertTrue(store.state.value.autoContextEnabled)
        assertFalse(store.state.value.planEnabled)
        assertEquals(ComposerMode.AUTO, store.state.value.executionMode)
    }

    @Test
    fun `custom model add state edits and cancels cleanly`() {
        val store = ComposerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.StartAddingCustomModel))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditCustomModelDraft("claude-custom")))

        assertTrue(store.state.value.addingCustomModel)
        assertEquals("claude-custom", store.state.value.customModelDraft)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.CancelAddingCustomModel))

        assertFalse(store.state.value.addingCustomModel)
        assertEquals("", store.state.value.customModelDraft)
    }

    @Test
    fun `missing selected custom model falls back to default on snapshot sync`() {
        val store = ComposerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectModel("gpt-missing")))

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = listOf("gpt-4.1-custom"),
            ),
        )

        assertEquals(CodexModelCatalog.defaultModel, store.state.value.selectedModel)
    }

    @Test
    fun `successful custom model sync closes add state`() {
        val store = ComposerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.StartAddingCustomModel))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditCustomModelDraft("gpt-4.1-custom")))

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = listOf("gpt-4.1-custom"),
            ),
        )

        assertFalse(store.state.value.addingCustomModel)
        assertEquals("", store.state.value.customModelDraft)
    }

    @Test
    fun `pending submissions update syncs queue and can clear current draft`() {
        val store = ComposerAreaStore()
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.InputChanged("queued prompt")))

        store.onEvent(
            AppEvent.PendingSubmissionsUpdated(
                submissions = listOf(
                    PendingComposerSubmission(
                        id = "pending-1",
                        prompt = "queued prompt",
                        systemInstructions = emptyList(),
                        contextFiles = listOf(ContextFile(path = "/tmp/A.kt")),
                        imageAttachments = emptyList(),
                        fileAttachments = emptyList(),
                        stagedAttachments = emptyList(),
                        selectedModel = CodexModelCatalog.defaultModel,
                        selectedReasoning = com.auracode.assistant.toolwindow.eventing.ComposerReasoning.MEDIUM,
                        executionMode = ComposerMode.AUTO,
                        planEnabled = false,
                    ),
                ),
                clearComposerDraft = true,
            ),
        )

        assertEquals(1, store.state.value.pendingSubmissions.size)
        assertEquals("", store.state.value.document.text)
    }

    @Test
    fun `context usage tooltip reflects real snapshot without synthetic empty state copy`() {
        val snapshot = TurnUsageSnapshot(
            model = "gpt-5.3-codex",
            contextWindow = 400_000,
            inputTokens = 0,
            cachedInputTokens = 0,
            outputTokens = 0,
        )

        val tooltip = snapshot.contextUsageTooltipText()

        assertTrue(tooltip.contains("Used 0%"))
        assertTrue(tooltip.contains("0 / 400,000 tokens"))
        assertFalse(tooltip.contains("Estimated from the latest completed turn"))
        assertFalse(tooltip.contains("No completed turn yet"))
    }

    private fun toolUserInputPrompt(): ToolUserInputPromptUiModel {
        return ToolUserInputPromptUiModel(
            requestId = "req-1",
            threadId = "thread-1",
            turnId = "turn-1",
            itemId = "call-1",
            questions = listOf(
                ToolUserInputQuestionUiModel(
                    id = "builder_demo_target",
                    header = "Target",
                    question = "How should I handle the builder demo?",
                    options = listOf(
                        ToolUserInputOptionUiModel(
                            label = "Reuse existing demo",
                            description = "Keep the current file and refine it",
                        ),
                    ),
                    isOther = true,
                ),
            ),
        )
    }
}
