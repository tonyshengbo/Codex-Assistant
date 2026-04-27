package com.auracode.assistant.toolwindow

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.auracode.assistant.model.TurnUsageSnapshot
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.UiScaleMode
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
import com.auracode.assistant.provider.codex.CodexCliUpgradeSource
import com.auracode.assistant.provider.codex.CodexCliVersionCheckStatus
import com.auracode.assistant.provider.codex.CodexCliVersionSnapshot
import com.auracode.assistant.provider.claude.ClaudeCliVersionCheckStatus
import com.auracode.assistant.provider.claude.ClaudeCliVersionSnapshot
import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.toolwindow.submission.SubmissionAreaStore
import com.auracode.assistant.toolwindow.submission.ContextEntry
import com.auracode.assistant.toolwindow.submission.FocusedContextSnapshot
import com.auracode.assistant.toolwindow.submission.MentionSuggestion
import com.auracode.assistant.toolwindow.shell.SidePanelAreaStore
import com.auracode.assistant.toolwindow.shell.AgentSettingsPage
import com.auracode.assistant.toolwindow.shell.McpSettingsPage
import com.auracode.assistant.toolwindow.shell.SidePanelKind
import com.auracode.assistant.toolwindow.settings.RuntimeSettingsTab
import com.auracode.assistant.toolwindow.settings.SettingsSection
import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.SubmissionMode
import com.auracode.assistant.toolwindow.eventing.SubmissionReasoning
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.sessions.SessionTabsAreaStore
import com.auracode.assistant.toolwindow.execution.ExecutionStatusAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationFileChange
import com.auracode.assistant.toolwindow.conversation.ConversationFileChangeKind
import com.auracode.assistant.toolwindow.conversation.ConversationActivityItem
import com.auracode.assistant.toolwindow.conversation.ConversationRenderCause
import com.auracode.assistant.toolwindow.execution.ExecutionTurnStatusUiState
import com.auracode.assistant.toolwindow.shared.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AreaStoresTest {
    @Test
    fun `composer store syncs usage snapshot for the active session`() {
        val store = SubmissionAreaStore()
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
        val store = SubmissionAreaStore()

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
        val store = SubmissionAreaStore()
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
        val store = SessionTabsAreaStore()

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
    fun `header store exposes active session engine label`() {
        val store = SessionTabsAreaStore()

        store.onEvent(
            AppEvent.SessionSnapshotUpdated(
                sessions = listOf(
                    AgentChatService.SessionSummary(
                        id = "s1",
                        title = "Claude Session",
                        updatedAt = 1L,
                        messageCount = 1,
                        remoteConversationId = "",
                        providerId = "claude",
                    ),
                ),
                activeSessionId = "s1",
            ),
        )

        assertEquals("Claude Session", store.state.value.title)
    }

    @Test
    fun `right drawer closes when close intent is published`() {
        val store = SidePanelAreaStore()
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleHistory))
        assertEquals(SidePanelKind.HISTORY, store.state.value.kind)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.CloseSidePanel))

        assertEquals(SidePanelKind.NONE, store.state.value.kind)
    }

    @Test
    fun `settings snapshot exposes claude cli configuration and save visibility`() {
        val store = SidePanelAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                claudeCliPath = "claude",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
            ),
        )

        assertEquals("claude", store.state.value.claudeCliPath)
        assertFalse(store.state.value.isEnvironmentSaveVisible)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditSettingsClaudeCliPath("custom-claude")))

        assertEquals("custom-claude", store.state.value.claudeCliPath)
        assertTrue(store.state.value.isEnvironmentSaveVisible)
    }

    @Test
    fun `settings save visibility ignores claude composer model changes`() {
        val store = SidePanelAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                claudeCliPath = "claude",
                selectedEngineId = "claude",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = com.auracode.assistant.settings.UiThemeMode.FOLLOW_IDE,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                selectedModel = "claude-opus-4-6",
            ),
        )

        assertFalse(store.state.value.isEnvironmentSaveVisible)
    }

    @Test
    fun `session snapshot keeps right drawer kind unchanged`() {
        val store = SidePanelAreaStore()
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleHistory))
        assertEquals(SidePanelKind.HISTORY, store.state.value.kind)

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

        assertEquals(SidePanelKind.HISTORY, store.state.value.kind)
    }

    @Test
    fun `history drawer tracks search query and paged remote conversations`() {
        val store = SidePanelAreaStore()

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
    fun `settings drawer defaults to basic section and switches sections explicitly`() {
        val store = SidePanelAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleSettings))
        assertEquals(SidePanelKind.SETTINGS, store.state.value.kind)
        assertEquals(SettingsSection.BASIC, store.state.value.settingsSection)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSettingsSection(SettingsSection.MCP)))

        assertEquals(SidePanelKind.SETTINGS, store.state.value.kind)
        assertEquals(SettingsSection.MCP, store.state.value.settingsSection)
    }

    @Test
    fun `runtime tab selection is preserved inside runtime settings`() {
        val store = SidePanelAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSettingsSection(SettingsSection.RUNTIME)))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectRuntimeSettingsTab(RuntimeSettingsTab.CLAUDE)))

        assertEquals(SettingsSection.RUNTIME, store.state.value.settingsSection)
        assertEquals(RuntimeSettingsTab.CLAUDE, store.state.value.runtimeSettingsTab)
    }

    @Test
    fun `settings drawer switches to skills section explicitly`() {
        val store = SidePanelAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleSettings))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSettingsSection(SettingsSection.SKILLS)))

        assertEquals(SidePanelKind.SETTINGS, store.state.value.kind)
        assertEquals(SettingsSection.SKILLS, store.state.value.settingsSection)
    }

    @Test
    fun `skills loading keeps cached list stable and scopes busy state to one row`() {
        val store = SidePanelAreaStore()
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
        val store = SidePanelAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                nodePath = "/opt/homebrew/bin/node",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = UiThemeMode.DARK,
                uiScaleMode = UiScaleMode.P100,
                autoContextEnabled = true,
                backgroundCompletionNotificationsEnabled = true,
                codexCliAutoUpdateCheckEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
            ),
        )
        assertEquals(UiThemeMode.DARK, store.state.value.themeMode)
        assertTrue(store.state.value.autoContextEnabled)
        assertTrue(store.state.value.backgroundCompletionNotificationsEnabled)
        assertEquals("/opt/homebrew/bin/node", store.state.value.nodePath)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditSettingsThemeMode(UiThemeMode.LIGHT)))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditSettingsUiScaleMode(UiScaleMode.P120)))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditSettingsAutoContextEnabled(false)))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditSettingsBackgroundCompletionNotificationsEnabled(false)))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditSettingsCodexCliAutoUpdateCheckEnabled(false)))

        assertEquals(UiThemeMode.LIGHT, store.state.value.themeMode)
        assertEquals(UiScaleMode.P120, store.state.value.uiScaleMode)
        assertFalse(store.state.value.autoContextEnabled)
        assertFalse(store.state.value.backgroundCompletionNotificationsEnabled)
        assertFalse(store.state.value.codexCliAutoUpdateCheckEnabled)
    }

    @Test
    fun `agent settings switch between list and editor pages`() {
        val store = SidePanelAreaStore()

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

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSettingsSection(SettingsSection.BASIC)))
        assertEquals(AgentSettingsPage.LIST, store.state.value.agentSettingsPage)
        assertFalse(store.state.value.isAgentEditorDialogVisible)
    }

    @Test
    fun `environment detection updates draft paths when requested`() {
        val store = SidePanelAreaStore()

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
        val store = SidePanelAreaStore()

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
        val store = SidePanelAreaStore()

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
    fun `settings snapshot carries codex cli version state`() {
        val store = SidePanelAreaStore()
        val snapshot = CodexCliVersionSnapshot(
            checkStatus = CodexCliVersionCheckStatus.UPDATE_AVAILABLE,
            currentVersion = "0.34.0",
            latestVersion = "0.35.0",
            ignoredVersion = "",
            upgradeSource = CodexCliUpgradeSource.NPM,
            displayCommand = "npm install -g @openai/codex@latest",
            lastCheckedAt = 123L,
        )

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                nodePath = "",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = UiThemeMode.DARK,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
                codexCliVersionSnapshot = snapshot,
            ),
        )

        assertEquals(snapshot, store.state.value.codexCliVersionSnapshot)
    }

    @Test
    fun `settings snapshot carries claude cli version state`() {
        val store = SidePanelAreaStore()
        val snapshot = ClaudeCliVersionSnapshot(
            checkStatus = ClaudeCliVersionCheckStatus.UPDATE_AVAILABLE,
            currentVersion = "2.1.74",
            latestVersion = "2.1.119",
            displayCommand = "npm install -g @anthropic-ai/claude-code@latest",
            lastCheckedAt = 456L,
        )

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                claudeCliPath = "claude",
                nodePath = "",
                languageMode = com.auracode.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = UiThemeMode.DARK,
                autoContextEnabled = true,
                savedAgents = emptyList(),
                customModelIds = emptyList(),
                claudeCliVersionSnapshot = snapshot,
            ),
        )

        assertEquals(snapshot, store.state.value.claudeCliVersionSnapshot)
    }

    @Test
    fun `version update event replaces codex cli version state without touching environment draft`() {
        val store = SidePanelAreaStore()
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

        val snapshot = CodexCliVersionSnapshot(
            checkStatus = CodexCliVersionCheckStatus.CHECKING,
            currentVersion = "0.34.0",
            latestVersion = "",
            ignoredVersion = "",
            upgradeSource = CodexCliUpgradeSource.UNKNOWN,
            displayCommand = "npm install -g @openai/codex@latest",
        )
        store.onEvent(AppEvent.CodexCliVersionSnapshotUpdated(snapshot))

        assertEquals(snapshot, store.state.value.codexCliVersionSnapshot)
        assertEquals("codex", store.state.value.codexCliPath)
        assertEquals("/opt/homebrew/bin/node", store.state.value.nodePath)
    }

    @Test
    fun `mcp settings switch between list and editor pages and keep batch draft state`() {
        val store = SidePanelAreaStore()

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
        val store = ConversationAreaStore()
        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(
                    ConversationActivityItem.CommandNode(
                        id = "command:turn_1:cmd_1",
                        sourceId = "cmd_1",
                        title = "Exec Command",
                        body = "ls",
                        collapsedSummary = "Working",
                        status = ItemStatus.RUNNING,
                        turnId = "turn_1",
                    ),
                ),
                oldestCursor = null,
                hasOlder = false,
                isRunning = true,
                latestError = null,
            ),
        )

        val node = assertIs<ConversationActivityItem.CommandNode>(store.state.value.nodes.single())
        assertTrue(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleNodeExpanded(node.id)))
        assertFalse(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(
                    ConversationActivityItem.CommandNode(
                        id = "command:turn_1:cmd_1",
                        sourceId = "cmd_1",
                        title = "Exec Command",
                        body = "ls\npwd",
                        collapsedSummary = "Working",
                        status = ItemStatus.RUNNING,
                        turnId = "turn_1",
                    ),
                ),
                oldestCursor = null,
                hasOlder = false,
                isRunning = true,
                latestError = null,
            ),
        )

        assertEquals(1, store.state.value.nodes.size)
        assertFalse(store.state.value.expandedNodeIds.contains(node.id))
        assertTrue(store.state.value.renderVersion > 0L)
        assertTrue(store.state.value.isRunning)
    }

    @Test
    fun `timeline store auto collapses process node when projected status becomes terminal`() {
        val store = ConversationAreaStore()

        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(
                    ConversationActivityItem.CommandNode(
                        id = "command:turn_1:cmd_1",
                        sourceId = "cmd_1",
                        title = "Exec Command",
                        body = "ls",
                        collapsedSummary = "Working",
                        status = ItemStatus.RUNNING,
                        turnId = "turn_1",
                    ),
                ),
                oldestCursor = null,
                hasOlder = false,
                isRunning = true,
                latestError = null,
            ),
        )

        val node = assertIs<ConversationActivityItem.CommandNode>(store.state.value.nodes.single())
        assertTrue(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(
                    ConversationActivityItem.CommandNode(
                        id = "command:turn_1:cmd_1",
                        sourceId = "cmd_1",
                        title = "Exec Command",
                        body = "ls",
                        collapsedSummary = null,
                        status = ItemStatus.SUCCESS,
                        turnId = "turn_1",
                    ),
                ),
                oldestCursor = null,
                hasOlder = false,
                isRunning = false,
                latestError = null,
            ),
        )

        assertFalse(store.state.value.expandedNodeIds.contains(node.id))
    }

    @Test
    fun `timeline store treats first projection as history reset and expands running process nodes`() {
        val store = ConversationAreaStore()
        val restored = ConversationActivityItem.CommandNode(
            id = "command:turn_1:cmd_1",
            sourceId = "cmd_1",
            title = "Exec Command",
            body = "ls",
            collapsedSummary = "Working · ls",
            status = ItemStatus.RUNNING,
            turnId = "turn_1",
        )

        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(restored),
                oldestCursor = null,
                hasOlder = false,
                isRunning = true,
                latestError = null,
            ),
        )

        assertTrue(store.state.value.expandedNodeIds.contains(restored.id))
        assertEquals(ConversationRenderCause.HISTORY_RESET, store.state.value.renderCause)
    }

    @Test
    fun `timeline store keeps empty running tool collapsed until detail arrives`() {
        val store = ConversationAreaStore()
        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(
                    ConversationActivityItem.ToolCallNode(
                        id = "tool:turn_1:web_1",
                        sourceId = "web_1",
                        title = "Searching the web",
                        body = "",
                        status = ItemStatus.RUNNING,
                        turnId = "turn_1",
                    ),
                ),
                oldestCursor = null,
                hasOlder = false,
                isRunning = true,
                latestError = null,
            ),
        )

        val node = assertIs<ConversationActivityItem.ToolCallNode>(store.state.value.nodes.single())
        assertFalse(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(
                    ConversationActivityItem.ToolCallNode(
                        id = "tool:turn_1:web_1",
                        sourceId = "web_1",
                        title = "Searching the web",
                        body = "Opened openai.com",
                        status = ItemStatus.RUNNING,
                        turnId = "turn_1",
                    ),
                ),
                oldestCursor = null,
                hasOlder = false,
                isRunning = true,
                latestError = null,
            ),
        )

        assertTrue(store.state.value.expandedNodeIds.contains(node.id))
    }

    @Test
    fun `timeline store keeps edited node collapsed by default and lets user expand it`() {
        val store = ConversationAreaStore()
        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(
                    ConversationActivityItem.FileChangeNode(
                        id = "diff:turn_1:diff_1",
                        sourceId = "diff_1",
                        title = "Edited Main.kt",
                        changes = listOf(
                            ConversationFileChange(
                                sourceScopedId = "diff_1:file_1",
                                path = "src/Main.kt",
                                displayName = "Main.kt",
                                kind = ConversationFileChangeKind.UPDATE,
                            ),
                        ),
                        status = ItemStatus.RUNNING,
                        turnId = "turn_1",
                    ),
                ),
                oldestCursor = null,
                hasOlder = false,
                isRunning = true,
                latestError = null,
            ),
        )

        val node = assertIs<ConversationActivityItem.FileChangeNode>(store.state.value.nodes.single())
        assertFalse(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleNodeExpanded(node.id)))
        assertTrue(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleNodeExpanded(node.id)))
        assertFalse(store.state.value.expandedNodeIds.contains(node.id))
    }

    @Test
    fun `timeline store expands plan node by default and lets user collapse it`() {
        val store = ConversationAreaStore()
        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(
                    ConversationActivityItem.PlanNode(
                        id = "plan:turn_1:plan_1",
                        sourceId = "plan_1",
                        title = "Plan",
                        body = "1. Inspect\n2. Fix\n3. Verify",
                        status = ItemStatus.RUNNING,
                        turnId = "turn_1",
                    ),
                ),
                oldestCursor = null,
                hasOlder = false,
                isRunning = true,
                latestError = null,
            ),
        )

        val node = assertIs<ConversationActivityItem.PlanNode>(store.state.value.nodes.single())
        assertTrue(store.state.value.expandedNodeIds.contains(node.id))

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleNodeExpanded(node.id)))
        assertFalse(store.state.value.expandedNodeIds.contains(node.id))
    }

    @Test
    fun `timeline store enters running state immediately after prompt accepted and clears on projection completion`() {
        val store = ConversationAreaStore()

        store.onEvent(AppEvent.PromptAccepted(prompt = "hello", localTurnId = "local-turn-1"))
        assertTrue(store.state.value.isRunning)
        assertEquals(1L, store.state.value.promptScrollRequestVersion)

        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(
                    ConversationActivityItem.MessageNode(
                        id = "message:local-turn-1:local-user",
                        sourceId = "local-user",
                        role = com.auracode.assistant.model.MessageRole.USER,
                        text = "hello",
                        status = ItemStatus.SUCCESS,
                        timestamp = null,
                        turnId = "local-turn-1",
                        cursor = null,
                    ),
                ),
                oldestCursor = null,
                hasOlder = false,
                isRunning = true,
                latestError = null,
            ),
        )
        assertTrue(store.state.value.isRunning)

        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(
                    ConversationActivityItem.MessageNode(
                        id = "message:local-turn-1:local-user",
                        sourceId = "local-user",
                        role = com.auracode.assistant.model.MessageRole.USER,
                        text = "hello",
                        status = ItemStatus.SUCCESS,
                        timestamp = null,
                        turnId = "local-turn-1",
                        cursor = null,
                    ),
                ),
                oldestCursor = null,
                hasOlder = false,
                isRunning = false,
                latestError = null,
            ),
        )

        assertFalse(store.state.value.isRunning)
    }

    @Test
    fun `timeline store marks older-message projection updates as history prepend`() {
        val store = ConversationAreaStore()
        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(
                    ConversationActivityItem.MessageNode(
                        id = "message:turn_1:assistant_1",
                        sourceId = "assistant_1",
                        role = com.auracode.assistant.model.MessageRole.ASSISTANT,
                        text = "newest",
                        status = ItemStatus.SUCCESS,
                        timestamp = null,
                        turnId = "turn_1",
                        cursor = null,
                    ),
                ),
                oldestCursor = "cursor-1",
                hasOlder = true,
                isRunning = false,
                latestError = null,
            ),
        )
        store.onEvent(AppEvent.ConversationOlderLoadingChanged(loading = true))
        store.onEvent(
            AppEvent.ConversationUiProjectionUpdated(
                nodes = listOf(
                    ConversationActivityItem.MessageNode(
                        id = "message:turn_0:assistant_0",
                        sourceId = "assistant_0",
                        role = com.auracode.assistant.model.MessageRole.ASSISTANT,
                        text = "older",
                        status = ItemStatus.SUCCESS,
                        timestamp = null,
                        turnId = "turn_0",
                        cursor = null,
                    ),
                    ConversationActivityItem.MessageNode(
                        id = "message:turn_1:assistant_1",
                        sourceId = "assistant_1",
                        role = com.auracode.assistant.model.MessageRole.ASSISTANT,
                        text = "newest",
                        status = ItemStatus.SUCCESS,
                        timestamp = null,
                        turnId = "turn_1",
                        cursor = null,
                    ),
                ),
                oldestCursor = "cursor-0",
                hasOlder = false,
                isRunning = false,
                latestError = null,
            ),
        )

        assertEquals(ConversationRenderCause.HISTORY_PREPEND, store.state.value.renderCause)
        assertEquals(1, store.state.value.prependedCount)
        assertFalse(store.state.value.isLoadingOlder)
    }

    @Test
    fun `status store shows turn status only while execution projection reports an active turn`() {
        val store = ExecutionStatusAreaStore()

        store.onEvent(AppEvent.PromptAccepted(prompt = "hello", localTurnId = "local-turn-1"))
        assertEquals("status.running", (store.state.value.turnStatus?.label as UiText.Bundle).key)
        store.onEvent(
            AppEvent.ExecutionUiProjectionUpdated(
                approvals = emptyList(),
                toolUserInputs = emptyList(),
                turnStatus = ExecutionTurnStatusUiState(
                    label = UiText.Bundle("status.running"),
                    startedAtMs = 1L,
                    turnId = "turn_1",
                ),
            ),
        )

        store.onEvent(
            AppEvent.ExecutionUiProjectionUpdated(
                approvals = emptyList(),
                toolUserInputs = emptyList(),
                turnStatus = null,
            ),
        )

        assertEquals(null, store.state.value.turnStatus)
    }

    @Test
    fun `status store routes global messages into toast`() {
        val store = ExecutionStatusAreaStore()

        store.onEvent(AppEvent.StatusTextUpdated(com.auracode.assistant.toolwindow.shared.UiText.raw("Cannot switch tabs while running.")))

        assertEquals("Cannot switch tabs while running.", (store.state.value.toast?.text as com.auracode.assistant.toolwindow.shared.UiText.Raw).value)
        assertEquals(null, store.state.value.turnStatus)
    }

    @Test
    fun `composer store clears input when prompt accepted`() {
        val store = SubmissionAreaStore()
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
        val store = SubmissionAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleReasoningMenu))
        assertTrue(store.state.value.reasoningMenuExpanded)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectReasoning(SubmissionReasoning.HIGH)))
        assertEquals(SubmissionReasoning.HIGH, store.state.value.selectedReasoning)
        assertFalse(store.state.value.reasoningMenuExpanded)
    }

    @Test
    fun `composer mode options include auto and approval labels`() {
        assertEquals(listOf(SubmissionMode.AUTO, SubmissionMode.APPROVAL), SubmissionMode.entries.toList())
    }

    @Test
    fun `composer store toggles plan independently from execution mode`() {
        val store = SubmissionAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMode(SubmissionMode.APPROVAL)))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.TogglePlanMode))

        assertEquals(SubmissionMode.APPROVAL, store.state.value.executionMode)
        assertTrue(store.state.value.planEnabled)
    }

    @Test
    fun `composer store disables plan when plan support is unavailable`() {
        val store = SubmissionAreaStore()

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
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMode(SubmissionMode.APPROVAL)))
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

        assertEquals(SubmissionMode.APPROVAL, store.state.value.executionMode)
        assertFalse(store.state.value.planEnabled)
        assertFalse(store.state.value.planModeAvailable)
    }

    @Test
    fun `composer store toggles execution mode independently from plan and other menus`() {
        val store = SubmissionAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleModelMenu))
        assertTrue(store.state.value.modelMenuExpanded)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleExecutionMode))
        assertEquals(SubmissionMode.APPROVAL, store.state.value.executionMode)
        assertTrue(store.state.value.modelMenuExpanded)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleReasoningMenu))
        assertTrue(store.state.value.reasoningMenuExpanded)
        assertFalse(store.state.value.modelMenuExpanded)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleExecutionMode))
        assertEquals(SubmissionMode.AUTO, store.state.value.executionMode)
    }

    @Test
    fun `composer context entries are deduplicated and capped at ten`() {
        val store = SubmissionAreaStore()
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
        val store = SubmissionAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.AddAttachments(listOf("/tmp/design-spec.md"))))

        assertEquals(listOf("/tmp/design-spec.md"), store.state.value.attachments.map { it.path })
        assertTrue(store.state.value.manualContextEntries.isEmpty())
        assertTrue(store.state.value.contextEntries.isEmpty())
    }

    @Test
    fun `focused file updates replace previous focused context entry`() {
        val store = SubmissionAreaStore()
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
        val store = SubmissionAreaStore()
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
        val store = SubmissionAreaStore()
        val candidate = ContextEntry(path = "/project/src/App.kt", displayName = "App.kt", tailPath = "src")

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.InputChanged("@App")))
        store.onEvent(
            AppEvent.MentionSuggestionsUpdated(
                query = "App",
                documentVersion = store.state.value.documentVersion,
                suggestions = listOf(MentionSuggestion.File(candidate)),
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
        val store = SubmissionAreaStore()
        val candidate = ContextEntry(path = "/project/src/App.kt", displayName = "App.kt", tailPath = "src")

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.InputChanged("@App")))
        store.onEvent(
            AppEvent.MentionSuggestionsUpdated(
                query = "App",
                documentVersion = store.state.value.documentVersion,
                suggestions = listOf(MentionSuggestion.File(candidate)),
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
        val store = SubmissionAreaStore()
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
                suggestions = items.map(MentionSuggestion::File),
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
        val store = SubmissionAreaStore()
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
                suggestions = listOf(MentionSuggestion.File(candidate)),
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
        val store = SubmissionAreaStore(
            availableSkillsProvider = {
                listOf(
                    com.auracode.assistant.toolwindow.submission.SlashSkillDescriptor(
                        name = "brainstorming",
                        description = "Explore requirements before building.",
                    ),
                    com.auracode.assistant.toolwindow.submission.SlashSkillDescriptor(
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
                (it as? com.auracode.assistant.toolwindow.submission.SlashSuggestionItem.Command)?.command
            },
        )
        assertTrue(store.state.value.slashSuggestions.any { it is com.auracode.assistant.toolwindow.submission.SlashSuggestionItem.Skill })
    }

    @Test
    fun `slash popup filters plan command by query`() {
        val store = SubmissionAreaStore()

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
                (it as? com.auracode.assistant.toolwindow.submission.SlashSuggestionItem.Command)?.command
            },
        )
    }

    @Test
    fun `slash popup filters auto command by query`() {
        val store = SubmissionAreaStore()

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
                (it as? com.auracode.assistant.toolwindow.submission.SlashSuggestionItem.Command)?.command
            },
        )
    }

    @Test
    fun `slash popup stays closed when slash is not at input start`() {
        val store = SubmissionAreaStore()

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
        val store = SubmissionAreaStore()
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
        val store = SubmissionAreaStore()
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
        val store = SubmissionAreaStore()
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/auto", TextRange(5))),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSlashCommand("auto")))

        assertEquals(SubmissionMode.APPROVAL, store.state.value.executionMode)
        assertEquals("", store.state.value.document.text)
        assertFalse(store.state.value.slashPopupVisible)

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/auto", TextRange(5))),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSlashCommand("auto")))

        assertEquals(SubmissionMode.AUTO, store.state.value.executionMode)
    }

    @Test
    fun `slash command descriptions reflect current toggle state`() {
        val store = SubmissionAreaStore()

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/", TextRange(1))),
            ),
        )

        val initialDescriptions = store.state.value.slashSuggestions
            .mapNotNull { suggestion ->
                (suggestion as? com.auracode.assistant.toolwindow.submission.SlashSuggestionItem.Command)
                    ?.let { it.command to it.description }
            }
            .toMap()
        assertEquals("Switch the composer into plan mode.", initialDescriptions["/plan"])
        assertEquals("Switch execution mode to approval with a workspace-write sandbox.", initialDescriptions["/auto"])
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
                (suggestion as? com.auracode.assistant.toolwindow.submission.SlashSuggestionItem.Command)
                    ?.let { it.command to it.description }
            }
            .toMap()
        assertEquals("Turn plan mode off.", toggledDescriptions["/plan"])
        assertEquals("Switch execution mode to auto.", toggledDescriptions["/auto"])
    }

    @Test
    fun `selecting slash new clears trigger text without changing local mode state`() {
        val store = SubmissionAreaStore()
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.TogglePlanMode))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleExecutionMode))
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/new", TextRange(4))),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSlashCommand("new")))

        assertTrue(store.state.value.planEnabled)
        assertEquals(SubmissionMode.APPROVAL, store.state.value.executionMode)
        assertEquals("", store.state.value.document.text)
        assertFalse(store.state.value.slashPopupVisible)
    }

    @Test
    fun `slash popup supports keyboard selection state`() {
        val store = SubmissionAreaStore(
            availableSkillsProvider = {
                listOf(
                    com.auracode.assistant.toolwindow.submission.SlashSkillDescriptor(
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
        val store = SubmissionAreaStore()
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
                suggestions = listOf(MentionSuggestion.File(oldCandidate)),
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
                suggestions = listOf(MentionSuggestion.File(oldCandidate)),
            ),
        )

        assertEquals("App", store.state.value.mentionQuery)
        assertTrue(store.state.value.mentionSuggestions.isEmpty())
        assertFalse(store.state.value.mentionPopupVisible)
    }
}
