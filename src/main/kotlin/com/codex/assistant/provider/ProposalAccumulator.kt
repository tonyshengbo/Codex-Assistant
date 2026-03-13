package com.codex.assistant.provider

import com.codex.assistant.model.EngineEvent

internal class ProposalAccumulator(
    private val cwd: String,
) {
    private val seenCommandKeys = linkedSetOf<String>()
    private val seenDiffKeys = linkedSetOf<String>()

    fun acceptStructured(event: EngineEvent): Boolean {
        return when (event) {
            is EngineEvent.CommandProposal -> seenCommandKeys.add(commandKey(event))
            is EngineEvent.DiffProposal -> seenDiffKeys.add(diffKey(event))
            else -> false
        }
    }

    private fun commandKey(event: EngineEvent.CommandProposal): String {
        return "${event.cwd.trim()}::${event.command.trim()}"
    }

    private fun diffKey(event: EngineEvent.DiffProposal): String {
        return "${event.filePath.trim()}::${event.newContent.hashCode()}"
    }
}
