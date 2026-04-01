package com.auracode.assistant.service

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.settings.AgentSettingsService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentChatServiceEngineErrorPresentationTest {
    @Test
    fun `normalizes engine missing error emitted by provider`() = runBlocking {
        val service = createService(
            provider = ErrorEventProvider(
                UnifiedEvent.Error("""Failed to start provider: Cannot run program "missing-cli": error=2, No such file or directory"""),
            ),
            settings = AgentSettingsService().apply {
                loadState(
                    AgentSettingsService.State(
                        engineExecutablePaths = mutableMapOf("mock-engine" to ""),
                    ),
                )
            },
        )
        val errorMessage = CompletableDeferred<String>()

        service.runAgent(
            engineId = "mock-engine",
            model = "test-model",
            prompt = "hello",
            contextFiles = emptyList(),
            onUnifiedEvent = { event ->
                if (event is UnifiedEvent.Error) {
                    errorMessage.complete(event.message)
                }
            },
        )

        val message = withTimeout(2_000) { errorMessage.await() }
        assertTrue(message.contains("所选引擎不可用"))
        assertTrue(message.contains("请先安装该引擎的 CLI"))
        assertTrue(message.contains("Mock Engine"))
        service.dispose()
    }

    @Test
    fun `normalizes engine missing exception thrown by provider`() = runBlocking {
        val service = createService(
            provider = ThrowingProvider(
                IllegalStateException("""Cannot run program "missing-cli": error=2, No such file or directory"""),
            ),
            settings = AgentSettingsService().apply {
                loadState(
                    AgentSettingsService.State(
                        engineExecutablePaths = mutableMapOf("mock-engine" to "/tmp/missing-cli"),
                    ),
                )
            },
        )
        val errorMessage = CompletableDeferred<String>()

        service.runAgent(
            engineId = "mock-engine",
            model = "test-model",
            prompt = "hello",
            contextFiles = emptyList(),
            onUnifiedEvent = { event ->
                if (event is UnifiedEvent.Error) {
                    errorMessage.complete(event.message)
                }
            },
        )

        val message = withTimeout(2_000) { errorMessage.await() }
        assertTrue(message.contains("所选引擎不可用"))
        assertTrue(message.contains("设置界面配置正确的可执行路径"))
        assertTrue(message.contains("Mock Engine"))
        service.dispose()
    }

    @Test
    fun `passes through unrelated runtime error`() = runBlocking {
        val service = createService(
            provider = ErrorEventProvider(UnifiedEvent.Error("Authentication failed.")),
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
        )
        val errorMessage = CompletableDeferred<String>()

        service.runAgent(
            engineId = "mock-engine",
            model = "test-model",
            prompt = "hello",
            contextFiles = emptyList(),
            onUnifiedEvent = { event ->
                if (event is UnifiedEvent.Error) {
                    errorMessage.complete(event.message)
                }
            },
        )

        assertEquals("Authentication failed.", withTimeout(2_000) { errorMessage.await() })
        service.dispose()
    }

    private fun createService(
        provider: AgentProvider,
        settings: AgentSettingsService,
    ): AgentChatService {
        val dbPath = createTempDirectory("chat-service-engine-error").resolve("chat.db")
        return AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = ProviderRegistry(
                descriptors = listOf(
                    EngineDescriptor(
                        id = "mock-engine",
                        displayName = "Mock Engine",
                        models = listOf("test-model"),
                        capabilities = EngineCapabilities(
                            supportsThinking = false,
                            supportsToolEvents = false,
                            supportsCommandProposal = false,
                            supportsDiffProposal = false,
                        ),
                    ),
                ),
                factories = listOf(
                    object : AgentProviderFactory {
                        override val engineId: String = "mock-engine"

                        override fun create(): AgentProvider = provider
                    },
                ),
                defaultEngineId = "mock-engine",
            ),
            settings = settings,
        )
    }

    private class ErrorEventProvider(
        private val event: UnifiedEvent.Error,
    ) : AgentProvider {
        override fun stream(request: AgentRequest): Flow<UnifiedEvent> = flow {
            emit(event)
        }

        override fun cancel(requestId: String) = Unit
    }

    private class ThrowingProvider(
        private val error: Throwable,
    ) : AgentProvider {
        override fun stream(request: AgentRequest): Flow<UnifiedEvent> = flow {
            throw error
        }

        override fun cancel(requestId: String) = Unit
    }
}
