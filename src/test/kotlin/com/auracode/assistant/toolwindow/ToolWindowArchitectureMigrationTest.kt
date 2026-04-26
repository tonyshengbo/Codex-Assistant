package com.auracode.assistant.toolwindow

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolWindowArchitectureMigrationTest {
    private val projectRoot: Path = Path.of(System.getProperty("user.dir"))

    @Test
    fun `coordinator no longer depends on legacy session ui cache dispatcher`() {
        val coordinatorSource = projectRoot.resolve(
            "src/main/kotlin/com/auracode/assistant/toolwindow/eventing/ToolWindowCoordinator.kt",
        )

        assertFalse(
            Files.exists(
                projectRoot.resolve(
                    "src/main/kotlin/com/auracode/assistant/toolwindow/eventing/SessionScopedEventDispatcher.kt",
                ),
            ),
        )
        assertFalse(
            Files.exists(
                projectRoot.resolve(
                    "src/main/kotlin/com/auracode/assistant/toolwindow/eventing/SessionUiStateCache.kt",
                ),
            ),
        )

        val sourceText = Files.readString(coordinatorSource)
        assertFalse(sourceText.contains("SessionScopedEventDispatcher"))
        assertFalse(sourceText.contains("SessionUiStateCache"))
    }

    @Test
    fun `toolwindow migration exposes a dedicated sessions package`() {
        val sessionsPackage = projectRoot.resolve(
            "src/main/kotlin/com/auracode/assistant/toolwindow/sessions",
        )

        assertTrue(Files.isDirectory(sessionsPackage))
        assertTrue(
            Files.list(sessionsPackage).use { stream -> stream.anyMatch { Files.isRegularFile(it) } },
        )
    }

    /**
     * Verifies that feature-oriented toolwindow packages replace the legacy placement-oriented layout.
     */
    @Test
    fun `toolwindow packages are organized by feature domains`() {
        assertHasKotlinFiles("src/main/kotlin/com/auracode/assistant/toolwindow/shell")
        assertHasKotlinFiles("src/main/kotlin/com/auracode/assistant/toolwindow/sessions")
        assertHasKotlinFiles("src/main/kotlin/com/auracode/assistant/toolwindow/conversation")
        assertHasKotlinFiles("src/main/kotlin/com/auracode/assistant/toolwindow/submission")
        assertHasKotlinFiles("src/main/kotlin/com/auracode/assistant/toolwindow/execution")
        assertHasKotlinFiles("src/main/kotlin/com/auracode/assistant/toolwindow/history")
        assertHasKotlinFiles("src/main/kotlin/com/auracode/assistant/toolwindow/settings")
        assertHasKotlinFiles("src/main/kotlin/com/auracode/assistant/toolwindow/shared")

        assertFalse(Files.exists(projectRoot.resolve("src/main/kotlin/com/auracode/assistant/toolwindow/bootstrap")))
        assertFalse(Files.exists(projectRoot.resolve("src/main/kotlin/com/auracode/assistant/toolwindow/drawer")))
        assertFalse(Files.exists(projectRoot.resolve("src/main/kotlin/com/auracode/assistant/toolwindow/session")))
        assertFalse(Files.exists(projectRoot.resolve("src/main/kotlin/com/auracode/assistant/toolwindow/header")))
        assertFalse(Files.exists(projectRoot.resolve("src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeMapper.kt")))
        assertFalse(Files.exists(projectRoot.resolve("src/main/kotlin/com/auracode/assistant/toolwindow/timeline/TimelineNodeReducer.kt")))
    }

    /**
     * Verifies that the given feature package contains at least one Kotlin source file.
     */
    private fun assertHasKotlinFiles(relativePath: String) {
        val packagePath = projectRoot.resolve(relativePath)
        assertTrue(Files.isDirectory(packagePath), "Expected package directory at $relativePath")
        assertTrue(
            Files.walk(packagePath).use { stream ->
                stream.anyMatch { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".kt") }
            },
            "Expected Kotlin source files under $relativePath",
        )
    }
}
