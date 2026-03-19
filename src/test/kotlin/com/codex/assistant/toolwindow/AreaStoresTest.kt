package com.codex.assistant.toolwindow

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.codex.assistant.protocol.ItemStatus
import com.codex.assistant.service.AgentChatService
import com.codex.assistant.settings.UiThemeMode
import com.codex.assistant.settings.SavedAgentDefinition
import com.codex.assistant.toolwindow.composer.ComposerAreaStore
import com.codex.assistant.toolwindow.composer.ContextEntry
import com.codex.assistant.toolwindow.drawer.RightDrawerAreaStore
import com.codex.assistant.toolwindow.drawer.AgentSettingsPage
import com.codex.assistant.toolwindow.drawer.RightDrawerKind
import com.codex.assistant.toolwindow.drawer.SettingsSection
import com.codex.assistant.toolwindow.eventing.AppEvent
import com.codex.assistant.toolwindow.eventing.ComposerMode
import com.codex.assistant.toolwindow.eventing.ComposerReasoning
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.header.HeaderAreaStore
import com.codex.assistant.toolwindow.status.StatusAreaStore
import com.codex.assistant.toolwindow.timeline.TimelineActivityKind
import com.codex.assistant.toolwindow.timeline.TimelineAreaStore
import com.codex.assistant.toolwindow.timeline.TimelineMutation
import com.codex.assistant.toolwindow.timeline.TimelineNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AreaStoresTest {
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
                    ),
                ),
                activeSessionId = "s1",
            ),
        )

        assertEquals(RightDrawerKind.HISTORY, store.state.value.kind)
    }

    @Test
    fun `settings drawer defaults to general section and switches sections explicitly`() {
        val store = RightDrawerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleSettings))
        assertEquals(RightDrawerKind.SETTINGS, store.state.value.kind)
        assertEquals(SettingsSection.GENERAL, store.state.value.settingsSection)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSettingsSection(SettingsSection.MODEL_PROVIDERS)))

        assertEquals(RightDrawerKind.SETTINGS, store.state.value.kind)
        assertEquals(SettingsSection.MODEL_PROVIDERS, store.state.value.settingsSection)
    }

    @Test
    fun `settings snapshot updates theme mode and draft changes are stored`() {
        val store = RightDrawerAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                languageMode = com.codex.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = UiThemeMode.DARK,
                savedAgents = emptyList(),
            ),
        )
        assertEquals(UiThemeMode.DARK, store.state.value.themeMode)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.EditSettingsThemeMode(UiThemeMode.LIGHT)))

        assertEquals(UiThemeMode.LIGHT, store.state.value.themeMode)
    }

    @Test
    fun `agent settings switch between list and editor pages`() {
        val store = RightDrawerAreaStore()

        store.onEvent(
            AppEvent.SettingsSnapshotUpdated(
                codexCliPath = "codex",
                languageMode = com.codex.assistant.settings.UiLanguageMode.FOLLOW_IDE,
                themeMode = UiThemeMode.DARK,
                savedAgents = listOf(
                    SavedAgentDefinition(id = "a1", name = "Code Review", prompt = "review"),
                ),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSettingsSection(SettingsSection.AGENTS)))
        assertEquals(AgentSettingsPage.LIST, store.state.value.agentSettingsPage)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSavedAgentForEdit("a1")))
        assertEquals(AgentSettingsPage.EDITOR, store.state.value.agentSettingsPage)
        assertEquals("Code Review", store.state.value.agentDraftName)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ShowAgentSettingsList))
        assertEquals(AgentSettingsPage.LIST, store.state.value.agentSettingsPage)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.CreateNewAgentDraft))
        assertEquals(AgentSettingsPage.EDITOR, store.state.value.agentSettingsPage)
        assertEquals("", store.state.value.agentDraftName)
    }

    @Test
    fun `timeline store updates flat node list and expansion state from events`() {
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

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleNodeExpanded("cmd_1")))

        assertEquals(1, store.state.value.nodes.size)
        assertIs<TimelineNode.CommandNode>(store.state.value.nodes.single())
        assertTrue(store.state.value.expandedNodeIds.contains("cmd_1"))
        assertTrue(store.state.value.renderVersion > 0L)
        assertTrue(store.state.value.isRunning)
    }

    @Test
    fun `status store shows turn status only while a turn is active`() {
        val store = StatusAreaStore()

        store.onEvent(AppEvent.PromptAccepted(prompt = "hello"))
        assertEquals("status.running", (store.state.value.turnStatus?.label as com.codex.assistant.toolwindow.shared.UiText.Bundle).key)

        store.onEvent(
            AppEvent.UnifiedEventPublished(
                com.codex.assistant.protocol.UnifiedEvent.TurnCompleted(
                    turnId = "turn_1",
                    outcome = com.codex.assistant.protocol.TurnOutcome.SUCCESS,
                    usage = null,
                ),
            ),
        )

        assertEquals(null, store.state.value.turnStatus)
    }

    @Test
    fun `status store routes global messages into toast`() {
        val store = StatusAreaStore()

        store.onEvent(AppEvent.StatusTextUpdated(com.codex.assistant.toolwindow.shared.UiText.raw("Cannot switch tabs while running.")))

        assertEquals("Cannot switch tabs while running.", (store.state.value.toast?.text as com.codex.assistant.toolwindow.shared.UiText.Raw).value)
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
    fun `composer store selects approval mode and closes popup`() {
        val store = ComposerAreaStore()

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.ToggleModeMenu))
        assertTrue(store.state.value.modeMenuExpanded)

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMode(ComposerMode.APPROVAL)))
        assertEquals(ComposerMode.APPROVAL, store.state.value.selectedMode)
        assertFalse(store.state.value.modeMenuExpanded)
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
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.UpdateFocusedContextFile("/tmp/A.kt")))
        assertEquals(listOf("/tmp/A.kt"), store.state.value.contextEntries.map { it.path })

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.UpdateFocusedContextFile("/tmp/B.kt")))
        assertEquals(listOf("/tmp/B.kt"), store.state.value.contextEntries.map { it.path })
    }

    @Test
    fun `focused file stays ahead of manual entries and can be cleared`() {
        val store = ComposerAreaStore()
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.AddContextFiles(listOf("/tmp/manual.kt"))))
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.UpdateFocusedContextFile("/tmp/focused.kt")))
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
