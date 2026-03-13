package com.codex.assistant.provider

import com.codex.assistant.model.EngineEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class ProposalAccumulatorTest {
    @Test
    fun `deduplicates repeated structured command proposals`() {
        val acc = ProposalAccumulator(cwd = "/tmp/repo")

        val first = acc.acceptStructured(
            EngineEvent.CommandProposal(command = "npm test", cwd = "/tmp/repo"),
        )
        val duplicate = acc.acceptStructured(
            EngineEvent.CommandProposal(command = "npm test", cwd = "/tmp/repo"),
        )

        assertEquals(true, first)
        assertEquals(false, duplicate)
    }
}
