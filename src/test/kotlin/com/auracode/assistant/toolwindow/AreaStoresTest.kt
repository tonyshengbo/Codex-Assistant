package com.auracode.assistant.toolwindow

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.auracode.assistant.model.TurnUsageSnapshot
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.UiThemeMode
import com.auracode.assistant.settings.SavedAgentDefinition
import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.settings.mcp.McpAuthState
import com.auracode.assistant.settings.mcp.McpBusyState
import com.auracode.assistant.settings.mcp.McpRuntimeStatus
import com.auracode.assistant.settings.mcp.McpServerDraft
import com.auracode.assistant.settings.mcp.McpServerSummary
import com.auracode.assistant.settings.mcp.McpTestResult
import com.auracode.assistant.settings.mcp.McpTransportType
import com.auracode.assistant.settings.mcp.McpValidationErrors
import com.auracode.assistant.settings.skills.SkillRuntimeEntry
import com.auracode.assistant.provider.codex.CodexEnvironmentCheckResult
import com.auracode.assistant.provider.codex.CodexEnvironmentStatus
import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.toolwindow.composer.ComposerAreaStore
import com.auracode.assistant.toolwindow.composer.ContextEntry
import com.auracode.assistant.toolwindow.composer.FocusedContextSnapshot
import com.auracode.assistant.toolwindow.drawer.RightDrawerAreaStore
import com.auracode.assistant.toolwindow.drawer.AgentSettingsPage
import com.auracode.assistant.toolwindow.drawer.McpSettingsPage
import com.auracode.assistant.toolwindow.drawer.RightDrawerKind
import com.auracode.assistant.toolwindow.drawer.SettingsSection
import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.ComposerMode
import com.auracode.assistant.toolwindow.eventing.ComposerReasoning
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.header.HeaderAreaStore
import com.auracode.assistant.toolwindow.status.StatusAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineFileChange
import com.auracode.assistant.toolwindow.timeline.TimelineFileChangeKind
import com.auracode.assistant.toolwindow.timeline.TimelineMutation
import com.auracode.assistant.toolwindow.timeline.TimelineNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AreaStoresTest {
    @Test
    fun `composer store syncs usage snapshot for the active session`() {
        val store = ComposerAreaStore()
        val activeUsage = TurnUsageSnapshot(
            model = "gpt-5.4",
            contextWindow = 100,
            inputTokens = 30,
            cachedInputTokens = 5,
            outputTokens = 20,
        )

        store.onEvent(
            AppEvent.SessionSnapshotUpdated(
                sessions = listOf(
                    AgentChatService.SessionSummary(
                        id = "s1",
                        title = "First",
                        updatedAt = 1L,
                        messageCount = 1,
                        remoteConversationId = "",
                        usageSnapshot = activeUsage,
                    ),
                    AgentChatService.SessionSummary(
                        id = "s2",
                        title = "Second",
                        updatedAt = 2L,
                        messageCount = 1,
                        remoteConversationId = "",
                        usageSnapshot = TurnUsageSnapshot(
                            model = "gpt-5.4",
                            contextWindow = 100,
                            inputTokens = 5,
                            cachedInputTokens = 0,
                            outputTokens = 5,
                        ),
                    ),
                ),
                activeSessionId = "s1",
            ),
        )

        assertEquals(activeUsage, store.state.value.usageSnapshot)
    }

    @Test
    fun `composer store clears usage snapshot when active session has no usage`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.SessionSnapshotUpdated(
                sessions = listOf(
                    AgentChatService.SessionSummary(
                        id = "s1",
                        title = "First",
                        updatedAt = 1L,
                        messageCount = 1,
                        remoteConversationId = "",
                        usageSnapshot = TurnUsageSnapshot(
                            model = "gpt-5.4",
                            contextWindow = 100,
                            inputTokens = 30,
                            cachedInputTokens = 0,
                            outputTokens = 20,
                        ),
                    ),
                ),
                activeSessionId = "s1",
            ),
        )

        store.onEvent(
            AppEvent.SessionSnapshotUpdated(
                sessions = listOf(
                    AgentChatService.SessionSummary(
                        id = "s1",
                        title = "First",
                        updatedAt = 2L,
                        messageCount = 2,
                        remoteConversationId = "",
                        usageSnapshot = null,
                    ),
                ),
                activeSessionId = "s1",
            ),
        )

        assertNull(store.state.value.usageSnapshot)
    }

    @Test
    fun `composer store switches usage snapshot with active session`() {
        val store = ComposerAreaStore()
        val firstUsage = TurnUsageSnapshot(
            model = "gpt-5.4",
            contextWindow = 100,
            inputTokens = 30,
            cachedInputTokens = 0,
            outputTokens = 20,
        )
        val secondUsage = TurnUsageSnapshot(
            model = "gpt-5.4",
            contextWindow = 200,
            inputTokens = 50,
            cachedInputTokens = 10,
            outputTokens = 30,
        )

        store.onEvent(
            AppEvent.SessionSnapshotUpdated(
                sessions = listOf(
                    AgentChatService.SessionSummary(
                        id = "s1",
                        title = "First",
                        updatedAt = 1L,
                        messageCount = 1,
                        remoteConversationId = "",
                        usageSnapshot = firstUsage,
                    ),
                    AgentChatService.SessionSummary(
                        id = "s2",
                        title = "Second",
                        updatedAt = 2L,
                        messageCount = 1,
                        remoteConversationId = "",
                        usageSnapshot = secondUsage,
                    ),
                ),
                activeSessionId = "s1",
            ),
        )
        assertEquals(firstUsage, store.state.value.usageSnapshot)

        store.onEvent(
            AppEvent.SessionSnapshotUpdated(
                sessions = listOf(
                    AgentChatService.SessionSummary(
                        id = "s1",
                        title = "First",
                        updatedAt = 1L,
                        messageCount = 1,
                        remoteConversationId = "",
                        usageSnapshot = firstUsage,
                    ),
                    AgentChatService.SessionSummary(
                        id = "s2",
                        title = "Second",
                        updatedAt = 2L,
                        messageCount = 1,
                        remoteConversationId = "",
                        usageSnapshot = secondUsage,
                    ),
                ),
                activeSessionId = "s2",
            ),
        )

        assertEquals(secondUsage, store.state.value.usageSnapshot)
    }

    @Test
    fun `header store keeps blank title raw so ui can localize fallback at render time`() {
        val store = HeaderAreaStore()

        store.onEvent(
            AppEvent.SessionSnapshotUpdated(
                sessions = listOf(
                    AgentChatService.SessionSummary(
                        id = "s1",
                        title = "",
                        updatedAt = 1L,
                        messageCount = 0,
                        remoteConversationId = "",
                        usageSnapshot = null,
                    ),
                ),
                activeSessionId = "s1",
            ),
        )

        assertEquals("", store.state.value.title)
        assertFalse(store.state.value.canCreateNewSession)
    }

    @Test
    fun `right drawer closes when close intent is published`() {
        val store = RightDrawerAreaStore()
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleHistory))
        assertEquals(RightDrawerKind.HISTORY, store.state.value.kind)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.CloseRightDrawer))

        assertEquals(RightDrawerKind.NONE, store.state.value.kind)
    }

    @Test
    fun `session snapshot keeps right drawer kind unchanged`() {
        val store = RightDrawerAreaStore()
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleHistory))
        assertEquals(RightDrawerKind.HISTORY, store.state.value.kind)

        store.onEvent(
            AppEvent.SessionSnapshotUpdated(
                sessions = listOf(
                    AgentChatService.SessionSummary(
                        id = "s1",
                        title = "t1",
                        updatedAt = 1L,
                        messageCount = 1,
                        remoteConversationId = "",
                        usageSnapshot = null,
                    ),
                ),
                activeSessionId = "s1",
            ),
        )

        assertEquals(RightDrawerKind.HISTORY, store.state.value.kind)
    }

    @Test
    fun `history drawer tracks search query and paged remote conversations`() {
        val store = RightDrawerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditHistorySearchQuery("fix")))
        store.onEvent(
            AppEvent.HistoryConversationsUpdated(
                conversations = listOf(
                    ConversationSummary(
                        remoteConversationId = "thr_1",
                        title = "Fix tests",
                        createdAt = 10L,
                        updatedAt = 20L,
                        status = "idle",
                    ),
                ),
                nextCursor = "next",
                isLoading = false,
                append = false,
            ),
        )

        assertEquals("fix", store.state.value.historyQuery)
        assertEquals(1, store.state.value.historyConversations.size)
        assertEquals("next", store.state.value.historyNextCursor)
    }

    @Test
    fun `settings drawer defaults to general section and switches sections explicitly`() {
        val store = RightDrawerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleSettings))
        assertEquals(RightDrawerKind.SETTINGS, store.state.value.kind)
        assertEquals(SettingsSection.GENERAL, store.state.value.settingsSection)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSettingsSection(SettingsSection.MCP)))

        assertEquals(RightDrawerKind.SETTINGS, store.state.value.kind)
        assertEquals(SettingsSection.MCP, store.state.value.settingsSection)
    }

    @Test
    fun `settings drawer switches to skills section explicitly`() {
        val store = RightDrawerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleSettings))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSettingsSection(SettingsSection.SKILLS)))

        assertEquals(RightDrawerKind.SETTINGS, store.state.value.kind)
        assertEquals(SettingsSection.SKILLS, store.state.value.settingsSection)
    }

    @Test
    fun `skills loading keeps cached list stable and scopes busy state to one row`() {
        val store = RightDrawerAreaStore()
        val skill = SkillRuntimeEntry(
            engineId = "codex",
            cwd = ".",
            name = "brainstorming",
            description = "Explore requirements.",
            enabled = true,
            path = "/runtime/brainstorming/SKILL.md",
            scopeLabel = "user",
        )

        store.onEvent(
            AppEvent.SkillsLoaded(
                engineId = "codex",
                cwd = ".",
                skills = listOf(skill),
                supportsRuntimeSkills = true,
                stale = false,
                errorMessage = null,
            ),
        )
        store.onEvent(AppEvent.SkillsLoadingChanged(loading = true, activePath = skill.path))

        assertTrue(store.state.value.skillsHasLoadedSnapshot)
        assertTrue(store.state.value.skillsLoading)
        assertEquals(skill.path, store.state.value.skillsActiveTogglePath)
        assertEquals(listOf(skill), store.state.value.skills)
    }

    @Test
    fun `settings snapshot updates theme mode and draft changes are stored`() {
        val store = RightDrawerAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                nodePath = "/opt/homebrew/bin/node",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = UiThemeMode.DARK,
                autoContextEnabled = true,
                backgroundCompletionNotificationsEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
            ),
        )
        assertEquals(UiThemeMode.DARK, store.state.value.themeMode)
        assertTrue(store.state.value.autoContextEnabled)
        assertTrue(store.state.value.backgroundCompletionNotificationsEnabled)
        assertEquals("/opt/homebrew/bin/node", store.state.value.nodePath)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditSettingsThemeMode(UiThemeMode.LIGHT)))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditSettingsAutoContextEnabled(false)))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditSettingsBackgroundCompletionNotificationsEnabled(false)))

        assertEquals(UiThemeMode.LIGHT, store.state.value.themeMode)
        assertFalse(store.state.value.autoContextEnabled)
        assertFalse(store.state.value.backgroundCompletionNotificationsEnabled)
    }

    @Test
    fun `agent settings switch between list and editor pages`() {
        val store = RightDrawerAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                nodePath = "",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = UiThemeMode.DARK,
                autoContextEnabled = true,
                savedAgents = listOf(
                    SavedAgentDefinition(id = "a1", name = "Code Review", prompt = "review"),
                ),
                customModelIds = emptyList(),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSettingsSection(SettingsSection.AGENTS)))
        assertEquals(AgentSettingsPage.LIST, store.state.value.agentSettingsPage)
        assertFalse(store.state.value.isAgentEditorDialogVisible)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSavedAgentForEdit("a1")))
        assertEquals(AgentSettingsPage.EDITOR, store.state.value.agentSettingsPage)
        assertEquals("Code Review", store.state.value.agentDraftName)
        assertTrue(store.state.value.isAgentEditorDialogVisible)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ShowAgentSettingsList))
        assertEquals(AgentSettingsPage.LIST, store.state.value.agentSettingsPage)
        assertFalse(store.state.value.isAgentEditorDialogVisible)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.CreateNewAgentDraft))
        assertEquals(AgentSettingsPage.EDITOR, store.state.value.agentSettingsPage)
        assertEquals("", store.state.value.agentDraftName)
        assertTrue(store.state.value.isAgentEditorDialogVisible)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSettingsSection(SettingsSection.GENERAL)))
        assertEquals(AgentSettingsPage.LIST, store.state.value.agentSettingsPage)
        assertFalse(store.state.value.isAgentEditorDialogVisible)
    }

    @Test
    fun `environment detection updates draft paths when requested`() {
        val store = RightDrawerAreaStore()

        store.onEvent(
            AppEvent.CodexEnvironmentCheckUpdated(
                result = CodexEnvironmentCheckResult(
                    codexPath = "/usr/local/bin/codex",
                    nodePath = "/opt/homebrew/bin/node",
                    codexStatus = CodexEnvironmentStatus.DETECTED,
                    nodeStatus = CodexEnvironmentStatus.DETECTED,
                    appServerStatus = CodexEnvironmentStatus.DETECTED,
                    message = "ready",
                ),
                updateDraftPaths = true,
            ),
        )

        assertEquals("/usr/local/bin/codex", store.state.value.codexCliPath)
        assertEquals("/opt/homebrew/bin/node", store.state.value.nodePath)
        assertEquals(CodexEnvironmentStatus.DETECTED, store.state.value.environmentCheckResult?.appServerStatus)
    }

    @Test
    fun `environment detection keeps manual draft edits and still updates untouched fields`() {
        val store = RightDrawerAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                nodePath = "",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = UiThemeMode.DARK,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditSettingsCodexCliPath("/manual/codex")))

        store.onEvent(
            AppEvent.CodexEnvironmentCheckUpdated(
                result = CodexEnvironmentCheckResult(
                    codexPath = "/usr/local/bin/codex",
                    nodePath = "/opt/homebrew/bin/node",
                    codexStatus = CodexEnvironmentStatus.DETECTED,
                    nodeStatus = CodexEnvironmentStatus.DETECTED,
                    appServerStatus = CodexEnvironmentStatus.DETECTED,
                    message = "ready",
                ),
                updateDraftPaths = true,
            ),
        )

        assertEquals("/manual/codex", store.state.value.codexCliPath)
        assertEquals("/opt/homebrew/bin/node", store.state.value.nodePath)
    }

    @Test
    fun `environment save visibility follows draft dirtiness`() {
        val store = RightDrawerAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                nodePath = "",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = UiThemeMode.DARK,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
            ),
        )
        assertFalse(store.state.value.isEnvironmentSaveVisible)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditSettingsNodePath("/opt/homebrew/bin/node")))
        assertTrue(store.state.value.isEnvironmentSaveVisible)

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                nodePath = "/opt/homebrew/bin/node",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = UiThemeMode.DARK,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
            ),
        )
        assertFalse(store.state.value.isEnvironmentSaveVisible)
    }

    @Test
    fun `mcp settings switch between list and editor pages and keep batch draft state`() {
        val store = RightDrawerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSettingsSection(SettingsSection.MCP)))
        assertEquals(McpSettingsPage.LIST, store.state.value.mcpSettingsPage)
        assertFalse(store.state.value.isMcpEditorDialogVisible)

        store.onEvent(
            AppEvent.McpServersLoaded(
                listOf(
                    McpServerSummary(
                        name = "docs",
                        engineId = "codex",
                        transportType = McpTransportType.STDIO,
                        displayTarget = "npx @acme/docs-mcp",
                        authState = McpAuthState.UNSUPPORTED,
                    ),
                ),
            ),
        )
        assertEquals(1, store.state.value.mcpServers.size)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.CreateNewMcpDraft))
        assertEquals(McpSettingsPage.EDITOR, store.state.value.mcpSettingsPage)
        assertTrue(store.state.value.isMcpEditorDialogVisible)
        assertTrue(store.state.value.mcpDraft.configJson.contains("\"mcpServers\""))

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.EditMcpDraftJson(
                    """
                    {
                      "mcpServers": {
                        "docs": {
                          "enabled": true,
                          "url": "https://example.com/mcp"
                        },
                        "figma": {
                          "enabled": true,
                          "command": "npx"
                        }
                      }
                    }
                    """.trimIndent(),
                ),
            ),
        )
        assertTrue(store.state.value.mcpDraft.configJson.contains("https://example.com/mcp"))
        assertTrue(store.state.value.mcpDraft.configJson.contains("\"figma\""))

        store.onEvent(
            AppEvent.McpDraftLoaded(
                McpServerDraft(
                    configJson = """
                        {
                          "mcpServers": {
                            "docs": {
                              "enabled": true,
                              "url": "https://example.com/mcp",
                              "bearer_token_env_var": "DOCS_TOKEN"
                            },
                            "figma": {
                              "enabled": true,
                              "command": "npx"
                            }
                          }
                        }
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(McpSettingsPage.EDITOR, store.state.value.mcpSettingsPage)
        assertNull(store.state.value.editingMcpName)
        assertTrue(store.state.value.mcpDraft.configJson.contains("\"bearer_token_env_var\""))

        store.onEvent(
            AppEvent.McpStatusesUpdated(
                mapOf(
                    "docs" to McpRuntimeStatus(
                        authState = McpAuthState.OAUTH,
                        tools = listOf("search_docs"),
                        resources = listOf("mcp://docs/index"),
                    ),
                ),
            ),
        )
        store.onEvent(
            AppEvent.McpTestResultUpdated(
                name = "docs",
                result = McpTestResult(success = true, summary = "1 tool, 1 resource"),
            ),
        )
        store.onEvent(
            AppEvent.McpBusyStateUpdated(
                McpBusyState(loading = false, testingName = "docs"),
            ),
        )
        store.onEvent(
            AppEvent.McpValidationErrorsUpdated(
                McpValidationErrors(json = "JSON config is required."),
            ),
        )
        store.onEvent(
            AppEvent.McpFeedbackUpdated(
                message = "Saved MCP server 'docs'.",
                isError = false,
            ),
        )

        assertEquals(McpAuthState.OAUTH, store.state.value.mcpStatusByName["docs"]?.authState)
        assertEquals("1 tool, 1 resource", store.state.value.mcpTestResultsByName["docs"]?.summary)
        assertEquals("docs", store.state.value.mcpBusyState.testingName)
        assertEquals("JSON config is required.", store.state.value.mcpValidationErrors.json)
        assertEquals("Saved MCP server 'docs'.", store.state.value.mcpFeedbackMessage)
        assertFalse(store.state.value.mcpFeedbackIsError)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ShowMcpSettingsList))
        assertEquals(McpSettingsPage.LIST, store.state.value.mcpSettingsPage)
        assertFalse(store.state.value.isMcpEditorDialogVisible)
    }

    @Test
    fun `timeline store auto expands running process node and lets user keep it collapsed`() {
        val store = TimelineAreaStore()
        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.TurnStarted(turnId = "turn_1", threadId = "th"),
            ),
        )
        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.UpsertCommand(
                    sourceId = "cmd_1",
                    title = "Exec Command",
                    body = "ls",
                    status = ItemStatus.RUNNING,
                ),
            ),
        )

        val node = assertIs<TimelineNode.CommandNode>(store.state.value.nodes.single())
        assertTrue(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleNodeExpanded(node.id)))
        assertFalse(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.UpsertCommand(
                    sourceId = "cmd_1",
                    title = "Exec Command",
                    body = "ls\npwd",
                    status = ItemStatus.RUNNING,
                ),
            ),
        )

        assertEquals(1, store.state.value.nodes.size)
        assertFalse(store.state.value.expandedNodeIds.contains(node.id))
        assertTrue(store.state.value.renderVersion > 0L)
        assertTrue(store.state.value.isRunning)
    }

    @Test
    fun `timeline store auto collapses process node when it reaches terminal status`() {
        val store = TimelineAreaStore()

        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.TurnStarted(turnId = "turn_1", threadId = "th"),
            ),
        )
        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.UpsertCommand(
                    sourceId = "cmd_1",
                    title = "Exec Command",
                    body = "ls",
                    status = ItemStatus.RUNNING,
                ),
            ),
        )

        val node = assertIs<TimelineNode.CommandNode>(store.state.value.nodes.single())
        assertTrue(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.TurnCompleted(
                    turnId = "turn_1",
                    outcome = com.auracode.assistant.protocol.TurnOutcome.SUCCESS,
                ),
            ),
        )

        assertFalse(store.state.value.expandedNodeIds.contains(node.id))
    }

    @Test
    fun `timeline store expands running process nodes restored from history`() {
        val store = TimelineAreaStore()
        val restored = TimelineNode.CommandNode(
            id = "command:turn_1:cmd_1",
            sourceId = "cmd_1",
            title = "Exec Command",
            body = "ls",
            collapsedSummary = "Working · ls",
            status = ItemStatus.RUNNING,
            turnId = "turn_1",
        )

        store.onEvent(
            AppEvent.TimelineHistoryLoaded(
                nodes = listOf(restored),
                oldestCursor = null,
                hasOlder = false,
                prepend = false,
            ),
        )

        assertTrue(store.state.value.expandedNodeIds.contains(restored.id))
    }

    @Test
    fun `timeline store keeps empty running tool collapsed until detail arrives`() {
        val store = TimelineAreaStore()
        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.TurnStarted(turnId = "turn_1", threadId = "th"),
            ),
        )
        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.UpsertToolCall(
                    sourceId = "web_1",
                    title = "Searching the web",
                    body = "",
                    status = ItemStatus.RUNNING,
                    turnId = "turn_1",
                ),
            ),
        )

        val node = assertIs<TimelineNode.ToolCallNode>(store.state.value.nodes.single())
        assertFalse(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.UpsertToolCall(
                    sourceId = "web_1",
                    title = "Searching the web",
                    body = "Opened openai.com",
                    status = ItemStatus.RUNNING,
                    turnId = "turn_1",
                ),
            ),
        )

        assertTrue(store.state.value.expandedNodeIds.contains(node.id))
    }

    @Test
    fun `timeline store keeps edited node collapsed by default and lets user expand it`() {
        val store = TimelineAreaStore()
        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.TurnStarted(turnId = "turn_1", threadId = "th"),
            ),
        )
        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.UpsertFileChange(
                    sourceId = "diff_1",
                    title = "Edited Main.kt",
                    changes = listOf(
                        TimelineFileChange(
                            sourceScopedId = "diff_1:file_1",
                            path = "src/Main.kt",
                            displayName = "Main.kt",
                            kind = TimelineFileChangeKind.UPDATE,
                        ),
                    ),
                    status = ItemStatus.RUNNING,
                    turnId = "turn_1",
                ),
            ),
        )

        val node = assertIs<TimelineNode.FileChangeNode>(store.state.value.nodes.single())
        assertFalse(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleNodeExpanded(node.id)))
        assertTrue(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleNodeExpanded(node.id)))
        assertFalse(store.state.value.expandedNodeIds.contains(node.id))
    }

    @Test
    fun `timeline store expands plan node by default and lets user collapse it`() {
        val store = TimelineAreaStore()
        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.TurnStarted(turnId = "turn_1", threadId = "th"),
            ),
        )
        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.UpsertPlan(
                    sourceId = "plan_1",
                    title = "Plan",
                    body = "1. Inspect\n2. Fix\n3. Verify",
                    status = ItemStatus.RUNNING,
                    turnId = "turn_1",
                ),
            ),
        )

        val node = assertIs<TimelineNode.PlanNode>(store.state.value.nodes.single())
        assertTrue(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleNodeExpanded(node.id)))
        assertFalse(store.state.value.expandedNodeIds.contains(node.id))
    }

    @Test
    fun `timeline store enters running state immediately after prompt accepted and clears on turn completed`() {
        val store = TimelineAreaStore()

        store.onEvent(AppEvent.PromptAccepted(prompt = "hello", localTurnId = "local-turn-1"))
        assertTrue(store.state.value.isRunning)
        assertEquals(1L, store.state.value.promptScrollRequestVersion)

        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.UpsertMessage(
                    sourceId = "local-user",
                    role = com.auracode.assistant.model.MessageRole.USER,
                    text = "hello",
                    status = ItemStatus.SUCCESS,
                    turnId = "local-turn-1",
                ),
            ),
        )
        assertTrue(store.state.value.isRunning)

        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.TurnCompleted(
                    turnId = "local-turn-1",
                    outcome = com.auracode.assistant.protocol.TurnOutcome.SUCCESS,
                ),
            ),
        )

        assertFalse(store.state.value.isRunning)
    }

    @Test
    fun `timeline store ignores stale completion from previous turn after next prompt is accepted`() {
        val store = TimelineAreaStore()

        store.onEvent(AppEvent.PromptAccepted(prompt = "first", localTurnId = "local-turn-1"))
        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.TurnStarted(turnId = "remote-turn-1", threadId = "thread-1"),
            ),
        )
        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.TurnCompleted(
                    turnId = "remote-turn-1",
                    outcome = com.auracode.assistant.protocol.TurnOutcome.SUCCESS,
                ),
            ),
        )
        assertFalse(store.state.value.isRunning)

        store.onEvent(AppEvent.PromptAccepted(prompt = "second", localTurnId = "local-turn-2"))
        assertTrue(store.state.value.isRunning)
        assertEquals(2L, store.state.value.promptScrollRequestVersion)

        store.onEvent(
            AppEvent.TimelineMutationApplied(
                TimelineMutation.TurnCompleted(
                    turnId = "remote-turn-1",
                    outcome = com.auracode.assistant.protocol.TurnOutcome.SUCCESS,
                ),
            ),
        )

        assertTrue(store.state.value.isRunning)
    }

    @Test
    fun `status store shows turn status only while a turn is active`() {
        val store = StatusAreaStore()

        store.onEvent(AppEvent.PromptAccepted(prompt = "hello", localTurnId = "local-turn-1"))
        assertEquals("status.running", (store.state.value.turnStatus?.label as com.auracode.assistant.toolwindow.shared.UiText.Bundle).key)
        store.onEvent(
            AppEvent.UnifiedEventPublished(
                com.auracode.assistant.protocol.UnifiedEvent.TurnStarted(
                    turnId = "turn_1",
                    threadId = "thread_1",
                ),
            ),
        )

        store.onEvent(
            AppEvent.UnifiedEventPublished(
                com.auracode.assistant.protocol.UnifiedEvent.TurnCompleted(
                    turnId = "turn_1",
                    outcome = com.auracode.assistant.protocol.TurnOutcome.SUCCESS,
                    usage = null,
                ),
            ),
        )

        assertEquals(null, store.state.value.turnStatus)
    }

    @Test
    fun `status store ignores stale completion from previous turn after next prompt starts`() {
        val store = StatusAreaStore()

        store.onEvent(AppEvent.PromptAccepted(prompt = "first", localTurnId = "local-turn-1"))
        store.onEvent(
            AppEvent.UnifiedEventPublished(
                com.auracode.assistant.protocol.UnifiedEvent.TurnStarted(
                    turnId = "remote-turn-1",
                    threadId = "thread-1",
                ),
            ),
        )
        store.onEvent(
            AppEvent.UnifiedEventPublished(
                com.auracode.assistant.protocol.UnifiedEvent.TurnCompleted(
                    turnId = "remote-turn-1",
                    outcome = com.auracode.assistant.protocol.TurnOutcome.SUCCESS,
                    usage = null,
                ),
            ),
        )
        assertEquals(null, store.state.value.turnStatus)

        store.onEvent(AppEvent.PromptAccepted(prompt = "second", localTurnId = "local-turn-2"))
        assertEquals("status.running", (store.state.value.turnStatus?.label as com.auracode.assistant.toolwindow.shared.UiText.Bundle).key)

        store.onEvent(
            AppEvent.UnifiedEventPublished(
                com.auracode.assistant.protocol.UnifiedEvent.TurnCompleted(
                    turnId = "remote-turn-1",
                    outcome = com.auracode.assistant.protocol.TurnOutcome.SUCCESS,
                    usage = null,
                ),
            ),
        )

        assertEquals("status.running", (store.state.value.turnStatus?.label as com.auracode.assistant.toolwindow.shared.UiText.Bundle).key)
    }

    @Test
    fun `status store routes global messages into toast`() {
        val store = StatusAreaStore()

        store.onEvent(AppEvent.StatusTextUpdated(com.auracode.assistant.toolwindow.shared.UiText.raw("Cannot switch tabs while running.")))

        assertEquals("Cannot switch tabs while running.", (store.state.value.toast?.text as com.auracode.assistant.toolwindow.shared.UiText.Raw).value)
        assertEquals(null, store.state.value.turnStatus)
    }

    @Test
    fun `composer store clears input when prompt accepted`() {
        val store = ComposerAreaStore()
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.InputChanged("hello")))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMentionFile(path = "/project/src/App.kt")))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.AddAttachments(listOf("/tmp/screenshot.png"))))
        val attachmentId = store.state.value.attachments.single().id
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.OpenAttachmentPreview(attachmentId)))
        store.onEvent(AppEvent.PromptAccepted(prompt = "hello"))

        assertEquals("", store.state.value.inputText)
        assertTrue(store.state.value.mentionEntries.isEmpty())
        assertTrue(store.state.value.attachments.isEmpty())
        assertEquals(null, store.state.value.previewAttachmentId)
    }

    @Test
    fun `composer store updates popup selections`() {
        val store = ComposerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleReasoningMenu))
        assertTrue(store.state.value.reasoningMenuExpanded)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectReasoning(ComposerReasoning.HIGH)))
        assertEquals(ComposerReasoning.HIGH, store.state.value.selectedReasoning)
        assertFalse(store.state.value.reasoningMenuExpanded)
    }

    @Test
    fun `composer mode options include auto and approval labels`() {
        assertEquals(listOf(ComposerMode.AUTO, ComposerMode.APPROVAL), ComposerMode.entries.toList())
    }

    @Test
    fun `composer store toggles plan independently from execution mode`() {
        val store = ComposerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMode(ComposerMode.APPROVAL)))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.TogglePlanMode))

        assertEquals(ComposerMode.APPROVAL, store.state.value.executionMode)
        assertTrue(store.state.value.planEnabled)
    }

    @Test
    fun `composer store disables plan when plan support is unavailable`() {
        val store = ComposerAreaStore()

        store.onEvent(AppEvent.ConversationCapabilitiesUpdated(ConversationCapabilities(
            supportsStructuredHistory = true,
            supportsHistoryPagination = true,
            supportsPlanMode = true,
            supportsApprovalRequests = true,
            supportsToolUserInput = true,
            supportsResume = true,
            supportsAttachments = true,
            supportsImageInputs = true,
        )))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMode(ComposerMode.APPROVAL)))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.TogglePlanMode))
        store.onEvent(AppEvent.ConversationCapabilitiesUpdated(ConversationCapabilities(
            supportsStructuredHistory = true,
            supportsHistoryPagination = true,
            supportsPlanMode = false,
            supportsApprovalRequests = true,
            supportsToolUserInput = true,
            supportsResume = true,
            supportsAttachments = true,
            supportsImageInputs = true,
        )))

        assertEquals(ComposerMode.APPROVAL, store.state.value.executionMode)
        assertFalse(store.state.value.planEnabled)
        assertFalse(store.state.value.planModeAvailable)
    }

    @Test
    fun `composer store toggles execution mode independently from plan and other menus`() {
        val store = ComposerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleModelMenu))
        assertTrue(store.state.value.modelMenuExpanded)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleExecutionMode))
        assertEquals(ComposerMode.APPROVAL, store.state.value.executionMode)
        assertTrue(store.state.value.modelMenuExpanded)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleReasoningMenu))
        assertTrue(store.state.value.reasoningMenuExpanded)
        assertFalse(store.state.value.modelMenuExpanded)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleExecutionMode))
        assertEquals(ComposerMode.AUTO, store.state.value.executionMode)
    }

    @Test
    fun `composer context entries are deduplicated and capped at ten`() {
        val store = ComposerAreaStore()
        val files = (1..10).map { "/tmp/f$it.kt" }

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.AddContextFiles(files)))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.AddContextFiles(listOf("/tmp/f1.kt"))))

        assertEquals(10, store.state.value.contextEntries.size)
        assertTrue(store.state.value.attachments.isEmpty())
        assertEquals("/tmp/f1.kt", store.state.value.contextEntries.first().path)
        assertEquals("/tmp/f10.kt", store.state.value.contextEntries.last().path)
    }

    @Test
    fun `composer attachments stay in attachment strip and do not leak into context entries`() {
        val store = ComposerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.AddAttachments(listOf("/tmp/design-spec.md"))))

        assertEquals(listOf("/tmp/design-spec.md"), store.state.value.attachments.map { it.path })
        assertTrue(store.state.value.manualContextEntries.isEmpty())
        assertTrue(store.state.value.contextEntries.isEmpty())
    }

    @Test
    fun `focused file updates replace previous focused context entry`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateFocusedContextFile(FocusedContextSnapshot(path = "/tmp/A.kt")),
            ),
        )
        assertEquals(listOf("/tmp/A.kt"), store.state.value.contextEntries.map { it.path })

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateFocusedContextFile(FocusedContextSnapshot(path = "/tmp/B.kt")),
            ),
        )
        assertEquals(listOf("/tmp/B.kt"), store.state.value.contextEntries.map { it.path })
    }

    @Test
    fun `focused file stays ahead of manual entries and can be cleared`() {
        val store = ComposerAreaStore()
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.AddContextFiles(listOf("/tmp/manual.kt"))))
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateFocusedContextFile(FocusedContextSnapshot(path = "/tmp/focused.kt")),
            ),
        )
        assertEquals(listOf("/tmp/focused.kt", "/tmp/manual.kt"), store.state.value.contextEntries.map { it.path })

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.UpdateFocusedContextFile(null)))
        assertEquals(listOf("/tmp/manual.kt"), store.state.value.contextEntries.map { it.path })
    }

    @Test
    fun `selecting mention file stores inline mention and hides mention popup`() {
        val store = ComposerAreaStore()
        val candidate = ContextEntry(path = "/project/src/App.kt", displayName = "App.kt", tailPath = "src")

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.InputChanged("@App")))
        store.onEvent(
            AppEvent.MentionSuggestionsUpdated(
                query = "App",
                documentVersion = store.state.value.documentVersion,
                suggestions = listOf(candidate),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMentionFile(path = candidate.path)))

        assertTrue(store.state.value.contextEntries.isEmpty())
        assertEquals(1, store.state.value.mentionEntries.size)
        assertEquals(candidate.path, store.state.value.mentionEntries.first().path)
        assertFalse(store.state.value.mentionPopupVisible)
        assertEquals("", store.state.value.mentionQuery)
        assertEquals("", store.state.value.inputText)
        assertEquals("@App.kt", store.state.value.document.text)
    }

    @Test
    fun `serialized prompt includes mention paths before user text`() {
        val store = ComposerAreaStore()
        val candidate = ContextEntry(path = "/project/src/App.kt", displayName = "App.kt", tailPath = "src")

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.InputChanged("@App")))
        store.onEvent(
            AppEvent.MentionSuggestionsUpdated(
                query = "App",
                documentVersion = store.state.value.documentVersion,
                suggestions = listOf(candidate),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMentionFile(path = candidate.path)))
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(
                    TextFieldValue("@App.ktexplain this file", TextRange(24)),
                ),
            ),
        )

        assertEquals("/project/src/App.kt\n\nexplain this file", store.state.value.serializedPrompt())
    }

    @Test
    fun `mention keyboard navigation updates active index and closes with escape`() {
        val store = ComposerAreaStore()
        val items = listOf(
            ContextEntry(path = "/project/src/A.kt", displayName = "A.kt", tailPath = "src"),
            ContextEntry(path = "/project/src/B.kt", displayName = "B.kt", tailPath = "src"),
        )

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("@A", TextRange(2))),
            ),
        )
        store.onEvent(
            AppEvent.MentionSuggestionsUpdated(
                query = "A",
                documentVersion = store.state.value.documentVersion,
                suggestions = items,
            ),
        )
        assertEquals(0, store.state.value.activeMentionIndex)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MoveMentionSelectionNext))
        assertEquals(1, store.state.value.activeMentionIndex)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.DismissMentionPopup))
        assertFalse(store.state.value.mentionPopupVisible)
    }

    @Test
    fun `document updates clear mention popup when cursor leaves mention mode`() {
        val store = ComposerAreaStore()
        val candidate = ContextEntry(path = "/project/src/App.kt", displayName = "App.kt", tailPath = "src")

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("@Ap", TextRange(3))),
            ),
        )
        store.onEvent(
            AppEvent.MentionSuggestionsUpdated(
                query = "Ap",
                documentVersion = store.state.value.documentVersion,
                suggestions = listOf(candidate),
            ),
        )

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("hello", TextRange(5))),
            ),
        )

        assertEquals("", store.state.value.mentionQuery)
        assertTrue(store.state.value.mentionSuggestions.isEmpty())
        assertFalse(store.state.value.mentionPopupVisible)
    }

    @Test
    fun `slash popup opens at input start and lists commands plus skills`() {
        val store = ComposerAreaStore(
            availableSkillsProvider = {
                listOf(
                    com.auracode.assistant.toolwindow.composer.SlashSkillDescriptor(
                        name = "brainstorming",
                        description = "Explore requirements before building.",
                    ),
                    com.auracode.assistant.toolwindow.composer.SlashSkillDescriptor(
                        name = "systematic-debugging",
                        description = "Debug unexpected behavior step by step.",
                    ),
                )
            },
        )

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/", TextRange(1))),
            ),
        )

        assertTrue(store.state.value.slashPopupVisible)
        assertEquals("", store.state.value.slashQuery)
        assertEquals(
            listOf("/plan", "/auto", "/new"),
            store.state.value.slashSuggestions.mapNotNull {
                (it as? com.auracode.assistant.toolwindow.composer.SlashSuggestionItem.Command)?.command
            },
        )
        assertTrue(store.state.value.slashSuggestions.any { it is com.auracode.assistant.toolwindow.composer.SlashSuggestionItem.Skill })
    }

    @Test
    fun `slash popup filters plan command by query`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/pl", TextRange(3))),
            ),
        )

        assertTrue(store.state.value.slashPopupVisible)
        assertEquals("pl", store.state.value.slashQuery)
        assertEquals(
            listOf("/plan"),
            store.state.value.slashSuggestions.mapNotNull {
                (it as? com.auracode.assistant.toolwindow.composer.SlashSuggestionItem.Command)?.command
            },
        )
    }

    @Test
    fun `slash popup filters auto command by query`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/au", TextRange(3))),
            ),
        )

        assertTrue(store.state.value.slashPopupVisible)
        assertEquals("au", store.state.value.slashQuery)
        assertEquals(
            listOf("/auto"),
            store.state.value.slashSuggestions.mapNotNull {
                (it as? com.auracode.assistant.toolwindow.composer.SlashSuggestionItem.Command)?.command
            },
        )
    }

    @Test
    fun `slash popup stays closed when slash is not at input start`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("hello /pl", TextRange(9))),
            ),
        )

        assertFalse(store.state.value.slashPopupVisible)
        assertTrue(store.state.value.slashSuggestions.isEmpty())
    }

    @Test
    fun `selecting slash plan enables plan mode and clears trigger text`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/plan", TextRange(5))),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSlashCommand("plan")))

        assertTrue(store.state.value.planEnabled)
        assertEquals("", store.state.value.document.text)
        assertFalse(store.state.value.slashPopupVisible)
    }

    @Test
    fun `selecting slash plan toggles plan mode off when already enabled`() {
        val store = ComposerAreaStore()
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.TogglePlanMode))
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/plan", TextRange(5))),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSlashCommand("plan")))

        assertFalse(store.state.value.planEnabled)
        assertEquals("", store.state.value.document.text)
        assertFalse(store.state.value.slashPopupVisible)
    }

    @Test
    fun `selecting slash auto toggles execution mode and clears trigger text`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/auto", TextRange(5))),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSlashCommand("auto")))

        assertEquals(ComposerMode.APPROVAL, store.state.value.executionMode)
        assertEquals("", store.state.value.document.text)
        assertFalse(store.state.value.slashPopupVisible)

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/auto", TextRange(5))),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSlashCommand("auto")))

        assertEquals(ComposerMode.AUTO, store.state.value.executionMode)
    }

    @Test
    fun `slash command descriptions reflect current toggle state`() {
        val store = ComposerAreaStore()

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/", TextRange(1))),
            ),
        )

        val initialDescriptions = store.state.value.slashSuggestions
            .mapNotNull { suggestion ->
                (suggestion as? com.auracode.assistant.toolwindow.composer.SlashSuggestionItem.Command)
                    ?.let { it.command to it.description }
            }
            .toMap()
        assertEquals("Switch the composer into plan mode.", initialDescriptions["/plan"])
        assertEquals("Switch execution mode to approval.", initialDescriptions["/auto"])
        assertEquals("Start a new session.", initialDescriptions["/new"])

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.TogglePlanMode))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleExecutionMode))
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/", TextRange(1))),
            ),
        )

        val toggledDescriptions = store.state.value.slashSuggestions
            .mapNotNull { suggestion ->
                (suggestion as? com.auracode.assistant.toolwindow.composer.SlashSuggestionItem.Command)
                    ?.let { it.command to it.description }
            }
            .toMap()
        assertEquals("Turn plan mode off.", toggledDescriptions["/plan"])
        assertEquals("Switch execution mode to auto.", toggledDescriptions["/auto"])
    }

    @Test
    fun `selecting slash new clears trigger text without changing local mode state`() {
        val store = ComposerAreaStore()
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.TogglePlanMode))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleExecutionMode))
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/new", TextRange(4))),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSlashCommand("new")))

        assertTrue(store.state.value.planEnabled)
        assertEquals(ComposerMode.APPROVAL, store.state.value.executionMode)
        assertEquals("", store.state.value.document.text)
        assertFalse(store.state.value.slashPopupVisible)
    }

    @Test
    fun `slash popup supports keyboard selection state`() {
        val store = ComposerAreaStore(
            availableSkillsProvider = {
                listOf(
                    com.auracode.assistant.toolwindow.composer.SlashSkillDescriptor(
                        name = "brainstorming",
                        description = "Explore requirements before building.",
                    ),
                )
            },
        )
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/", TextRange(1))),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.MoveSlashSelectionNext))

        assertEquals(1, store.state.value.activeSlashIndex)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.DismissSlashPopup))

        assertFalse(store.state.value.slashPopupVisible)
        assertEquals(0, store.state.value.activeSlashIndex)
    }

    @Test
    fun `stale mention suggestions are ignored after document query changes`() {
        val store = ComposerAreaStore()
        val oldCandidate = ContextEntry(path = "/project/src/App.kt", displayName = "App.kt", tailPath = "src")

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("@Ap", TextRange(3))),
            ),
        )
        store.onEvent(
            AppEvent.MentionSuggestionsUpdated(
                query = "Ap",
                documentVersion = store.state.value.documentVersion,
                suggestions = listOf(oldCandidate),
            ),
        )

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("@App", TextRange(4))),
            ),
        )
        store.onEvent(
            AppEvent.MentionSuggestionsUpdated(
                query = "Ap",
                documentVersion = store.state.value.documentVersion - 1,
                suggestions = listOf(oldCandidate),
            ),
        )

        assertEquals("App", store.state.value.mentionQuery)
        assertTrue(store.state.value.mentionSuggestions.isEmpty())
        assertFalse(store.state.value.mentionPopupVisible)
    }
}
