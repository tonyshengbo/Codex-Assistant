package com.auracode.assistant.provider.claude

import com.auracode.assistant.provider.codex.CodexExecutableResolver
import com.auracode.assistant.provider.codex.buildLaunchEnvironmentOverrides
import com.auracode.assistant.provider.runtime.RuntimeUpgradeCommandResolver
import com.auracode.assistant.settings.AgentSettingsService
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Represents the result of running an external command for Claude version management. */
internal data class ClaudeCliCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/** Reads remote npm metadata and local CLI state for Claude version management. */
internal class ClaudeCliVersionService(
    private val settingsService: AgentSettingsService,
    private val sourceDetector: ClaudeCliUpgradeSourceDetector = ClaudeCliUpgradeSourceDetector(),
    private val executableResolver: CodexExecutableResolver = CodexExecutableResolver(),
    private val upgradeCommandResolver: RuntimeUpgradeCommandResolver = RuntimeUpgradeCommandResolver(),
    private val shellEnvironmentLoader: () -> Map<String, String> = { System.getenv() },
    private val commandRunner: (List<String>, Map<String, String>) -> ClaudeCliCommandResult = ::runClaudeCliCommand,
    private val latestVersionFetcher: () -> String = ::fetchLatestClaudeCliVersionFromNpm,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class ClaudeLaunchResolution(
        val claudePath: String,
        val environmentOverrides: Map<String, String>,
    )

    private val cachedUpgradeAction: ClaudeCliUpgradeAction
        get() = detectUpgradeAction()

    @Volatile
    private var cachedSnapshot: ClaudeCliVersionSnapshot = restoreClaudeCliVersionSnapshot(
        currentVersion = settingsService.claudeCliLastKnownCurrentVersion(),
        latestVersion = settingsService.claudeCliLastKnownLatestVersion(),
        ignoredVersion = settingsService.claudeCliIgnoredVersion(),
        lastCheckedAt = settingsService.claudeCliLastCheckAt(),
        action = cachedUpgradeAction,
    )

    /** Returns the most recent cached Claude version snapshot. */
    fun snapshot(): ClaudeCliVersionSnapshot = cachedSnapshot

    /** Returns true when the runtime should refresh Claude version metadata again. */
    fun shouldRefresh(force: Boolean = false): Boolean {
        if (force) return true
        val elapsed = clock() - settingsService.claudeCliLastCheckAt()
        return elapsed >= AUTO_REFRESH_INTERVAL_MS || cachedSnapshot.lastCheckedAt == 0L
    }

    /** Refreshes local and remote version metadata and updates the cached snapshot. */
    fun refresh(force: Boolean = false): ClaudeCliVersionSnapshot {
        if (!shouldRefresh(force = force)) {
            val restored = restoreSnapshotFromCache(action = cachedUpgradeAction)
            cachedSnapshot = restored
            return restored
        }
        val resolution = resolveForLaunch()
        val source = sourceDetector.detect(resolution.claudePath)
        val action = claudeCliUpgradeActionFor(source)
        val currentVersion = readInstalledVersion(resolution)
        val ignoredVersion = settingsService.claudeCliIgnoredVersion()
        val now = clock()
        val latestVersion = runCatching(latestVersionFetcher).getOrNull()

        val nextSnapshot = when {
            currentVersion == null -> ClaudeCliVersionSnapshot(
                checkStatus = ClaudeCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE,
                latestVersion = latestVersion.orEmpty(),
                ignoredVersion = ignoredVersion,
                upgradeSource = source,
                displayCommand = action.displayCommand,
                isUpgradeSupported = action.isUpgradeSupported,
                lastCheckedAt = now,
                message = defaultClaudeCliVersionMessage(ClaudeCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE),
            )
            latestVersion.isNullOrBlank() -> ClaudeCliVersionSnapshot(
                checkStatus = ClaudeCliVersionCheckStatus.REMOTE_CHECK_FAILED,
                currentVersion = currentVersion.toString(),
                ignoredVersion = ignoredVersion,
                upgradeSource = source,
                displayCommand = action.displayCommand,
                isUpgradeSupported = action.isUpgradeSupported,
                lastCheckedAt = now,
                message = defaultClaudeCliVersionMessage(ClaudeCliVersionCheckStatus.REMOTE_CHECK_FAILED),
            )
            parseClaudeCliSemVer(latestVersion) == null -> ClaudeCliVersionSnapshot(
                checkStatus = ClaudeCliVersionCheckStatus.REMOTE_CHECK_FAILED,
                currentVersion = currentVersion.toString(),
                latestVersion = latestVersion,
                ignoredVersion = ignoredVersion,
                upgradeSource = source,
                displayCommand = action.displayCommand,
                isUpgradeSupported = action.isUpgradeSupported,
                lastCheckedAt = now,
                message = "The latest Claude CLI version response could not be parsed.",
            )
            parseClaudeCliSemVer(latestVersion)!! > currentVersion -> ClaudeCliVersionSnapshot(
                checkStatus = ClaudeCliVersionCheckStatus.UPDATE_AVAILABLE,
                currentVersion = currentVersion.toString(),
                latestVersion = latestVersion,
                ignoredVersion = ignoredVersion,
                upgradeSource = source,
                displayCommand = action.displayCommand,
                isUpgradeSupported = action.isUpgradeSupported,
                lastCheckedAt = now,
                message = defaultClaudeCliVersionMessage(ClaudeCliVersionCheckStatus.UPDATE_AVAILABLE),
            )
            else -> ClaudeCliVersionSnapshot(
                checkStatus = ClaudeCliVersionCheckStatus.UP_TO_DATE,
                currentVersion = currentVersion.toString(),
                latestVersion = latestVersion,
                ignoredVersion = ignoredVersion,
                upgradeSource = source,
                displayCommand = action.displayCommand,
                isUpgradeSupported = action.isUpgradeSupported,
                lastCheckedAt = now,
                message = defaultClaudeCliVersionMessage(ClaudeCliVersionCheckStatus.UP_TO_DATE),
            )
        }
        persistSnapshot(nextSnapshot)
        return nextSnapshot
    }

    /** Executes the inferred upgrade command and refreshes the snapshot afterwards. */
    fun upgrade(): ClaudeCliVersionSnapshot {
        val resolution = resolveForLaunch()
        val action = claudeCliUpgradeActionFor(sourceDetector.detect(resolution.claudePath))
        val previousVersion = parseClaudeCliSemVer(cachedSnapshot.currentVersion)
        if (!action.isUpgradeSupported) {
            val unsupported = cachedSnapshot.copy(
                checkStatus = ClaudeCliVersionCheckStatus.UPGRADE_FAILED,
                message = "Automatic upgrade is unavailable for the current Claude CLI installation source.",
            )
            cachedSnapshot = unsupported
            return unsupported
        }
        cachedSnapshot = cachedSnapshot.copy(
            checkStatus = ClaudeCliVersionCheckStatus.UPGRADE_IN_PROGRESS,
            message = "Upgrading Claude CLI...",
        )
        val resolvedCommand = upgradeCommandResolver.resolve(
            command = action.command,
            shellEnvironment = resolution.environmentOverrides,
        )
        val result = commandRunner(resolvedCommand, resolution.environmentOverrides)
        if (result.exitCode != 0) {
            val failed = cachedSnapshot.copy(
                checkStatus = ClaudeCliVersionCheckStatus.UPGRADE_FAILED,
                message = result.stderr.ifBlank { result.stdout }.ifBlank { "Claude CLI upgrade failed." },
            )
            cachedSnapshot = failed
            return failed
        }
        val refreshed = refresh(force = true)
        val refreshedCurrent = parseClaudeCliSemVer(refreshed.currentVersion)
        val refreshedLatest = parseClaudeCliSemVer(refreshed.latestVersion)
        val currentAdvanced = refreshedCurrent != null && previousVersion != null && refreshedCurrent > previousVersion
        val confirmedLatest = refreshedCurrent != null && refreshedLatest != null && refreshedCurrent >= refreshedLatest
        return if (refreshed.checkStatus == ClaudeCliVersionCheckStatus.UP_TO_DATE || confirmedLatest || currentAdvanced) {
            refreshed.copy(
                checkStatus = ClaudeCliVersionCheckStatus.UPGRADE_SUCCEEDED,
                message = if (refreshed.checkStatus == ClaudeCliVersionCheckStatus.REMOTE_CHECK_FAILED) {
                    "Claude CLI was upgraded successfully, but the latest version could not be confirmed."
                } else {
                    defaultClaudeCliVersionMessage(ClaudeCliVersionCheckStatus.UPGRADE_SUCCEEDED)
                },
            ).also { cachedSnapshot = it }
        } else {
            refreshed.copy(
                checkStatus = ClaudeCliVersionCheckStatus.UPGRADE_FAILED,
                message = "Claude CLI upgrade finished, but the installed version could not be confirmed.",
            ).also { cachedSnapshot = it }
        }
    }

    /** Updates the ignored version marker and keeps the cached snapshot in sync. */
    fun ignoreVersion(version: String): ClaudeCliVersionSnapshot {
        settingsService.setClaudeCliIgnoredVersion(version)
        val nextSnapshot = cachedSnapshot.copy(ignoredVersion = version.trim())
        cachedSnapshot = nextSnapshot
        return nextSnapshot
    }

    /** Persists the reminder marker for the latest notified version. */
    fun markNotified(version: String) {
        settingsService.setClaudeCliLastNotifiedVersion(version)
    }

    /** Returns true when the snapshot should emit a one-shot reminder notification. */
    fun shouldNotify(snapshot: ClaudeCliVersionSnapshot): Boolean {
        val latest = snapshot.latestVersion.trim()
        if (snapshot.checkStatus != ClaudeCliVersionCheckStatus.UPDATE_AVAILABLE) return false
        if (latest.isBlank()) return false
        if (snapshot.ignoredVersion.trim() == latest) return false
        return settingsService.claudeCliLastNotifiedVersion() != latest
    }

    /** Resolves the runnable Claude executable and environment overrides from current settings. */
    private fun resolveForLaunch(): ClaudeLaunchResolution {
        val shellEnvironment = shellEnvironmentLoader()
        val configuredClaudePath = settingsService.state.executablePathFor("claude")
        val configuredNodePath = settingsService.nodeExecutablePath()
        val claude = executableResolver.resolve(
            configuredPath = configuredClaudePath,
            commandName = "claude",
            shellEnvironment = shellEnvironment,
        )
        val node = executableResolver.resolve(
            configuredPath = configuredNodePath,
            commandName = "node",
            shellEnvironment = shellEnvironment,
        )
        val claudePath = claude.path?.takeIf { it.isNotBlank() }
            ?: configuredClaudePath.trim().takeIf { it.isNotBlank() }
            ?: "claude"
        return ClaudeLaunchResolution(
            claudePath = claudePath,
            environmentOverrides = buildLaunchEnvironmentOverrides(
                shellEnvironment = shellEnvironment,
                nodePath = node.path,
            ),
        )
    }

    /** Reads the installed Claude CLI version from the current executable. */
    private fun readInstalledVersion(resolution: ClaudeLaunchResolution): ClaudeCliSemVer? {
        val result = runCatching {
            commandRunner(listOf(resolution.claudePath, "--version"), resolution.environmentOverrides)
        }.getOrNull() ?: return null
        val output = result.stdout.ifBlank { result.stderr }
        return if (result.exitCode == 0) parseClaudeCliSemVer(output) else null
    }

    /** Persists the latest resolved snapshot to the settings service cache. */
    private fun persistSnapshot(snapshot: ClaudeCliVersionSnapshot) {
        cachedSnapshot = snapshot
        settingsService.setClaudeCliLastCheckAt(snapshot.lastCheckedAt)
        settingsService.setClaudeCliLastKnownCurrentVersion(snapshot.currentVersion)
        settingsService.setClaudeCliLastKnownLatestVersion(snapshot.latestVersion)
    }

    /** Restores the UI snapshot from persisted cache without forcing a new network request. */
    private fun restoreSnapshotFromCache(action: ClaudeCliUpgradeAction): ClaudeCliVersionSnapshot {
        return restoreClaudeCliVersionSnapshot(
            currentVersion = settingsService.claudeCliLastKnownCurrentVersion(),
            latestVersion = settingsService.claudeCliLastKnownLatestVersion(),
            ignoredVersion = settingsService.claudeCliIgnoredVersion(),
            lastCheckedAt = settingsService.claudeCliLastCheckAt(),
            action = action,
        )
    }

    /** Recomputes the best upgrade action for the current Claude executable path. */
    private fun detectUpgradeAction(): ClaudeCliUpgradeAction {
        return claudeCliUpgradeActionFor(sourceDetector.detect(resolveForLaunch().claudePath))
    }

    companion object {
        private val AUTO_REFRESH_INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(12)
    }
}

private fun runClaudeCliCommand(
    command: List<String>,
    environment: Map<String, String>,
): ClaudeCliCommandResult {
    val process = ProcessBuilder(command)
        .apply {
            redirectErrorStream(false)
            environment().putAll(environment)
        }
        .start()
    val finished = process.waitFor(2, TimeUnit.MINUTES)
    if (!finished) {
        process.destroyForcibly()
        return ClaudeCliCommandResult(exitCode = -1, stdout = "", stderr = "Command timed out.")
    }
    val stdout = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)
    val stderr = process.errorStream.bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)
    return ClaudeCliCommandResult(
        exitCode = process.exitValue(),
        stdout = stdout.trim(),
        stderr = stderr.trim(),
    )
}

private fun fetchLatestClaudeCliVersionFromNpm(): String {
    val connection = URL("https://registry.npmjs.org/@anthropic-ai/claude-code/latest").openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    connection.setRequestProperty("Accept", "application/json")
    return connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
        val payload = reader.readText()
        Regex(""""version"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)?.trim().orEmpty()
    }.ifBlank {
        error("Missing npm version field.")
    }
}
