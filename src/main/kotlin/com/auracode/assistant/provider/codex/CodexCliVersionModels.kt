package com.auracode.assistant.provider.codex

import java.util.Locale

/** Represents the lifecycle state of Codex CLI version checks and upgrades. */
internal enum class CodexCliVersionCheckStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    LOCAL_VERSION_UNAVAILABLE,
    REMOTE_CHECK_FAILED,
    UPGRADE_IN_PROGRESS,
    UPGRADE_SUCCEEDED,
    UPGRADE_FAILED,
}

/** Describes the inferred installation source used for Codex CLI upgrade commands. */
internal enum class CodexCliUpgradeSource {
    NPM,
    PNPM,
    BUN,
    BREW,
    UNKNOWN,
}

/** Holds the command users can inspect, copy, or execute when upgrading Codex CLI. */
internal data class CodexCliUpgradeAction(
    val source: CodexCliUpgradeSource,
    val displayCommand: String,
    val command: List<String>,
    val isUpgradeSupported: Boolean,
)

/** Stores the latest known Codex CLI version state consumed by the settings UI. */
internal data class CodexCliVersionSnapshot(
    val checkStatus: CodexCliVersionCheckStatus = CodexCliVersionCheckStatus.IDLE,
    val currentVersion: String = "",
    val latestVersion: String = "",
    val ignoredVersion: String = "",
    val upgradeSource: CodexCliUpgradeSource = CodexCliUpgradeSource.UNKNOWN,
    val displayCommand: String = codexCliUpgradeActionFor(CodexCliUpgradeSource.UNKNOWN).displayCommand,
    val isUpgradeSupported: Boolean = false,
    val lastCheckedAt: Long = 0L,
    val message: String = "",
) {
    /** Returns true when the latest version is newer than the current version. */
    val hasUpgrade: Boolean
        get() = checkStatus == CodexCliVersionCheckStatus.UPDATE_AVAILABLE
}

/** Parses and compares semantic versions emitted by Codex CLI and npm metadata. */
internal data class CodexCliSemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String? = null,
) : Comparable<CodexCliSemVer> {
    override fun compareTo(other: CodexCliSemVer): Int {
        return compareValuesBy(this, other, CodexCliSemVer::major, CodexCliSemVer::minor, CodexCliSemVer::patch)
            .takeIf { it != 0 }
            ?: comparePrerelease(prerelease, other.prerelease)
    }

    override fun toString(): String {
        return buildString {
            append(major)
            append('.')
            append(minor)
            append('.')
            append(patch)
            prerelease?.takeIf { it.isNotBlank() }?.let {
                append('-')
                append(it)
            }
        }
    }
}

/** Extracts the first semantic version found in a Codex CLI output line. */
internal fun parseCodexCliSemVer(raw: String): CodexCliSemVer? {
    val match = SEMVER_REGEX.find(raw) ?: return null
    return CodexCliSemVer(
        major = match.groupValues[1].toInt(),
        minor = match.groupValues[2].toInt(),
        patch = match.groupValues[3].toInt(),
        prerelease = match.groupValues.getOrNull(4)?.ifBlank { null },
    )
}

/** Infers the most likely installation source for the resolved Codex executable path. */
internal class CodexCliUpgradeSourceDetector {
    fun detect(codexPath: String): CodexCliUpgradeSource {
        val normalized = codexPath.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) return CodexCliUpgradeSource.UNKNOWN
        return when {
            normalized.contains("/pnpm/") || normalized.contains("\\pnpm\\") -> CodexCliUpgradeSource.PNPM
            normalized.contains("/.bun/bin/") || normalized.contains("\\.bun\\bin\\") -> CodexCliUpgradeSource.BUN
            normalized.contains("/.nvm/versions/node/") ||
                normalized.contains("\\.nvm\\versions\\node\\") ||
                normalized.contains("/volta/bin/") ||
                normalized.contains("\\volta\\bin\\") ||
                normalized.contains("/appdata/roaming/npm/") ||
                normalized.contains("\\appdata\\roaming\\npm\\") -> CodexCliUpgradeSource.NPM
            normalized.startsWith("/opt/homebrew/bin/") ||
                normalized.startsWith("/usr/local/homebrew/") ||
                normalized.contains("/cellar/") -> CodexCliUpgradeSource.BREW
            else -> CodexCliUpgradeSource.UNKNOWN
        }
    }
}

/** Returns the user-facing upgrade action for the provided installation source. */
internal fun codexCliUpgradeActionFor(source: CodexCliUpgradeSource): CodexCliUpgradeAction {
    return when (source) {
        CodexCliUpgradeSource.NPM -> CodexCliUpgradeAction(
            source = source,
            displayCommand = "npm install -g @openai/codex@latest",
            command = listOf("npm", "install", "-g", "@openai/codex@latest"),
            isUpgradeSupported = true,
        )
        CodexCliUpgradeSource.PNPM -> CodexCliUpgradeAction(
            source = source,
            displayCommand = "pnpm add -g @openai/codex@latest",
            command = listOf("pnpm", "add", "-g", "@openai/codex@latest"),
            isUpgradeSupported = true,
        )
        CodexCliUpgradeSource.BUN -> CodexCliUpgradeAction(
            source = source,
            displayCommand = "bun add -g @openai/codex@latest",
            command = listOf("bun", "add", "-g", "@openai/codex@latest"),
            isUpgradeSupported = true,
        )
        CodexCliUpgradeSource.BREW -> CodexCliUpgradeAction(
            source = source,
            displayCommand = "brew upgrade codex",
            command = listOf("brew", "upgrade", "codex"),
            isUpgradeSupported = true,
        )
        CodexCliUpgradeSource.UNKNOWN -> CodexCliUpgradeAction(
            source = source,
            displayCommand = "npm install -g @openai/codex@latest",
            command = emptyList(),
            isUpgradeSupported = false,
        )
    }
}

private fun comparePrerelease(left: String?, right: String?): Int {
    return when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> left.compareTo(right)
    }
}

private val SEMVER_REGEX = Regex("""(?:^|[^0-9])v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?""")
