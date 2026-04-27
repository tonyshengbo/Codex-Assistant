package com.auracode.assistant.provider.session

import com.auracode.assistant.protocol.ProviderAgentSnapshot
import com.auracode.assistant.protocol.ProviderAgentStatus
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderItem
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.ProviderPlanStep
import com.auracode.assistant.protocol.ProviderRunningPlanPresentation
import com.auracode.assistant.protocol.ProviderToolUserInputPrompt
import com.auracode.assistant.protocol.ProviderToolUserInputQuestion
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.session.kernel.SessionRunningPlanPresentation
import com.auracode.assistant.session.kernel.SessionSubagentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies that provider protocol normalization preserves edited-file and retry semantics.
 */
class ProviderProtocolDomainMapperTest {
    /**
     * Verifies that turn diff updates are normalized into one kernel edited-file snapshot event.
     */
    @Test
    fun `maps turn diff updated into edited file snapshot event`() {
        val mapper = ProviderProtocolDomainMapper(clock = { 42L })

        val event = assertIs<SessionDomainEvent.EditedFilesTracked>(
            mapper.map(
                ProviderEvent.TurnDiffUpdated(
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
            ).single(),
        )

        assertEquals("thread-1", event.threadId)
        assertEquals("turn-1", event.turnId)
        assertEquals(1, event.changes.size)
        assertEquals("/tmp/Foo.kt", event.changes.single().path)
        assertEquals(2, event.changes.single().addedLines)
        assertEquals(1, event.changes.single().deletedLines)
        assertEquals(42L, event.changes.single().updatedAtMs)
    }

    /**
     * Verifies that EOF newline markers survive normalization for submission-side diff previews.
     */
    @Test
    fun `maps turn diff updated preserving missing old trailing newline`() {
        val mapper = ProviderProtocolDomainMapper(clock = { 42L })

        val event = assertIs<SessionDomainEvent.EditedFilesTracked>(
            mapper.map(
                ProviderEvent.TurnDiffUpdated(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    diff = """
                        diff --git a//tmp/Foo.kt b//tmp/Foo.kt
                        --- a//tmp/Foo.kt
                        +++ b//tmp/Foo.kt
                        @@ -1 +1 @@
                        -fun a() = 1
                        \ No newline at end of file
                        +fun a() = 2
                    """.trimIndent(),
                ),
            ).single(),
        )

        assertEquals("fun a() = 1", event.changes.single().oldContent)
        assertEquals("fun a() = 2\n", event.changes.single().newContent)
    }

    /**
     * Verifies that non-terminal provider errors still append a conversation error without ending the active turn.
     */
    @Test
    fun `maps non terminal error preserving retryable state`() {
        val mapper = ProviderProtocolDomainMapper(clock = { 42L })

        mapper.map(
            ProviderEvent.TurnStarted(
                turnId = "turn-1",
                threadId = "thread-1",
            ),
        )
        val event = assertIs<SessionDomainEvent.ErrorAppended>(
            mapper.map(
                ProviderEvent.Error(
                    message = "Reconnecting... 3/5",
                    terminal = false,
                ),
            ).single(),
        )

        assertEquals("turn-1", event.turnId)
        assertEquals("Reconnecting... 3/5", event.message)
        assertTrue(!event.terminal)
    }

    /**
     * Verifies that separate mapper instances still allocate unique error entry ids.
     */
    @Test
    fun `maps errors with globally unique ids across mapper instances`() {
        val firstMapper = ProviderProtocolDomainMapper(clock = { 42L })
        val secondMapper = ProviderProtocolDomainMapper(clock = { 42L })

        val firstEvent = assertIs<SessionDomainEvent.ErrorAppended>(
            firstMapper.map(
                ProviderEvent.Error(
                    message = "Reconnecting",
                    terminal = false,
                ),
            ).single(),
        )
        val secondEvent = assertIs<SessionDomainEvent.ErrorAppended>(
            secondMapper.map(
                ProviderEvent.Error(
                    message = "Reconnecting",
                    terminal = false,
                ),
            ).single(),
        )

        assertTrue(firstEvent.itemId.startsWith("session-error:"))
        assertTrue(secondEvent.itemId.startsWith("session-error:"))
        assertNotEquals(firstEvent.itemId, secondEvent.itemId)
    }

    /**
     * Verifies that provider token usage updates stay on the domain ingress path.
     */
    @Test
    fun `maps thread token usage updated into usage domain event`() {
        val mapper = ProviderProtocolDomainMapper(clock = { 42L })

        val event = assertIs<SessionDomainEvent.UsageUpdated>(
            mapper.map(
                ProviderEvent.ThreadTokenUsageUpdated(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    contextWindow = 400_000,
                    inputTokens = 120_000,
                    cachedInputTokens = 90_000,
                    outputTokens = 3_200,
                ),
            ).single(),
        )

        assertEquals("thread-1", event.threadId)
        assertEquals("turn-1", event.turnId)
        assertEquals(400_000, event.contextWindow)
        assertEquals(120_000, event.inputTokens)
        assertEquals(90_000, event.cachedInputTokens)
        assertEquals(3_200, event.outputTokens)
    }

    /**
     * Verifies that provider collaboration snapshots stay on the domain ingress path.
     */
    @Test
    fun `maps subagents updated into collaboration domain event`() {
        val mapper = ProviderProtocolDomainMapper(clock = { 42L })

        val event = assertIs<SessionDomainEvent.SubagentsUpdated>(
            mapper.map(
                ProviderEvent.SubagentsUpdated(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    agents = listOf(
                        ProviderAgentSnapshot(
                            threadId = "agent-thread-1",
                            displayName = "Worker 1",
                            mentionSlug = "worker-1",
                            status = ProviderAgentStatus.ACTIVE,
                            statusText = "Running",
                            summary = "Inspecting the repository",
                            updatedAt = 42L,
                        ),
                    ),
                ),
            ).single(),
        )

        assertEquals("thread-1", event.threadId)
        assertEquals("turn-1", event.turnId)
        assertEquals(1, event.agents.size)
        assertEquals("Worker 1", event.agents.single().displayName)
        assertEquals("worker-1", event.agents.single().mentionSlug)
        assertEquals(SessionSubagentStatus.ACTIVE, event.agents.single().status)
        assertEquals("Running", event.agents.single().statusText)
        assertEquals("Inspecting the repository", event.agents.single().summary)
        assertEquals(42L, event.agents.single().updatedAt)
    }

    /**
     * Verifies that Codex running-plan notifications preserve submission-panel presentation into the kernel.
     */
    @Test
    fun `maps provider running plan update preserving submission presentation`() {
        val mapper = ProviderProtocolDomainMapper(clock = { 42L })

        val event = assertIs<SessionDomainEvent.RunningPlanUpdated>(
            mapper.map(
                ProviderEvent.RunningPlanUpdated(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    explanation = "Working through plan",
                    steps = listOf(
                        ProviderPlanStep(step = "Inspect events", status = "completed"),
                        ProviderPlanStep(step = "Wire composer panel", status = "inProgress"),
                    ),
                    body = """
                        Working through plan

                        - [completed] Inspect events
                        - [inProgress] Wire composer panel
                    """.trimIndent(),
                    presentation = ProviderRunningPlanPresentation.SUBMISSION_PANEL,
                ),
            ).single(),
        )

        assertEquals("turn-1", event.plan.turnId)
        assertEquals("Working through plan", event.plan.explanation)
        assertEquals(SessionRunningPlanPresentation.SUBMISSION_PANEL, event.plan.presentation)
    }

    /**
     * Verifies that replayed user-input items rebuild both request and resolved events.
     */
    @Test
    fun `maps historical user input item into request and resolved domain events`() {
        val mapper = ProviderProtocolDomainMapper(clock = { 42L })
        mapper.map(
            ProviderEvent.TurnStarted(
                turnId = "turn-1",
                threadId = "thread-1",
            ),
        )

        val events = mapper.map(
            ProviderEvent.ItemUpdated(
                ProviderItem(
                    id = "req-1:call-1",
                    kind = ItemKind.USER_INPUT,
                    status = ItemStatus.SUCCESS,
                    name = "User Input",
                    text = "Target\nReuse existing demo",
                    toolUserInputPrompt = ProviderToolUserInputPrompt(
                        requestId = "req-1:call-1",
                        threadId = "thread-1",
                        turnId = "turn-1",
                        itemId = "call-1",
                        questions = listOf(
                            ProviderToolUserInputQuestion(
                                id = "builder_demo_target",
                                header = "Target",
                                question = "How should I handle the builder demo?",
                            ),
                        ),
                        responseSummary = "Target\nReuse existing demo",
                        status = ItemStatus.SUCCESS,
                    ),
                ),
            ),
        )

        assertEquals(2, events.size)
        val requested = assertIs<SessionDomainEvent.ToolUserInputRequested>(events[0])
        val resolved = assertIs<SessionDomainEvent.ToolUserInputResolved>(events[1])
        assertEquals("req-1:call-1", requested.request.requestId)
        assertEquals("thread-1", requested.request.threadId)
        assertEquals("Target\nReuse existing demo", resolved.responseSummary)
        assertEquals(com.auracode.assistant.session.kernel.SessionActivityStatus.SUCCESS, resolved.status)
    }
}
