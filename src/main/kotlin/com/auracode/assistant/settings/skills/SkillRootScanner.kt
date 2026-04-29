package com.auracode.assistant.settings.skills

import java.nio.file.Path

/** Stores one discovered skill directory under an imported root. */
internal data class ScannedSkillRootEntry(
    val name: String,
    val description: String,
    val skillDir: Path,
    val skillFile: Path,
)

/** Describes the scan result for one imported root. */
internal data class SkillRootScanResult(
    val skills: List<ScannedSkillRootEntry>,
    val invalidSkillFiles: List<Path>,
)

/** Scans an imported root and resolves every valid skill directory beneath it. */
internal class SkillRootScanner {
    /** Returns both valid skills and invalid `SKILL.md` files for import diagnostics. */
    fun scan(root: Path): SkillRootScanResult {
        val valid = mutableListOf<ScannedSkillRootEntry>()
        val invalid = mutableListOf<Path>()
        discoverSkillFilesUnder(root).forEach { skillFile ->
            val descriptor = parseSkillDescriptor(skillFile)
            if (descriptor == null) {
                invalid.add(skillFile)
            } else {
                valid.add(
                    ScannedSkillRootEntry(
                    name = descriptor.name,
                    description = descriptor.description.ifBlank { descriptor.name },
                    skillDir = skillFile.parent,
                    skillFile = skillFile,
                    ),
                )
            }
        }
        return SkillRootScanResult(
            skills = valid.sortedBy { it.name.lowercase() },
            invalidSkillFiles = invalid.sortedBy { it.toString() },
        )
    }
}
