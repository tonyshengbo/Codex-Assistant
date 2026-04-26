package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.toolwindow.conversation.TimelineFileChangePreview
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolWindowCoordinatorEditedFilesTest {
    @Test
    fun `revert parsed diff restores update to previous content`() {
        val workingDir = createTempDirectory("edited-file-revert-update")
        val file = workingDir.resolve("Foo.kt")
        Files.writeString(file, "fun a() = 2\nfun b() = 3\n")
        val parsed = TimelineFileChangePreview.parseTurnDiff(
            """
                diff --git a/${file} b/${file}
                --- a/${file}
                +++ b/${file}
                @@ -1 +1,2 @@
                -fun a() = 1
                +fun a() = 2
                +fun b() = 3
            """.trimIndent(),
        ).getValue(file.toString())

        val result = TimelineFileChangePreview.revertParsedDiff(parsed)

        assertTrue(result.isSuccess)
        assertEquals("fun a() = 1\n", Files.readString(file))
    }

    @Test
    fun `revert parsed diff deletes file created during session`() {
        val workingDir = createTempDirectory("edited-file-revert-create")
        val file = workingDir.resolve("Created.kt")
        Files.writeString(file, "class Created\n")
        val parsed = TimelineFileChangePreview.parseTurnDiff(
            """
                diff --git a/${file} b/${file}
                new file mode 100644
                --- /dev/null
                +++ b/${file}
                @@ -0,0 +1 @@
                +class Created
            """.trimIndent(),
        ).getValue(file.toString())

        val result = TimelineFileChangePreview.revertParsedDiff(parsed)

        assertTrue(result.isSuccess)
        assertFalse(Files.exists(file))
    }
}
