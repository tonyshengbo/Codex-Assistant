package com.codex.assistant.timeline

import com.codex.assistant.model.EngineEvent
import com.codex.assistant.model.TimelineAction
import com.codex.assistant.model.TimelineNarrativeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimelineActionAssemblerTest {
    @Test
    fun `flushes middle text as notes and trailing text as result based on engine event order`() {
        val assembler = TimelineActionAssembler()

        val actions = buildList {
            addAll(assembler.accept(EngineEvent.AssistantTextDelta("I inspected the file first.")))
            addAll(
                assembler.accept(
                    EngineEvent.ToolCallStarted(
                        callId = "tool-1",
                        name = "read_file",
                        input = "src/App.kt",
                    ),
                ),
            )
            addAll(
                assembler.accept(
                    EngineEvent.ToolCallFinished(
                        callId = "tool-1",
                        name = "read_file",
                        output = "file loaded",
                    ),
                ),
            )
            addAll(assembler.accept(EngineEvent.AssistantTextDelta("I then ran the verification command.")))
            addAll(assembler.accept(EngineEvent.CommandProposal(command = "./gradlew test", cwd = ".")))
            addAll(assembler.accept(EngineEvent.AssistantTextDelta("All checks passed.")))
            addAll(assembler.accept(EngineEvent.Completed(exitCode = 0)))
        }

        assertEquals(
            listOf(
                TimelineAction.AppendNarrative::class,
                TimelineAction.UpsertTool::class,
                TimelineAction.UpsertTool::class,
                TimelineAction.AppendNarrative::class,
                TimelineAction.CommandProposalReceived::class,
                TimelineAction.AppendNarrative::class,
                TimelineAction.FinishTurn::class,
            ),
            actions.map { it::class },
        )

        val firstNote = assertIs<TimelineAction.AppendNarrative>(actions[0])
        assertEquals(TimelineNarrativeKind.NOTE, firstNote.kind)
        assertEquals("I inspected the file first.", firstNote.text)

        val secondNote = assertIs<TimelineAction.AppendNarrative>(actions[3])
        assertEquals(TimelineNarrativeKind.NOTE, secondNote.kind)
        assertEquals("I then ran the verification command.", secondNote.text)

        val result = assertIs<TimelineAction.AppendNarrative>(actions[5])
        assertEquals(TimelineNarrativeKind.RESULT, result.kind)
        assertEquals("All checks passed.", result.text)
    }

    @Test
    fun `ignores startup status events instead of turning them into narrative nodes`() {
        val assembler = TimelineActionAssembler()

        val turnStarted = assembler.accept(EngineEvent.Status("turn.started"))
        val threadStarted = assembler.accept(EngineEvent.Status("thread.started"))
        val itemStarted = assembler.accept(EngineEvent.Status("item.started"))

        assertTrue(turnStarted.isEmpty(), "turn.started should not become a visible narrative action")
        assertTrue(threadStarted.isEmpty(), "thread.started should not become a visible narrative action")
        assertTrue(itemStarted.isEmpty(), "item.started should not become a visible narrative action")
    }
}
