package com.auracode.assistant.settings.skills

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Resolves whether a runtime skill path belongs to a user-managed local installation and
 * performs safe uninstall operations only for those managed roots.
 */
internal class LocalSkillInstallPolicy(
    private val homeDir: Path = Path.of(System.getProperty("user.home").orEmpty()),
) {
    private val managedRoots: List<Path> = listOf(
        homeDir.resolve(".codex/skills").normalize(),
        homeDir.resolve(".claude/skills").normalize(),
        homeDir.resolve(".agents/skills").normalize(),
    )

    /** Returns the local skill directory if the runtime path belongs to a user-managed install. */
    fun resolveInstalledSkillDir(path: String): Path? {
        val candidate = runCatching { Path.of(path).normalize() }.getOrNull() ?: return null
        val skillDir = when {
            candidate.isDirectory() && candidate.resolve("SKILL.md").isRegularFile() -> candidate
            candidate.isRegularFile() && candidate.name.equals("SKILL.md", ignoreCase = true) -> candidate.parent
            else -> null
        } ?: return null
        if (!skillDir.startsWithAny(managedRoots)) return null
        return skillDir
    }

    /** Returns true when the path points to a local skill installation owned by the user. */
    fun canUninstall(path: String): Boolean = resolveInstalledSkillDir(path) != null

    /** Deletes the installed skill directory and returns the removed root when successful. */
    fun uninstall(path: String): Result<Path> {
        val skillDir = resolveInstalledSkillDir(path)
            ?: return Result.failure(IllegalArgumentException("This skill cannot be uninstalled."))
        return runCatching {
            deleteRecursively(skillDir)
            skillDir
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walkFileTree(
            root,
            object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            },
        )
    }
}

private fun Path.startsWithAny(roots: List<Path>): Boolean = roots.any { this.startsWith(it) }
