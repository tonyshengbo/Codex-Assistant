package com.auracode.assistant.settings.skills

import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.submission.SlashSkillDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumMap
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines

internal enum class SkillSource {
    LOCAL,
    SUPERPOWERS,
    AGENTS,
}

internal data class SkillSummary(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val effectiveSource: SkillSource,
    val effectivePath: String,
    val sourceCount: Int,
    val deletable: Boolean,
)

internal data class SkillImportResult(
    val success: Boolean,
    val message: String,
)

internal interface SkillCatalog {
    fun listSkills(): List<SkillSummary>
    fun listEnabledSkills(): List<SlashSkillDescriptor>
    fun importSkill(path: String): SkillImportResult
    fun setSkillEnabled(name: String, enabled: Boolean)
    fun deleteSkill(name: String): Boolean
    fun findDisabledSkillMentions(text: String): List<String>
}

internal class LocalSkillCatalog(
    private val settings: AgentSettingsService,
    private val homeDir: Path = Path.of(System.getProperty("user.home").orEmpty()),
) : SkillCatalog {
    private data class SkillLocation(
        val name: String,
        val description: String,
        val source: SkillSource,
        val skillDir: Path,
        val skillFile: Path,
    )

    override fun listSkills(): List<SkillSummary> {
        val disabled = settings.disabledSkillNames()
        return discoverSkillLocations()
            .entries
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .map { (name, locations) ->
                val effective = locations.first()
                SkillSummary(
                    name = name,
                    description = effective.description.ifBlank { name },
                    enabled = name !in disabled,
                    effectiveSource = effective.source,
                    effectivePath = effective.skillFile.toString(),
                    sourceCount = locations.size,
                    deletable = isDeletable(effective),
                )
            }
    }

    override fun listEnabledSkills(): List<SlashSkillDescriptor> {
        return listSkills()
            .filter { it.enabled }
            .sortedWith(compareBy<SkillSummary>({ slashSkillSortPriority(it.name) }, { it.name.lowercase(Locale.ROOT) }))
            .map { SlashSkillDescriptor(name = it.name, description = it.description) }
    }

    override fun importSkill(path: String): SkillImportResult {
        val sourcePath = runCatching { Path.of(path.trim()) }.getOrNull()
            ?: return SkillImportResult(false, "Invalid skill path.")
        val sourceFile = when {
            sourcePath.isRegularFile() && sourcePath.name.equals("SKILL.md", ignoreCase = true) -> sourcePath
            sourcePath.isDirectory() -> sourcePath.resolve("SKILL.md").takeIf { it.isRegularFile() }
            else -> null
        } ?: return SkillImportResult(false, "Import must target a skill folder or SKILL.md file.")
        val descriptor = parseSkillDescriptor(sourceFile)
            ?: return SkillImportResult(false, "Imported skill is missing a valid name in front matter.")
        val targetDir = managedLocalRoot().resolve(descriptor.name)
        if (targetDir.exists()) {
            return SkillImportResult(false, "A local skill named '${descriptor.name}' already exists.")
        }
        Files.createDirectories(targetDir.parent)
        if (sourcePath.isRegularFile()) {
            Files.createDirectories(targetDir)
            Files.copy(sourceFile, targetDir.resolve("SKILL.md"), StandardCopyOption.REPLACE_EXISTING)
        } else {
            copyDirectory(sourcePath, targetDir)
        }
        return SkillImportResult(true, "Imported local skill '${descriptor.name}'.")
    }

    override fun setSkillEnabled(name: String, enabled: Boolean) {
        if (enabled) {
            settings.enableSkill(name)
        } else {
            settings.disableSkill(name)
        }
    }

    override fun deleteSkill(name: String): Boolean {
        val location = discoverSkillLocations()[name]?.firstOrNull() ?: return false
        if (!isDeletable(location)) return false
        deleteRecursively(location.skillDir)
        return true
    }

    override fun findDisabledSkillMentions(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val knownSkills = listSkills().associateBy { it.name }
        return SKILL_TOKEN_REGEX
            .findAll(text)
            .mapNotNull { match ->
                val name = match.groupValues[1]
                knownSkills[name]?.takeIf { !it.enabled }?.name
            }
            .distinct()
            .toList()
    }

    private fun discoverSkillLocations(): Map<String, List<SkillLocation>> {
        val grouped = linkedMapOf<String, MutableList<SkillLocation>>()
        skillRoots().forEach { (source, root) ->
            if (!Files.isDirectory(root)) return@forEach
            runCatching {
                Files.walk(root).use { paths ->
                    paths
                        .filter { it.isRegularFile() && it.name.equals("SKILL.md", ignoreCase = true) }
                        .forEach { skillFile ->
                            parseSkillDescriptor(skillFile)?.let { descriptor ->
                                grouped.getOrPut(descriptor.name) { mutableListOf() }
                                    .add(
                                        SkillLocation(
                                            name = descriptor.name,
                                            description = descriptor.description,
                                            source = source,
                                            skillDir = skillFile.parent,
                                            skillFile = skillFile,
                                        ),
                                    )
                            }
                        }
                }
            }
        }
        return grouped.mapValues { (_, locations) ->
            locations.sortedBy { skillSourcePriority(it.source) }
        }
    }

    private fun managedLocalRoot(): Path = homeDir.resolve(".codex/skills")

    private fun skillRoots(): Map<SkillSource, Path> = EnumMap<SkillSource, Path>(SkillSource::class.java).apply {
        put(SkillSource.LOCAL, managedLocalRoot())
        put(SkillSource.SUPERPOWERS, homeDir.resolve(".codex/superpowers/skills"))
        put(SkillSource.AGENTS, homeDir.resolve(".agents/skills"))
    }

    private fun isDeletable(location: SkillLocation): Boolean {
        if (location.source == SkillSource.SUPERPOWERS) return false
        return Files.isWritable(location.skillDir.parent ?: location.skillDir)
    }

    private data class ParsedSkillDescriptor(
        val name: String,
        val description: String,
    )

    private fun parseSkillDescriptor(skillFile: Path): ParsedSkillDescriptor? {
        val lines = runCatching { skillFile.readLines() }.getOrNull() ?: return null
        val frontMatter = extractFrontMatter(lines) ?: return null
        val name = frontMatter["name"]?.trim()?.trim('"')?.takeIf(String::isNotBlank) ?: return null
        val description = frontMatter["description"]?.trim()?.trim('"').orEmpty()
        return ParsedSkillDescriptor(name = name, description = description)
    }

    private fun extractFrontMatter(lines: List<String>): Map<String, String>? {
        if (lines.firstOrNull()?.trim() != "---") return null
        val fields = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val trimmed = line.trim()
            if (trimmed == "---") return fields
            val separatorIndex = trimmed.indexOf(':')
            if (separatorIndex <= 0) continue
            fields[trimmed.substring(0, separatorIndex).trim()] = trimmed.substring(separatorIndex + 1).trim()
        }
        return null
    }

    private fun copyDirectory(source: Path, target: Path) {
        Files.walkFileTree(source, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()))
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.copy(
                    file,
                    target.resolve(source.relativize(file).toString()),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteRecursively(path: Path) {
        Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.deleteIfExists(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
                Files.deleteIfExists(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun skillSourcePriority(source: SkillSource): Int = when (source) {
        SkillSource.LOCAL -> 0
        SkillSource.SUPERPOWERS -> 1
        SkillSource.AGENTS -> 2
    }

    private fun slashSkillSortPriority(name: String): Int = when (name) {
        "using-superpowers" -> 0
        "brainstorming" -> 1
        "systematic-debugging" -> 2
        "test-driven-development" -> 3
        "writing-plans" -> 4
        "executing-plans" -> 5
        "verification-before-completion" -> 6
        else -> 100
    }

    companion object {
        /** Matches `$skill-name` references in prompt text for disabled-skill validation. */
        internal val SKILL_TOKEN_REGEX = Regex("(?<!\\S)\\$([A-Za-z0-9._-]+)")
    }
}
