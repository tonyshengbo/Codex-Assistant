package com.auracode.assistant.settings.skills

import java.nio.file.Files
import java.nio.file.FileVisitOption
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines

/** Describes the parsed front-matter metadata for one skill directory. */
internal data class ParsedSkillDescriptor(
    val name: String,
    val description: String,
)

/** Parses one `SKILL.md` file into a minimal descriptor used across skill loaders. */
internal fun parseSkillDescriptor(skillFile: Path): ParsedSkillDescriptor? {
    val lines = runCatching { skillFile.readLines() }.getOrNull() ?: return null
    val frontMatter = extractFrontMatter(lines) ?: return null
    val name = frontMatter["name"]?.trim()?.trim('"')?.takeIf(String::isNotBlank) ?: return null
    val description = frontMatter["description"]?.trim()?.trim('"').orEmpty()
    return ParsedSkillDescriptor(name = name, description = description)
}

/**
 * Reads the body content after the front-matter closing marker in SKILL.md.
 * Used to inject skill directives as system prompt for engines that don't support native skill token parsing (like Claude CLI).
 */
internal fun readSkillBody(skillFile: Path): String? {
    val lines = runCatching { skillFile.readLines() }.getOrNull() ?: return null
    if (lines.firstOrNull()?.trim() != "---") return null
    // Skip the first line "---", find the position of the closing "---"
    val closingIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (closingIndex < 0) return null
    // front-matter takes 1 (opening ---) + closingIndex lines + 1 (closing ---) = closingIndex + 2 lines
    return lines.drop(closingIndex + 2).joinToString("\n").trim().takeIf { it.isNotBlank() }
}

/** Recursively finds every `SKILL.md` under the supplied root. */
internal fun discoverSkillFilesUnder(root: Path): List<Path> {
    if (!Files.isDirectory(root)) return emptyList()
    return runCatching {
        Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { paths ->
            paths
                .filter { it.isRegularFile() && it.name.equals("SKILL.md", ignoreCase = true) }
                .toList()
        }
    }.getOrDefault(emptyList())
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
