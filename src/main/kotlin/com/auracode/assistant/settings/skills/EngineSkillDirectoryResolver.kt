package com.auracode.assistant.settings.skills

import java.nio.file.Path

/** Resolves engine-specific skill directories for read and projection operations. */
internal class EngineSkillDirectoryResolver(
    private val homeDir: Path = Path.of(System.getProperty("user.home").orEmpty()),
) {
    /** Returns all directories scanned for the given engine. */
    fun scanDirectories(engineId: String): List<Path> {
        return when (engineId.trim()) {
            "claude" -> listOf(
                homeDir.resolve(".claude/skills"),
                homeDir.resolve(".claude/plugins/cache"),
            )
            else -> listOf(homeDir.resolve(".codex/skills"))
        }
    }

    /** Returns the writable projection target directory for the given engine. */
    fun projectionDirectory(engineId: String): Path {
        return when (engineId.trim()) {
            "claude" -> homeDir.resolve(".claude/skills")
            else -> homeDir.resolve(".codex/skills")
        }
    }

    /** Returns the engines supported by the current global projection flow. */
    fun supportedProjectionEngines(): List<String> = listOf("codex", "claude")
}
