package com.auracode.assistant.commit

import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CommitMessageGenerationServiceTest {
    @Test
    fun `generate returns sanitized assistant commit message and attaches generic vcs context files`() {
        runBlocking {
            var capturedRequest = com.auracode.assistant.model.AgentRequest(
                engineId = "unused",
                prompt = "",
                contextFiles = emptyList(),
                workingDirectory = ".",
            )
            val service = CommitMessageGenerationService(
                streamProvider = {
                    capturedRequest = it
                    flowOf(
                        com.auracode.assistant.session.kernel.SessionDomainEvent.MessageAppended(
                            messageId = "item-1",
                            turnId = "turn-1",
                            role = com.auracode.assistant.session.kernel.SessionMessageRole.ASSISTANT,
                            text = "feat: add commit message generation button",
                        ),
                    )
                },
            )

            val result = service.generate(
                CommitMessageGenerationContext(
                    changeSummary = "MODIFICATION: src/Main.kt\n- old\n+ new",
                    includedFilePaths = listOf("src/Main.kt"),
                ),
            )

            assertEquals("feat: add commit message generation button", result)
            val changeSummary = capturedRequest.contextFiles.firstOrNull { it.path == "vcs/change-summary.txt" }
            val includedFiles = capturedRequest.contextFiles.firstOrNull { it.path == "vcs/included-files.txt" }
            assertEquals("MODIFICATION: src/Main.kt\n- old\n+ new", assertNotNull(changeSummary).content)
            assertEquals("src/Main.kt", assertNotNull(includedFiles).content)
        }
    }

    @Test
    fun `generate fails when provider output is not a conventional commit`() {
        runBlocking {
            val service = CommitMessageGenerationService(
                streamProvider = {
                    flowOf(
                        com.auracode.assistant.session.kernel.SessionDomainEvent.MessageAppended(
                            messageId = "item-1",
                            turnId = "turn-1",
                            role = com.auracode.assistant.session.kernel.SessionMessageRole.ASSISTANT,
                            text = "add commit message generation button",
                        ),
                    )
                },
            )

            assertFailsWith<IllegalArgumentException> {
                service.generate(
                    CommitMessageGenerationContext(
                        changeSummary = "MODIFICATION: src/Main.kt\n- old\n+ new",
                        includedFilePaths = listOf("src/Main.kt"),
                    ),
                )
            }
        }
    }
}
