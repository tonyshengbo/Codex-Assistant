package com.auracode.assistant.settings.skills

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/** Copies imported skills into engine-visible directories for the selected engine. */
internal class SkillProjectionManager(
    private val directoryResolver: EngineSkillDirectoryResolver = EngineSkillDirectoryResolver(),
) {
    /** Copies all scanned skills into every supported engine directory. */
    fun projectAll(skills: List<ScannedSkillRootEntry>) {
        val engines = directoryResolver.supportedProjectionEngines()
        engines.forEach { engineId ->
            projectForEngine(engineId = engineId, skills = skills)
        }
    }

    /** Copies all scanned skills into one engine directory. */
    fun projectForEngine(engineId: String, skills: List<ScannedSkillRootEntry>) {
        val targetRoot = directoryResolver.projectionDirectory(engineId)
        targetRoot.createDirectories()
        ensureNoDuplicateImportedNames(skills)
        skills.forEach { skill ->
            val target = targetRoot.resolve(skill.name)
            if (target.exists()) {
                deleteRecursively(target)
            }
            copyDirectory(source = skill.skillDir, target = target)
        }
    }

    /** Validates that the imported scan result does not contain duplicate skill names. */
    fun ensureNoDuplicateImportedNames(skills: List<ScannedSkillRootEntry>) {
        skills.groupBy { it.name }
            .filterValues { it.size > 1 }
            .keys
            .firstOrNull()
            ?.let { duplicateName ->
                error("Duplicate imported skill '$duplicateName' was found in the selected root.")
            }
    }

    /** Copies one source skill directory into its destination directory. */
    private fun copyDirectory(source: Path, target: Path) {
        Files.walkFileTree(
            source,
            object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    Files.createDirectories(target.resolve(source.relativize(dir).toString()))
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    Files.copy(
                        file,
                        target.resolve(source.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            },
        )
    }

    /** Deletes one existing target skill directory before an overwrite copy. */
    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walkFileTree(
            root,
            object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: java.io.IOException?,
                ): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            },
        )
    }
}
