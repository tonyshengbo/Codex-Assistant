package com.codex.assistant.timeline

import com.codex.assistant.model.EngineEvent
import com.codex.assistant.model.TimelineAction
import com.codex.assistant.model.TimelineActionStatus
import com.codex.assistant.model.TimelineNarrativeKind

class TimelineActionAssembler(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var nextSequenceValue: Int = 0
    private var narrativeIndex: Int = 0
    private var toolIndex: Int = 0
    private var commandIndex: Int = 0
    private val toolSequences = linkedMapOf<String, Int>()
    private val openToolIdsByName = linkedMapOf<String, String>()
    private val narrativeBuffer = StringBuilder()
    private var failureRecorded: Boolean = false

    fun accept(event: EngineEvent): List<TimelineAction> {
        return when (event) {
            is EngineEvent.AssistantTextDelta -> {
                narrativeBuffer.append(event.text)
                emptyList()
            }

            is EngineEvent.ThinkingDelta -> listOf(
                TimelineAction.AppendThinking(
                    text = event.text,
                    timestampMs = clock(),
                ),
            )

            is EngineEvent.TurnUsage -> emptyList()

            is EngineEvent.Status -> {
                val actions = mutableListOf<TimelineAction>()
                flushNarrative(TimelineNarrativeKind.NOTE)?.let(actions::add)
                val text = event.message.trim()
                if (text.isNotBlank() && !isHiddenLifecycleStatus(text)) {
                    actions += TimelineAction.AppendNarrative(
                        id = nextNarrativeId(TimelineNarrativeKind.NOTE),
                        kind = TimelineNarrativeKind.NOTE,
                        text = text,
                        sequence = nextSequence(),
                        timestampMs = clock(),
                    )
                }
                actions
            }

            is EngineEvent.ToolCallStarted -> {
                val actions = mutableListOf<TimelineAction>()
                flushNarrative(TimelineNarrativeKind.NOTE)?.let(actions::add)
                val toolId = event.callId ?: "tool-${++toolIndex}"
                val sequence = toolSequences.getOrPut(toolId) { nextSequence() }
                openToolIdsByName[event.name.lowercase()] = toolId
                actions += TimelineAction.UpsertTool(
                    id = toolId,
                    name = event.name,
                    input = event.input.orEmpty(),
                    status = TimelineActionStatus.RUNNING,
                    sequence = sequence,
                    timestampMs = clock(),
                )
                actions
            }

            is EngineEvent.ToolCallFinished -> {
                val actions = mutableListOf<TimelineAction>()
                flushNarrative(TimelineNarrativeKind.NOTE)?.let(actions::add)
                val toolId = event.callId
                    ?: openToolIdsByName.remove(event.name.lowercase())
                    ?: "tool-${++toolIndex}"
                val sequence = toolSequences.getOrPut(toolId) { nextSequence() }
                actions += TimelineAction.UpsertTool(
                    id = toolId,
                    name = event.name,
                    output = event.output.orEmpty(),
                    status = if (event.isError) TimelineActionStatus.FAILED else TimelineActionStatus.SUCCESS,
                    sequence = sequence,
                    timestampMs = clock(),
                )
                actions
            }

            is EngineEvent.CommandProposal -> {
                val actions = mutableListOf<TimelineAction>()
                flushNarrative(TimelineNarrativeKind.NOTE)?.let(actions::add)
                actions += TimelineAction.CommandProposalReceived(
                    id = "command-${++commandIndex}",
                    command = event.command,
                    cwd = event.cwd,
                    sequence = nextSequence(),
                    timestampMs = clock(),
                )
                actions
            }

            is EngineEvent.DiffProposal -> listOf(
                TimelineAction.DiffProposalReceived(
                    filePath = event.filePath,
                    newContent = event.newContent,
                    timestampMs = clock(),
                ),
            )

            is EngineEvent.SessionReady -> emptyList()

            is EngineEvent.Error -> {
                val actions = mutableListOf<TimelineAction>()
                flushNarrative(TimelineNarrativeKind.NOTE)?.let(actions::add)
                val message = event.message.trim()
                if (message.isNotBlank()) {
                    failureRecorded = true
                    actions += TimelineAction.MarkTurnFailed(
                        message = message,
                        sequence = nextSequence(),
                        timestampMs = clock(),
                    )
                }
                actions
            }

            is EngineEvent.Completed -> {
                val actions = mutableListOf<TimelineAction>()
                flushNarrative(
                    kind = if (failureRecorded || event.exitCode != 0) TimelineNarrativeKind.NOTE else TimelineNarrativeKind.RESULT,
                )?.let(actions::add)
                if (event.exitCode != 0 && !failureRecorded) {
                    failureRecorded = true
                    actions += TimelineAction.MarkTurnFailed(
                        message = "Engine exited with code ${event.exitCode}.",
                        sequence = nextSequence(),
                        timestampMs = clock(),
                    )
                }
                actions += TimelineAction.FinishTurn
                actions
            }
        }
    }

    private fun flushNarrative(kind: TimelineNarrativeKind): TimelineAction.AppendNarrative? {
        val text = narrativeBuffer.toString().trim()
        if (text.isBlank()) {
            narrativeBuffer.clear()
            return null
        }
        narrativeBuffer.clear()
        return TimelineAction.AppendNarrative(
            id = nextNarrativeId(kind),
            kind = kind,
            text = text,
            sequence = nextSequence(),
            timestampMs = clock(),
        )
    }

    private fun nextNarrativeId(kind: TimelineNarrativeKind): String {
        narrativeIndex += 1
        val prefix = when (kind) {
            TimelineNarrativeKind.NOTE -> "note"
            TimelineNarrativeKind.RESULT -> "result"
        }
        return "$prefix-$narrativeIndex"
    }

    private fun isHiddenLifecycleStatus(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isBlank()) return false
        val lower = normalized.lowercase()
        return lower == "turn.started" ||
            lower == "thread.started" ||
            lower == "item.started" ||
            lower == "turn.completed" ||
            lower == "thread.completed" ||
            lower == "item.completed"
    }

    private fun nextSequence(): Int {
        nextSequenceValue += 1
        return nextSequenceValue
    }
}
