package com.auracode.assistant.toolwindow.eventing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.settings.skills.LocalSkillInstallPolicy
import com.auracode.assistant.settings.skills.RuntimeSkillRecord
import com.auracode.assistant.settings.skills.SkillSelector
import com.auracode.assistant.settings.skills.SkillsManagementAdapter
import com.auracode.assistant.settings.skills.SkillsManagementAdapterRegistry
import com.auracode.assistant.settings.skills.SkillsRuntimeService
import com.auracode.assistant.toolwindow.execution.ApprovalAreaStore
import com.auracode.assistant.toolwindow.submission.ComposerAreaStore
import com.auracode.assistant.toolwindow.shell.RightDrawerAreaStore
import com.auracode.assistant.toolwindow.settings.SettingsSection
import com.auracode.assistant.toolwindow.sessions.HeaderAreaStore
import com.auracode.assistant.toolwindow.execution.StatusAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineAreaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ToolWindowCoordinatorSkillTest {
    private val testDispatcher = Dispatchers.Default.limitedParallelism(1)

    @Test
    fun `submitting prompt with disabled skill token is blocked before agent run`() {
        val workingDir = createTempDirectory("coordinator-skill-submit")
        val settings = AgentSettingsService()
        val service = AgentChatService(
            repository = com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
            registry = registry(),
            settings = settings,
        )
        val eventHub = ToolWindowEventHub()
        val statusStore = StatusAreaStore()
        val runtimeService = SkillsRuntimeService(
            adapterRegistry = SkillsManagementAdapterRegistry(
                adapters = mapOf(
                    "codex" to FakeSkillsManagementAdapter(
                        listOf(
                            RuntimeSkillRecord(
                                name = "brainstorming",
                                description = "Explore requirements.",
                                enabled = false,
                                path = "/runtime/brainstorming/SKILL.md",
                                scopeLabel = "system",
                            ),
                        ),
                    ),
                ),
                defaultEngineId = "codex",
            ),
        )
        kotlinx.coroutines.runBlocking {
            runtimeService.getSkills(engineId = "codex", cwd = ".")
        }
        val composerStore = ComposerAreaStore(
            availableSkillsProvider = {
                runtimeService.enabledSlashSkills(engineId = "codex", cwd = ".")
            },
        )
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = statusStore,
            timelineStore = TimelineAreaStore(),
            composerStore = composerStore,
            rightDrawerStore = RightDrawerAreaStore(),
            approvalStore = ApprovalAreaStore(),
            skillsRuntimeService = runtimeService,
            runStartupWarmups = false,
            scopeDispatcher = testDispatcher,
        )

        composerStore.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("\$brainstorming write tests", TextRange(27))),
            ),
        )

        eventHub.publishUiIntent(UiIntent.SendPrompt)

        waitUntil(2_000) { statusStore.state.value.toast != null }

        assertEquals(
            "Disabled skills cannot be used: brainstorming",
            statusStore.state.value.toast?.text?.let { (it as com.auracode.assistant.toolwindow.shared.UiText.Raw).value },
        )
        assertEquals(null, statusStore.state.value.turnStatus)

        coordinator.dispose()
        service.dispose()
    }

    @Test
    fun `composer slash suggestions only include enabled runtime skills`() {
        val settings = AgentSettingsService()
        val runtimeService = SkillsRuntimeService(
            adapterRegistry = SkillsManagementAdapterRegistry(
                adapters = mapOf(
                    "codex" to FakeSkillsManagementAdapter(
                        listOf(
                            RuntimeSkillRecord(
                                name = "brainstorming",
                                description = "Explore requirements.",
                                enabled = false,
                                path = "/runtime/brainstorming/SKILL.md",
                                scopeLabel = "system",
                            ),
                            RuntimeSkillRecord(
                                name = "systematic-debugging",
                                description = "Debug step by step.",
                                enabled = true,
                                path = "/runtime/systematic-debugging/SKILL.md",
                                scopeLabel = "system",
                            ),
                        ),
                    ),
                ),
                defaultEngineId = "codex",
            ),
        )
        kotlinx.coroutines.runBlocking {
            runtimeService.getSkills(engineId = "codex", cwd = ".")
        }
        val store = ComposerAreaStore(
            availableSkillsProvider = {
                runtimeService.enabledSlashSkills(engineId = "codex", cwd = ".")
            },
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.UpdateDocument(TextFieldValue("/", TextRange(1)))))

        val skillNames = store.state.value.slashSuggestions
            .mapNotNull { (it as? com.auracode.assistant.toolwindow.submission.SlashSuggestionItem.Skill)?.name }

        assertFalse(skillNames.contains("brainstorming"))
        assertTrue(skillNames.contains("systematic-debugging"))
    }

    @Test
    fun `toggle skill intent writes by runtime path`() {
        val workingDir = createTempDirectory("coordinator-skill-toggle")
        val settings = AgentSettingsService()
        val service = AgentChatService(
            repository = com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
            registry = registry(),
            settings = settings,
        )
        val adapter = RecordingSkillsManagementAdapter(
            records = mutableListOf(
                RuntimeSkillRecord(
                    name = "brainstorming",
                    description = "Explore requirements.",
                    enabled = true,
                    path = "/runtime/brainstorming/SKILL.md",
                    scopeLabel = "system",
                ),
            ),
        )
        val eventHub = ToolWindowEventHub()
        val runtimeService = SkillsRuntimeService(
            adapterRegistry = SkillsManagementAdapterRegistry(
                adapters = mapOf("codex" to adapter),
                defaultEngineId = "codex",
            ),
        )
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = TimelineAreaStore(),
            composerStore = ComposerAreaStore(),
            rightDrawerStore = RightDrawerAreaStore(),
            approvalStore = ApprovalAreaStore(),
            skillsRuntimeService = runtimeService,
            runStartupWarmups = false,
            scopeDispatcher = testDispatcher,
        )

        kotlinx.coroutines.runBlocking {
            runtimeService.getSkills(engineId = "codex", cwd = ".")
        }
        adapter.records[0] = adapter.records[0].copy(enabled = false)

        eventHub.publishUiIntent(
            UiIntent.ToggleSkillEnabled(
                name = "brainstorming",
                path = "/runtime/brainstorming/SKILL.md",
                enabled = false,
            ),
        )

        waitUntil(2_000) { adapter.toggleCalls.isNotEmpty() }

        assertEquals(listOf("/runtime/brainstorming/SKILL.md:false"), adapter.toggleCalls)

        coordinator.dispose()
        service.dispose()
    }

    @Test
    fun `selecting skills settings section triggers automatic load`() {
        val workingDir = createTempDirectory("coordinator-skill-section")
        val settings = AgentSettingsService()
        val service = AgentChatService(
            repository = com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
            registry = registry(),
            settings = settings,
        )
        val adapter = RecordingSkillsManagementAdapter(
            records = mutableListOf(
                RuntimeSkillRecord(
                    name = "brainstorming",
                    description = "Explore requirements.",
                    enabled = true,
                    path = "/runtime/brainstorming/SKILL.md",
                    scopeLabel = "system",
                ),
            ),
        )
        val eventHub = ToolWindowEventHub()
        val rightDrawerStore = RightDrawerAreaStore()
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = TimelineAreaStore(),
            composerStore = ComposerAreaStore(),
            rightDrawerStore = rightDrawerStore,
            approvalStore = ApprovalAreaStore(),
            skillsRuntimeService = SkillsRuntimeService(
                adapterRegistry = SkillsManagementAdapterRegistry(
                    adapters = mapOf("codex" to adapter),
                    defaultEngineId = "codex",
                ),
            ),
            runStartupWarmups = false,
            scopeDispatcher = testDispatcher,
        )

        val initialListCalls = adapter.listCalls
        eventHub.publishUiIntent(UiIntent.SelectSettingsSection(SettingsSection.SKILLS))

        waitUntil(5_000) {
            adapter.listCalls > initialListCalls &&
                rightDrawerStore.state.value.settingsSection == SettingsSection.SKILLS &&
                rightDrawerStore.state.value.skillsHasLoadedSnapshot
        }

        assertEquals(SettingsSection.SKILLS, rightDrawerStore.state.value.settingsSection)
        assertTrue(rightDrawerStore.state.value.skillsHasLoadedSnapshot)
        assertEquals(1, rightDrawerStore.state.value.skills.size)

        coordinator.dispose()
        service.dispose()
    }

    @Test
    fun `open and reveal skill intents reuse existing path handlers`() {
        val workingDir = createTempDirectory("coordinator-skill-path-actions")
        val settings = AgentSettingsService()
        val service = AgentChatService(
            repository = com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
            registry = registry(),
            settings = settings,
        )
        val eventHub = ToolWindowEventHub()
        var openedPath: String? = null
        var revealedPath: String? = null
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = TimelineAreaStore(),
            composerStore = ComposerAreaStore(),
            rightDrawerStore = RightDrawerAreaStore(),
            approvalStore = ApprovalAreaStore(),
            openTimelineFilePath = { path -> openedPath = path },
            revealPathInFileManager = { path ->
                revealedPath = path
                true
            },
            runStartupWarmups = false,
            scopeDispatcher = testDispatcher,
        )

        eventHub.publishUiIntent(UiIntent.OpenSkillPath("/tmp/brainstorming/SKILL.md"))
        eventHub.publishUiIntent(UiIntent.RevealSkillPath("/tmp/brainstorming/SKILL.md"))

        waitUntil(2_000) { openedPath != null && revealedPath != null }

        assertEquals("/tmp/brainstorming/SKILL.md", openedPath)
        assertEquals("/tmp/brainstorming/SKILL.md", revealedPath)

        coordinator.dispose()
        service.dispose()
    }

    @Test
    fun `uninstall skill removes local installation and refreshes runtime list`() {
        val home = createTempDirectory("coordinator-skill-uninstall-home")
        val skillDir = home.resolve(".codex/skills/brainstorming").createDirectories()
        val skillFile = skillDir.resolve("SKILL.md").also {
            it.writeText(
                """
                ---
                name: brainstorming
                description: "Explore requirements."
                ---
                """.trimIndent(),
            )
        }
        val workingDir = createTempDirectory("coordinator-skill-uninstall")
        val settings = AgentSettingsService()
        val service = AgentChatService(
            repository = com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
            registry = registry(),
            settings = settings,
        )
        val adapter = RecordingSkillsManagementAdapter(
            records = mutableListOf(
                RuntimeSkillRecord(
                    name = "brainstorming",
                    description = "Explore requirements.",
                    enabled = true,
                    path = skillFile.toString(),
                    scopeLabel = "local",
                ),
            ),
        )
        val eventHub = ToolWindowEventHub()
        val statusStore = StatusAreaStore()
        val rightDrawerStore = RightDrawerAreaStore()
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = statusStore,
            timelineStore = TimelineAreaStore(),
            composerStore = ComposerAreaStore(),
            rightDrawerStore = rightDrawerStore,
            approvalStore = ApprovalAreaStore(),
            skillsRuntimeService = SkillsRuntimeService(
                adapterRegistry = SkillsManagementAdapterRegistry(
                    adapters = mapOf("codex" to adapter),
                    defaultEngineId = "codex",
                ),
            ),
            localSkillInstallPolicy = LocalSkillInstallPolicy(homeDir = home),
            runStartupWarmups = false,
            scopeDispatcher = testDispatcher,
        )

        eventHub.publishUiIntent(UiIntent.SelectSettingsSection(SettingsSection.SKILLS))
        waitUntil(2_000) { rightDrawerStore.state.value.skillsHasLoadedSnapshot }
        adapter.records.clear()

        eventHub.publishUiIntent(UiIntent.UninstallSkill(name = "brainstorming", path = skillFile.toString()))

        waitUntil(2_000) { !skillDir.exists() }
        waitUntil(2_000) { rightDrawerStore.state.value.skills.isEmpty() }

        assertFalse(skillDir.exists())
        assertEquals(
            "Uninstalled local skill 'brainstorming'.",
            (statusStore.state.value.toast?.text as com.auracode.assistant.toolwindow.shared.UiText.Raw).value,
        )

        coordinator.dispose()
        service.dispose()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Condition was not met within ${timeoutMs}ms")
            }
            Thread.sleep(20)
        }
    }

    private fun registry(): ProviderRegistry {
        return ProviderRegistry(
            descriptors = listOf(
                EngineDescriptor(
                    id = "codex",
                    displayName = "Codex",
                    models = listOf("gpt-5.3-codex"),
                    capabilities = EngineCapabilities(
                        supportsThinking = true,
                        supportsToolEvents = true,
                        supportsCommandProposal = false,
                        supportsDiffProposal = false,
                    ),
                ),
            ),
            factories = listOf(
                object : AgentProviderFactory {
                    override val engineId: String = "codex"
                    override fun create(): AgentProvider = object : AgentProvider {
                        override fun stream(request: AgentRequest): Flow<UnifiedEvent> = flow {
                            emit(UnifiedEvent.TurnCompleted(turnId = "turn-1", outcome = TurnOutcome.SUCCESS))
                        }

                        override fun cancel(requestId: String) = Unit
                    }
                },
            ),
            defaultEngineId = "codex",
        )
    }

    private class FakeSkillsManagementAdapter(
        private val records: List<RuntimeSkillRecord>,
    ) : SkillsManagementAdapter {
        override val engineId: String = "codex"

        override fun supportsRuntimeSkills(): Boolean = true

        override suspend fun listRuntimeSkills(cwd: String, forceReload: Boolean): List<RuntimeSkillRecord> = records

        override suspend fun setSkillEnabled(cwd: String, selector: SkillSelector, enabled: Boolean) = Unit
    }

    private class RecordingSkillsManagementAdapter(
        val records: MutableList<RuntimeSkillRecord>,
    ) : SkillsManagementAdapter {
        override val engineId: String = "codex"
        var listCalls: Int = 0
        val toggleCalls = CopyOnWriteArrayList<String>()

        override fun supportsRuntimeSkills(): Boolean = true

        override suspend fun listRuntimeSkills(cwd: String, forceReload: Boolean): List<RuntimeSkillRecord> {
            listCalls += 1
            return records.toList()
        }

        override suspend fun setSkillEnabled(cwd: String, selector: SkillSelector, enabled: Boolean) {
            val path = (selector as SkillSelector.ByPath).path
            toggleCalls += "$path:$enabled"
        }
    }
}
