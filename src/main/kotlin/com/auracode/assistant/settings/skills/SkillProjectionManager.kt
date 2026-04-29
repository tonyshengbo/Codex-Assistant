package com.auracode.assistant.settings.skills

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/** Creates engine-visible projections for imported skill directories. */
internal class SkillProjectionManager(
    private val directoryResolver: EngineSkillDirectoryResolver = EngineSkillDirectoryResolver(),
    private val operatingSystemName: String = System.getProperty("os.name").orEmpty(),
) {
    /** Projects all scanned skills into every supported engine directory. */
    fun projectAll(skills: List<ScannedSkillRootEntry>) {
        val engines = directoryResolver.supportedProjectionEngines()
        ensureNoConflicts(skills = skills, engines = engines)
        engines.forEach { engineId ->
            projectForEngine(engineId = engineId, skills = skills)
        }
    }

    /** Projects all scanned skills into one engine directory. */
    fun projectForEngine(engineId: String, skills: List<ScannedSkillRootEntry>) {
        val targetRoot = directoryResolver.projectionDirectory(engineId)
        targetRoot.createDirectories()
        skills.forEach { skill ->
            val target = targetRoot.resolve(skill.name)
            if (target.exists()) {
                error("Skill '${skill.name}' already exists in '${targetRoot}'.")
            }
            createDirectoryProjection(source = skill.skillDir, target = target)
        }
    }

    /** Validates that no projection target already exists before creating anything. */
    fun ensureNoConflicts(skills: List<ScannedSkillRootEntry>, engines: List<String> = directoryResolver.supportedProjectionEngines()) {
        skills.groupBy { it.name }
            .filterValues { it.size > 1 }
            .keys
            .firstOrNull()
            ?.let { duplicateName ->
                error("Duplicate imported skill '$duplicateName' was found in the selected root.")
            }
        engines.forEach { engineId ->
            val targetRoot = directoryResolver.projectionDirectory(engineId)
            skills.firstOrNull { targetRoot.resolve(it.name).exists() }?.let { conflict ->
                error("Skill '${conflict.name}' already exists in '${targetRoot}'.")
            }
        }
    }

    private fun createDirectoryProjection(source: Path, target: Path) {
        if (isWindows()) {
            createWindowsJunction(source = source, target = target)
        } else {
            Files.createSymbolicLink(target, source)
        }
    }

    private fun createWindowsJunction(source: Path, target: Path) {
        val process = ProcessBuilder(
            "cmd",
            "/c",
            "mklink",
            "/J",
            target.toAbsolutePath().toString(),
            source.toAbsolutePath().toString(),
        ).start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val errorText = process.errorStream.bufferedReader().readText().trim()
            error(
                if (errorText.isNotBlank()) {
                    "Failed to create junction '${target}': $errorText"
                } else {
                    "Failed to create junction '${target}'."
                },
            )
        }
    }

    private fun isWindows(): Boolean = operatingSystemName.contains("win", ignoreCase = true)
}
