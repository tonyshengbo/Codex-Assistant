package com.auracode.assistant.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityTitleFormatterTest {
    @Test
    fun `formats file probes as read`() {
        val presentation = ActivityTitleFormatter.commandPresentation(
            command = "/bin/zsh -lc 'cat /tmp/a >/dev/null && nl -ba /Users/tonysheng/DataGripProjects/hello.java'",
        )

        assertEquals("Read hello.java", presentation.title)
        assertEquals("hello.java", presentation.targetLabel)
        assertEquals("/Users/tonysheng/DataGripProjects/hello.java", presentation.targetPath)
    }

    @Test
    fun `formats relative file probes as read`() {
        val presentation = ActivityTitleFormatter.commandPresentation(
            command = "/bin/zsh -lc 'sed -n ''240,340p'' src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt'",
        )

        assertEquals("Read ToolWindowCoordinator.kt", presentation.title)
        assertEquals("ToolWindowCoordinator.kt", presentation.targetLabel)
        assertEquals("src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt", presentation.targetPath)
    }

    @Test
    fun `formats file writes as edit`() {
        val presentation = ActivityTitleFormatter.commandPresentation(
            command = "/bin/zsh -lc \"cat > /Users/tonysheng/DataGripProjects/HelloTest.java <<'EOF'\nhello\nEOF\"",
        )

        assertEquals("Edit HelloTest.java", presentation.title)
        assertEquals("HelloTest.java", presentation.targetLabel)
        assertEquals("/Users/tonysheng/DataGripProjects/HelloTest.java", presentation.targetPath)
    }

    @Test
    fun `formats append as edit`() {
        val presentation = ActivityTitleFormatter.commandPresentation(
            command = "/bin/zsh -lc \"cat >> /Users/tonysheng/DataGripProjects/hello.java <<'EOF'\nhello\nEOF\"",
        )

        assertEquals("Edit hello.java", presentation.title)
        assertEquals("hello.java", presentation.targetLabel)
        assertEquals("/Users/tonysheng/DataGripProjects/hello.java", presentation.targetPath)
    }

    @Test
    fun `keeps search classification`() {
        val presentation = ActivityTitleFormatter.commandPresentation(
            command = "rg --files -g '*.kt'",
        )

        assertEquals("Search kt files", presentation.title)
    }

    @Test
    fun `keeps high value run categories`() {
        assertEquals("Run Git Status", ActivityTitleFormatter.commandTitle(command = "git status"))
        assertEquals("Run Gradle Test", ActivityTitleFormatter.commandTitle(command = "./gradlew test"))
        assertEquals("Run Java", ActivityTitleFormatter.commandTitle(command = "javac hello.java"))
    }

    @Test
    fun `falls back to generic run command`() {
        assertEquals("Run command foobar", ActivityTitleFormatter.commandTitle(command = "foobar --flag"))
    }

    @Test
    fun `falls back to bare run command when command cannot be extracted`() {
        assertEquals("Run command", ActivityTitleFormatter.commandTitle(command = "&& || ;"))
    }
}
