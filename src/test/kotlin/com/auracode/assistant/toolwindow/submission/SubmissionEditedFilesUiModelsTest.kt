package com.auracode.assistant.toolwindow.submission

import com.auracode.assistant.toolwindow.conversation.ConversationFileChangeKind
import com.auracode.assistant.toolwindow.conversation.ConversationFileChange
import kotlin.test.Test
import kotlin.test.assertEquals

class SubmissionEditedFilesUiModelsTest {
    @Test
    fun `edited files ui model exposes file count without edit count`() {
        val uiModel = SubmissionAreaState(
            editedFiles = listOf(
                EditedFileAggregate(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    lastUpdatedAt = 1L,
                    change = ConversationFileChange(
                        sourceScopedId = "edited-file:turn-1:/tmp/src/Foo.kt",
                        path = "/tmp/src/Foo.kt",
                        displayName = "Foo.kt",
                        kind = ConversationFileChangeKind.UPDATE,
                        timestamp = 1L,
                        addedLines = 3,
                        deletedLines = 1,
                        unifiedDiff = """
                            diff --git a//tmp/src/Foo.kt b//tmp/src/Foo.kt
                            --- a//tmp/src/Foo.kt
                            +++ b//tmp/src/Foo.kt
                            @@ -1 +1 @@
                            -fun a() = 1
                            +fun a() = 2
                        """.trimIndent(),
                        oldContent = "fun a() = 1\n",
                        newContent = "fun a() = 2\n",
                    ),
                ),
            ),
        ).toEditedFilesPanelUiModel()

        assertEquals(1, uiModel.summary.totalFiles)
        assertEquals("Foo.kt", uiModel.files.single().displayName)
        assertEquals("tmp/src", uiModel.files.single().parentPath)
        assertEquals(3, uiModel.files.single().latestAddedLines)
        assertEquals(1, uiModel.files.single().latestDeletedLines)
    }
}
