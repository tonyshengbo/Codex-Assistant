package com.auracode.assistant.service

import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.settings.AgentSettingsService
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentChatServiceEngineSelectionTest {
    @Test
    fun `new session uses default engine from settings-backed registry`() {
        val settings = AgentSettingsService().apply {
            loadState(
                AgentSettingsService.State(
                    defaultEngineId = "claude",
                ),
            )
        }
        val service = createService(settings)

        val sessionId = service.createSession()
        val session = service.listSessions().first { it.id == sessionId }

        assertEquals("claude", session.providerId)
        service.dispose()
    }

    @Test
    fun `empty session can switch engine`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = createService(settings)

        assertTrue(service.canSwitchSessionEngine())
        assertTrue(service.switchCurrentSessionEngine("codex"))
        assertEquals("codex", service.currentSessionEngineId())

        service.dispose()
    }

    @Test
    fun `non empty session cannot switch engine`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = createService(settings)

        service.recordUserMessage(prompt = "hello")

        assertFalse(service.canSwitchSessionEngine())
        assertFalse(service.switchCurrentSessionEngine("codex"))
        assertEquals("claude", service.currentSessionEngineId())

        service.dispose()
    }

    private fun createService(settings: AgentSettingsService): AgentChatService {
        val dbPath = createTempDirectory("chat-service-engine-selection").resolve("chat.db")
        return AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = ProviderRegistry(settings),
            settings = settings,
        )
    }
}
