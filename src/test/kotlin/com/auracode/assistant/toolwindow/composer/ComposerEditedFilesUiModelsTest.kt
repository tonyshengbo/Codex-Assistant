package com.auracode.assistant.toolwindow.submission

import com.auracode.assistant.toolwindow.conversation.TimelineFileChangeKind
import com.auracode.assistant.toolwindow.conversation.TimelineParsedTurnDiff
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerEditedFilesUiModelsTest {
    @Test
    fun `edited files ui model exposes file count without edit count`() {
        val uiModel = ComposerAreaState(
            editedFiles = listOf(
                EditedFileAggregate(
                    path = "/tmp/src/Foo.kt",
                    displayName = "Foo.kt",
                    threadId = "thread-1",
                    turnId = "turn-1",
                    latestAddedLines = 3,
                    latestDeletedLines = 1,
                    lastUpdatedAt = 1L,
                    parsedDiff = TimelineParsedTurnDiff(
                        path = "/tmp/src/Foo.kt",
                        displayName = "Foo.kt",
                        kind = TimelineFileChangeKind.UPDATE,
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
