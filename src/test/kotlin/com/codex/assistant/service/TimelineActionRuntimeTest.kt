package com.codex.assistant.service

import com.codex.assistant.model.TimelineAction
import com.codex.assistant.model.TimelineActionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TimelineActionRuntimeTest {
    @Test
    fun `executes command proposals in service layer and delays finish until command completes`() = runBlocking {
        val commandResult = CompletableDeferred<Pair<Int, String>>()
        val emitted = mutableListOf<TimelineAction>()
        val runtime = TimelineActionRuntime(
            scope = this,
            supportsCommandExecution = true,
            commandExecutor = { _, _ -> commandResult.await() },
            emitAction = { emitted += it },
            clock = { 1_000L },
        )

        runtime.accept(
            TimelineAction.CommandProposalReceived(
                id = "command-1",
                command = "./gradlew test",
                cwd = ".",
                sequence = 3,
                timestampMs = 900L,
            ),
        )
        runtime.accept(TimelineAction.FinishTurn)

        assertEquals(1, emitted.size)
        val running = assertIs<TimelineAction.UpsertCommand>(emitted.single())
        assertEquals(TimelineActionStatus.RUNNING, running.status)
        assertEquals("./gradlew test", running.command)

        commandResult.complete(0 to "BUILD SUCCESSFUL")
        runtime.awaitIdle()

        assertEquals(
            listOf(
                TimelineAction.UpsertCommand::class,
                TimelineAction.UpsertCommand::class,
                TimelineAction.FinishTurn::class,
            ),
            emitted.map { it::class },
        )
        val completed = assertIs<TimelineAction.UpsertCommand>(emitted[1])
        assertEquals(TimelineActionStatus.SUCCESS, completed.status)
        assertEquals("BUILD SUCCESSFUL", completed.output)
    }

    @Test
    fun `passes command proposal through when service-side execution is disabled`() = runBlocking {
        val emitted = mutableListOf<TimelineAction>()
        val runtime = TimelineActionRuntime(
            scope = this,
            supportsCommandExecution = false,
            commandExecutor = { _, _ -> error("should not execute") },
            emitAction = { emitted += it },
        )

        val proposal = TimelineAction.CommandProposalReceived(
            id = "command-1",
            command = "echo hi",
            cwd = ".",
            sequence = 1,
        )

        runtime.accept(proposal)
        runtime.accept(TimelineAction.FinishTurn)
        runtime.awaitIdle()

        assertEquals(listOf(proposal, TimelineAction.FinishTurn), emitted)
    }
}
