package com.auracode.assistant.provider.claude

import com.auracode.assistant.provider.codex.CodexCliExecutablePathInspector
import java.util.Locale

/** Represents the lifecycle state of Claude CLI version checks and upgrades. */
internal enum class ClaudeCliVersionCheckStatus {
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

/** Describes the inferred installation source used for Claude CLI upgrade commands. */
internal enum class ClaudeCliUpgradeSource {
    NPM,
    PNPM,
    BUN,
    BREW,
    UNKNOWN,
}

/** Holds the command users can inspect, copy, or execute when upgrading Claude CLI. */
internal data class ClaudeCliUpgradeAction(
    val source: ClaudeCliUpgradeSource,
    val displayCommand: String,
    val command: List<String>,
    val isUpgradeSupported: Boolean,
)

/** Stores the latest known Claude CLI version state consumed by the runtime settings UI. */
internal data class ClaudeCliVersionSnapshot(
    val checkStatus: ClaudeCliVersionCheckStatus = ClaudeCliVersionCheckStatus.IDLE,
    val currentVersion: String = "",
    val latestVersion: String = "",
    val ignoredVersion: String = "",
    val upgradeSource: ClaudeCliUpgradeSource = ClaudeCliUpgradeSource.UNKNOWN,
    val displayCommand: String = claudeCliUpgradeActionFor(ClaudeCliUpgradeSource.UNKNOWN).displayCommand,
    val isUpgradeSupported: Boolean = false,
    val lastCheckedAt: Long = 0L,
    val message: String = "",
)

/** Parses and compares semantic versions emitted by Claude CLI and npm metadata. */
internal data class ClaudeCliSemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String? = null,
) : Comparable<ClaudeCliSemVer> {
    override fun compareTo(other: ClaudeCliSemVer): Int {
        return compareValuesBy(this, other, ClaudeCliSemVer::major, ClaudeCliSemVer::minor, ClaudeCliSemVer::patch)
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

/** Extracts the first semantic version found in a Claude CLI output line. */
internal fun parseClaudeCliSemVer(raw: String): ClaudeCliSemVer? {
    val match = CLAUDE_SEMVER_REGEX.find(raw) ?: return null
    return ClaudeCliSemVer(
        major = match.groupValues[1].toInt(),
        minor = match.groupValues[2].toInt(),
        patch = match.groupValues[3].toInt(),
        prerelease = match.groupValues.getOrNull(4)?.ifBlank { null },
    )
}

/** Infers the most likely installation source for the resolved Claude executable path. */
internal class ClaudeCliUpgradeSourceDetector(
    private val pathInspector: CodexCliExecutablePathInspector = CodexCliExecutablePathInspector(),
) {
    /** Detects the best matching package manager from the current Claude executable location. */
    fun detect(claudePath: String): ClaudeCliUpgradeSource {
        val inspectedPaths = pathInspector.inspect(claudePath)
        val candidates = listOf(inspectedPaths.entryPath, inspectedPaths.resolvedPath)
            .map { it.orEmpty().trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
        if (candidates.isEmpty()) return ClaudeCliUpgradeSource.UNKNOWN
        return when {
            candidates.any { it.contains("/pnpm/") || it.contains("\\pnpm\\") } -> ClaudeCliUpgradeSource.PNPM
            candidates.any { it.contains("/.bun/bin/") || it.contains("\\.bun\\bin\\") } -> ClaudeCliUpgradeSource.BUN
            candidates.any(::looksLikeNpmInstall) -> ClaudeCliUpgradeSource.NPM
            candidates.any(::looksLikeBrewInstall) -> ClaudeCliUpgradeSource.BREW
            else -> ClaudeCliUpgradeSource.UNKNOWN
        }
    }
}

/** Rebuilds a stable snapshot from cached values so the UI survives IDE restarts without losing state. */
internal fun restoreClaudeCliVersionSnapshot(
    currentVersion: String,
    latestVersion: String,
    ignoredVersion: String,
    lastCheckedAt: Long,
    action: ClaudeCliUpgradeAction,
): ClaudeCliVersionSnapshot {
    val normalizedCurrent = currentVersion.trim()
    val normalizedLatest = latestVersion.trim()
    val normalizedIgnored = ignoredVersion.trim()
    val status = deriveClaudeCliVersionStatus(
        currentVersion = normalizedCurrent,
        latestVersion = normalizedLatest,
        lastCheckedAt = lastCheckedAt,
    )
    return ClaudeCliVersionSnapshot(
        checkStatus = status,
        currentVersion = normalizedCurrent,
        latestVersion = normalizedLatest,
        ignoredVersion = normalizedIgnored,
        upgradeSource = action.source,
        displayCommand = action.displayCommand,
        isUpgradeSupported = action.isUpgradeSupported,
        lastCheckedAt = lastCheckedAt.coerceAtLeast(0L),
        message = defaultClaudeCliVersionMessage(status),
    )
}

/** Produces the long-lived status used after refreshes and across IDE restarts. */
internal fun deriveClaudeCliVersionStatus(
    currentVersion: String,
    latestVersion: String,
    lastCheckedAt: Long,
): ClaudeCliVersionCheckStatus {
    val normalizedCurrent = currentVersion.trim()
    val normalizedLatest = latestVersion.trim()
    if (normalizedCurrent.isBlank() && normalizedLatest.isBlank() && lastCheckedAt <= 0L) {
        return ClaudeCliVersionCheckStatus.IDLE
    }
    if (normalizedCurrent.isBlank()) {
        return if (lastCheckedAt > 0L) {
            ClaudeCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE
        } else {
            ClaudeCliVersionCheckStatus.IDLE
        }
    }
    if (normalizedLatest.isBlank()) {
        return if (lastCheckedAt > 0L) {
            ClaudeCliVersionCheckStatus.REMOTE_CHECK_FAILED
        } else {
            ClaudeCliVersionCheckStatus.IDLE
        }
    }
    val current = parseClaudeCliSemVer(normalizedCurrent)
    val latest = parseClaudeCliSemVer(normalizedLatest)
    return when {
        current == null -> ClaudeCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE
        latest == null -> ClaudeCliVersionCheckStatus.REMOTE_CHECK_FAILED
        latest > current -> ClaudeCliVersionCheckStatus.UPDATE_AVAILABLE
        else -> ClaudeCliVersionCheckStatus.UP_TO_DATE
    }
}

/** Returns the default status copy shown in the runtime settings UI. */
internal fun defaultClaudeCliVersionMessage(status: ClaudeCliVersionCheckStatus): String = when (status) {
    ClaudeCliVersionCheckStatus.IDLE -> ""
    ClaudeCliVersionCheckStatus.CHECKING -> "Checking Claude CLI version..."
    ClaudeCliVersionCheckStatus.UP_TO_DATE -> "Claude CLI is up to date."
    ClaudeCliVersionCheckStatus.UPDATE_AVAILABLE -> "A newer Claude CLI version is available."
    ClaudeCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE -> "Unable to read the installed Claude CLI version."
    ClaudeCliVersionCheckStatus.REMOTE_CHECK_FAILED -> "Unable to fetch the latest Claude CLI version."
    ClaudeCliVersionCheckStatus.UPGRADE_IN_PROGRESS -> "Upgrading Claude CLI..."
    ClaudeCliVersionCheckStatus.UPGRADE_SUCCEEDED -> "Claude CLI was upgraded successfully."
    ClaudeCliVersionCheckStatus.UPGRADE_FAILED -> "Claude CLI upgrade failed."
}

/** Returns the user-facing upgrade action for the provided installation source. */
internal fun claudeCliUpgradeActionFor(source: ClaudeCliUpgradeSource): ClaudeCliUpgradeAction {
    return when (source) {
        ClaudeCliUpgradeSource.NPM -> ClaudeCliUpgradeAction(
            source = source,
            displayCommand = "npm install -g @anthropic-ai/claude-code@latest",
            command = listOf("npm", "install", "-g", "@anthropic-ai/claude-code@latest"),
            isUpgradeSupported = true,
        )
        ClaudeCliUpgradeSource.PNPM -> ClaudeCliUpgradeAction(
            source = source,
            displayCommand = "pnpm add -g @anthropic-ai/claude-code@latest",
            command = listOf("pnpm", "add", "-g", "@anthropic-ai/claude-code@latest"),
            isUpgradeSupported = true,
        )
        ClaudeCliUpgradeSource.BUN -> ClaudeCliUpgradeAction(
            source = source,
            displayCommand = "bun add -g @anthropic-ai/claude-code@latest",
            command = listOf("bun", "add", "-g", "@anthropic-ai/claude-code@latest"),
            isUpgradeSupported = true,
        )
        ClaudeCliUpgradeSource.BREW -> ClaudeCliUpgradeAction(
            source = source,
            displayCommand = "brew upgrade claude-code",
            command = listOf("brew", "upgrade", "claude-code"),
            isUpgradeSupported = true,
        )
        ClaudeCliUpgradeSource.UNKNOWN -> ClaudeCliUpgradeAction(
            source = source,
            displayCommand = "npm install -g @anthropic-ai/claude-code@latest",
            command = emptyList(),
            isUpgradeSupported = false,
        )
    }
}

/** Compares prerelease markers consistently with semantic version rules. */
private fun comparePrerelease(left: String?, right: String?): Int {
    return when {
        left.isNullOrBlank() && right.isNullOrBlank() -> 0
        left.isNullOrBlank() -> 1
        right.isNullOrBlank() -> -1
        else -> left.compareTo(right)
    }
}

private val CLAUDE_SEMVER_REGEX = Regex("""(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?""")

private fun looksLikeNpmInstall(path: String): Boolean {
    return path.contains("/node_modules/") || path.contains("\\node_modules\\") || path.endsWith("/npm") || path.endsWith("\\npm")
}

private fun looksLikeBrewInstall(path: String): Boolean {
    return path.contains("/homebrew/") || path.contains("/cellar/") || path.contains("\\homebrew\\") || path.contains("\\cellar\\")
}
