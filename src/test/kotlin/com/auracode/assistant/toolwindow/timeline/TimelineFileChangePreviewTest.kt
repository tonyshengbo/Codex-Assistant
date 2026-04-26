package com.auracode.assistant.toolwindow.conversation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TimelineFileChangePreviewTest {
    @Test
    fun `update change computes stats from on disk content when old content is missing`() {
        val file = Files.createTempFile("timeline-file-change-preview", ".kt")
        Files.writeString(file, "fun a() = 1\n")

        val resolved = TimelineFileChangePreview.resolve(
            TimelineFileChange(
                sourceScopedId = "req:item:0",
                path = file.toString(),
                displayName = file.fileName.toString(),
                kind = TimelineFileChangeKind.UPDATE,
                newContent = "fun a() = 2\nfun b() = 3\n",
            ),
        )

        assertEquals("fun a() = 1\n", resolved.oldContent)
        assertEquals("fun a() = 2\nfun b() = 3\n", resolved.newContent)
        assertEquals(2, resolved.addedLines)
        assertEquals(1, resolved.deletedLines)
    }

    @Test
    fun `create change uses empty old content and computes added lines`() {
        val resolved = TimelineFileChangePreview.resolve(
            TimelineFileChange(
                sourceScopedId = "req:item:1",
                path = "/tmp/new-file.kt",
                displayName = "new-file.kt",
                kind = TimelineFileChangeKind.CREATE,
                newContent = "class NewFile\n",
            ),
        )

        assertEquals("", resolved.oldContent)
        assertEquals("class NewFile\n", resolved.newContent)
        assertEquals(1, resolved.addedLines)
        assertEquals(0, resolved.deletedLines)
    }

    @Test
    fun `parses unified diff with absolute paths and new file`() {
        val parsed = TimelineFileChangePreview.parseTurnDiff(
            """
                diff --git a//tmp/Foo.kt b//tmp/Foo.kt
                index 1111111..2222222 100644
                --- a//tmp/Foo.kt
                +++ b//tmp/Foo.kt
                @@ -1 +1,2 @@
                -fun a() = 1
                +fun a() = 2
                +fun b() = 3
                diff --git a//tmp/Bar.kt b//tmp/Bar.kt
                new file mode 100644
                --- /dev/null
                +++ b//tmp/Bar.kt
                @@ -0,0 +1 @@
                +class Bar
            """.trimIndent(),
        )

        val foo = parsed["/tmp/Foo.kt"]
        val bar = parsed["/tmp/Bar.kt"]
        assertNotNull(foo)
        assertNotNull(bar)
        assertEquals(TimelineFileChangeKind.UPDATE, foo.kind)
        assertEquals(2, foo.addedLines)
        assertEquals(1, foo.deletedLines)
        assertEquals(TimelineFileChangeKind.CREATE, bar.kind)
        assertEquals(1, bar.addedLines)
        assertEquals(0, bar.deletedLines)
    }

    @Test
    fun `timeline inline diff prefers scoped unified diff for current file`() {
        val diff = timelineInlineDiff(
            TimelineFileChange(
                sourceScopedId = "req:item:0",
                path = "/tmp/Foo.kt",
                displayName = "Foo.kt",
                kind = TimelineFileChangeKind.UPDATE,
                unifiedDiff = """
                    diff --git a//tmp/Foo.kt b//tmp/Foo.kt
                    index 1111111..2222222 100644
                    --- a//tmp/Foo.kt
                    +++ b//tmp/Foo.kt
                    @@ -1 +1 @@
                    -fun a() = 1
                    +fun a() = 2
                    diff --git a//tmp/Bar.kt b//tmp/Bar.kt
                    --- a//tmp/Bar.kt
                    +++ b//tmp/Bar.kt
                    @@ -1 +1 @@
                    -class Bar
                    +class Bar2
                """.trimIndent(),
            ),
        )

        assertTrue(diff.contains("+++ b//tmp/Foo.kt"))
        assertTrue(diff.contains("+fun a() = 2"))
        assertTrue(!diff.contains("Bar2"))
    }

    @Test
    fun `parsed update diff can be reverted against current disk content`() {
        val file = Files.createTempFile("timeline-file-change-revert", ".kt")
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
        )[file.toString()]

        assertNotNull(parsed)

        val result = TimelineFileChangePreview.revertParsedDiff(parsed)

        assertTrue(result.isSuccess)
        assertEquals("fun a() = 1\n", Files.readString(file))
    }
}
