package com.auracode.assistant.commit

import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedItem
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommitMessageGenerationServiceTest {
    @Test
    fun `generate returns sanitized assistant commit message`() {
        runBlocking {
            val service = CommitMessageGenerationService(
                streamProvider = {
                    flowOf(
                        UnifiedEvent.ItemUpdated(
                            UnifiedItem(
                                id = "item-1",
                                kind = ItemKind.NARRATIVE,
                                status = ItemStatus.SUCCESS,
                                text = "feat: add commit message generation button",
                            ),
                        ),
                    )
                },
            )

            val result = service.generate(
                CommitMessageGenerationContext(
                    branchName = "main",
                    stagedDiff = "diff --git a.txt b.txt",
                    includedFilePaths = listOf("src/Main.kt"),
                ),
            )

            assertEquals("feat: add commit message generation button", result)
        }
    }

    @Test
    fun `generate fails when provider output is not a conventional commit`() {
        runBlocking {
            val service = CommitMessageGenerationService(
                streamProvider = {
                    flowOf(
                        UnifiedEvent.ItemUpdated(
                            UnifiedItem(
                                id = "item-1",
                                kind = ItemKind.NARRATIVE,
                                status = ItemStatus.SUCCESS,
                                text = "add commit message generation button",
                            ),
                        ),
                    )
                },
            )

            assertFailsWith<IllegalArgumentException> {
                service.generate(
                    CommitMessageGenerationContext(
                        branchName = "main",
                        stagedDiff = "diff --git a.txt b.txt",
                        includedFilePaths = listOf("src/Main.kt"),
                    ),
                )
            }
        }
    }
}
