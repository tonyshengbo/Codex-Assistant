package com.auracode.assistant.provider.codex

import java.nio.file.Files
import java.nio.file.Path
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

/** Captures the executable entry path together with its resolved target when available. */
internal data class CodexCliExecutablePaths(
    val entryPath: String,
    val resolvedPath: String? = null,
)

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
internal class CodexCliUpgradeSourceDetector(
    private val pathInspector: CodexCliExecutablePathInspector = CodexCliExecutablePathInspector(),
) {
    fun detect(codexPath: String): CodexCliUpgradeSource {
        val inspectedPaths = pathInspector.inspect(codexPath)
        val candidates = listOf(inspectedPaths.entryPath, inspectedPaths.resolvedPath)
            .map { it.orEmpty().trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
        if (candidates.isEmpty()) return CodexCliUpgradeSource.UNKNOWN
        return when {
            candidates.any { it.contains("/pnpm/") || it.contains("\\pnpm\\") } -> CodexCliUpgradeSource.PNPM
            candidates.any { it.contains("/.bun/bin/") || it.contains("\\.bun\\bin\\") } -> CodexCliUpgradeSource.BUN
            candidates.any(::looksLikeNpmInstall) -> CodexCliUpgradeSource.NPM
            candidates.any(::looksLikeBrewInstall) -> CodexCliUpgradeSource.BREW
            else -> CodexCliUpgradeSource.UNKNOWN
        }
    }
}

/** Resolves the executable path so installation source inference can inspect the true target. */
internal open class CodexCliExecutablePathInspector {
    open fun inspect(codexPath: String): CodexCliExecutablePaths {
        val entryPath = codexPath.trim()
        if (entryPath.isBlank()) return CodexCliExecutablePaths(entryPath = "")
        val resolvedPath = runCatching {
            val path = Path.of(entryPath)
            when {
                Files.isSymbolicLink(path) -> path.parent.resolve(Files.readSymbolicLink(path)).normalize().toString()
                Files.exists(path) -> path.toRealPath().toString()
                else -> null
            }
        }.getOrNull()
        return CodexCliExecutablePaths(
            entryPath = entryPath,
            resolvedPath = resolvedPath?.takeIf { it.isNotBlank() && it != entryPath },
        )
    }
}

/** Rebuilds a stable snapshot from cached values so the UI survives IDE restarts without losing state. */
internal fun restoreCodexCliVersionSnapshot(
    currentVersion: String,
    latestVersion: String,
    ignoredVersion: String,
    lastCheckedAt: Long,
    action: CodexCliUpgradeAction,
): CodexCliVersionSnapshot {
    val normalizedCurrent = currentVersion.trim()
    val normalizedLatest = latestVersion.trim()
    val normalizedIgnored = ignoredVersion.trim()
    val status = deriveCodexCliVersionStatus(
        currentVersion = normalizedCurrent,
        latestVersion = normalizedLatest,
        lastCheckedAt = lastCheckedAt,
    )
    return CodexCliVersionSnapshot(
        checkStatus = status,
        currentVersion = normalizedCurrent,
        latestVersion = normalizedLatest,
        ignoredVersion = normalizedIgnored,
        upgradeSource = action.source,
        displayCommand = action.displayCommand,
        isUpgradeSupported = action.isUpgradeSupported,
        lastCheckedAt = lastCheckedAt.coerceAtLeast(0L),
        message = defaultCodexCliVersionMessage(status),
    )
}

/** Produces the long-lived status used after refreshes and across IDE restarts. */
internal fun deriveCodexCliVersionStatus(
    currentVersion: String,
    latestVersion: String,
    lastCheckedAt: Long,
): CodexCliVersionCheckStatus {
    val normalizedCurrent = currentVersion.trim()
    val normalizedLatest = latestVersion.trim()
    if (normalizedCurrent.isBlank() && normalizedLatest.isBlank() && lastCheckedAt <= 0L) {
        return CodexCliVersionCheckStatus.IDLE
    }
    if (normalizedCurrent.isBlank()) {
        return if (lastCheckedAt > 0L) {
            CodexCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE
        } else {
            CodexCliVersionCheckStatus.IDLE
        }
    }
    if (normalizedLatest.isBlank()) {
        return if (lastCheckedAt > 0L) {
            CodexCliVersionCheckStatus.REMOTE_CHECK_FAILED
        } else {
            CodexCliVersionCheckStatus.IDLE
        }
    }
    val current = parseCodexCliSemVer(normalizedCurrent)
    val latest = parseCodexCliSemVer(normalizedLatest)
    return when {
        current == null -> CodexCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE
        latest == null -> CodexCliVersionCheckStatus.REMOTE_CHECK_FAILED
        latest > current -> CodexCliVersionCheckStatus.UPDATE_AVAILABLE
        else -> CodexCliVersionCheckStatus.UP_TO_DATE
    }
}

/** Returns the default status copy shown in the settings UI and notifications. */
internal fun defaultCodexCliVersionMessage(status: CodexCliVersionCheckStatus): String = when (status) {
    CodexCliVersionCheckStatus.IDLE -> ""
    CodexCliVersionCheckStatus.CHECKING -> "Checking Codex CLI version..."
    CodexCliVersionCheckStatus.UP_TO_DATE -> "Codex CLI is up to date."
    CodexCliVersionCheckStatus.UPDATE_AVAILABLE -> "A newer Codex CLI version is available."
    CodexCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE -> "Unable to read the installed Codex CLI version."
    CodexCliVersionCheckStatus.REMOTE_CHECK_FAILED -> "Unable to fetch the latest Codex CLI version."
    CodexCliVersionCheckStatus.UPGRADE_IN_PROGRESS -> "Upgrading Codex CLI..."
    CodexCliVersionCheckStatus.UPGRADE_SUCCEEDED -> "Codex CLI was upgraded successfully."
    CodexCliVersionCheckStatus.UPGRADE_FAILED -> "Codex CLI upgrade failed."
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

private fun looksLikeNpmInstall(path: String): Boolean {
    return path.contains("/.nvm/versions/node/") ||
        path.contains("\\.nvm\\versions\\node\\") ||
        path.contains("/volta/bin/") ||
        path.contains("\\volta\\bin\\") ||
        path.contains("/appdata/roaming/npm/") ||
        path.contains("\\appdata\\roaming\\npm\\") ||
        path.contains("/lib/node_modules/@openai/codex/") ||
        path.contains("\\lib\\node_modules\\@openai\\codex\\") ||
        path.contains("/node_modules/@openai/codex/") ||
        path.contains("\\node_modules\\@openai\\codex\\")
}

private fun looksLikeBrewInstall(path: String): Boolean {
    return path.contains("/cellar/codex/") ||
        path.contains("\\cellar\\codex\\") ||
        path.contains("/homebrew/cellar/codex/") ||
        path.contains("\\homebrew\\cellar\\codex\\")
}

private val SEMVER_REGEX = Regex("""(?:^|[^0-9])v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?""")
