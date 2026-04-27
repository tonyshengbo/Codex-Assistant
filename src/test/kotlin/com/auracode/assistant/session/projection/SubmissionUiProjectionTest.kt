package com.auracode.assistant.session.projection

import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.session.kernel.SessionFileChange
import com.auracode.assistant.session.kernel.SessionStateReducer
import com.auracode.assistant.session.kernel.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that submission projection exposes kernel-backed composer state.
 */
class SubmissionUiProjectionTest {
    /**
     * Verifies that running state and edited-file details are projected from the session kernel.
     */
    @Test
    fun `projects submission slice into running state and edited file aggregates`() {
        val state = SessionStateReducer().reduceAll(
            initialState = SessionState.empty(
                sessionId = "session-1",
                engineId = "codex",
            ),
            events = listOf(
                SessionDomainEvent.TurnStarted(
                    turnId = "turn-1",
                    threadId = "thread-1",
                    startedAtMs = 1L,
                ),
                SessionDomainEvent.EditedFilesTracked(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    changes = listOf(
                        SessionFileChange(
                            path = "/tmp/Foo.kt",
                            kind = "update",
                            summary = "update /tmp/Foo.kt",
                            displayName = "Foo.kt",
                            addedLines = 2,
                            deletedLines = 1,
                            updatedAtMs = 9L,
                            unifiedDiff = """
                                diff --git a//tmp/Foo.kt b//tmp/Foo.kt
                                --- a//tmp/Foo.kt
                                +++ b//tmp/Foo.kt
                                @@ -1 +1,2 @@
                                -fun a() = 1
                                +fun a() = 2
                                +fun b() = 3
                            """.trimIndent(),
                            oldContent = "fun a() = 1\n",
                            newContent = "fun a() = 2\nfun b() = 3\n",
                        ),
                    ),
                ),
            ),
        )

        val projection = SessionUiProjectionBuilder().project(state).submission

        assertTrue(projection.isRunning)
        assertEquals(1, projection.editedFiles.size)
        assertEquals("/tmp/Foo.kt", projection.editedFiles.single().path)
        assertEquals(2, projection.editedFiles.single().latestAddedLines)
        assertEquals(1, projection.editedFiles.single().latestDeletedLines)
    }
}
