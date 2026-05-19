package com.auracode.assistant.provider.codex

import com.auracode.assistant.provider.runtime.RuntimePackageManager
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.provider.runtime.RuntimeUpgradeCommandResolver
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Represents the result of running an external command for version management. */
internal data class CodexCliCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

/** Reads remote npm metadata and local CLI state for Codex version management. */
internal class CodexCliVersionService(
    private val settingsService: AgentSettingsService,
    private val environmentDetector: CodexEnvironmentDetector = CodexEnvironmentDetector(),
    private val sourceDetector: CodexCliUpgradeSourceDetector = CodexCliUpgradeSourceDetector(),
    private val upgradeCommandResolver: RuntimeUpgradeCommandResolver = RuntimeUpgradeCommandResolver(),
    private val commandRunner: (List<String>, Map<String, String>) -> CodexCliCommandResult = ::runCodexCliCommand,
    private val latestVersionFetcher: () -> String = ::fetchLatestCodexCliVersionFromNpm,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cachedUpgradeAction: CodexCliUpgradeAction
        get() = detectUpgradeAction()

    @Volatile
    private var cachedSnapshot: CodexCliVersionSnapshot = restoreCodexCliVersionSnapshot(
        currentVersion = settingsService.codexCliLastKnownCurrentVersion(),
        latestVersion = settingsService.codexCliLastKnownLatestVersion(),
        ignoredVersion = settingsService.codexCliIgnoredVersion(),
        lastCheckedAt = settingsService.codexCliLastCheckAt(),
        action = cachedUpgradeAction,
    )

    fun snapshot(): CodexCliVersionSnapshot = cachedSnapshot

    /** Returns true when a non-forced refresh should hit the network again. */
    fun shouldRefresh(force: Boolean = false): Boolean {
        if (force) return true
        if (!settingsService.codexCliAutoUpdateCheckEnabled()) return false
        val elapsed = clock() - settingsService.codexCliLastCheckAt()
        return elapsed >= AUTO_REFRESH_INTERVAL_MS || cachedSnapshot.lastCheckedAt == 0L
    }

    /** Refreshes local and remote version metadata and updates the cached snapshot. */
    fun refresh(
        force: Boolean = false,
        configuredCodexPathOverride: String? = null,
        configuredNodePathOverride: String? = null,
    ): CodexCliVersionSnapshot {
        if (!force && cachedSnapshot.checkStatus.isTransientOperationInProgress()) {
            return cachedSnapshot
        }
        if (!shouldRefresh(force = force)) {
            val restored = restoreSnapshotFromCache(
                action = detectUpgradeAction(
                    configuredCodexPathOverride = configuredCodexPathOverride,
                    configuredNodePathOverride = configuredNodePathOverride,
                ),
            )
            cachedSnapshot = restored
            return restored
        }
        val resolution = resolveForLaunch(
            configuredCodexPathOverride = configuredCodexPathOverride,
            configuredNodePathOverride = configuredNodePathOverride,
        )
        val source = sourceDetector.detect(resolution.codexPath)
        val action = codexCliUpgradeActionFor(source)
        val currentVersion = readInstalledVersion(resolution)
        val ignoredVersion = settingsService.codexCliIgnoredVersion()
        val now = clock()

        val latestVersion = runCatching(latestVersionFetcher).getOrNull()
        val nextSnapshot = when {
            currentVersion == null -> CodexCliVersionSnapshot(
                checkStatus = if (resolution.codexStatus == CodexEnvironmentStatus.MISSING) {
                    CodexCliVersionCheckStatus.NOT_INSTALLED
                } else {
                    CodexCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE
                },
                latestVersion = latestVersion.orEmpty(),
                ignoredVersion = ignoredVersion,
                upgradeSource = source,
                displayCommand = action.displayCommand,
                isUpgradeSupported = action.isUpgradeSupported,
                lastCheckedAt = now,
                message = defaultCodexCliVersionMessage(
                    if (resolution.codexStatus == CodexEnvironmentStatus.MISSING) {
                        CodexCliVersionCheckStatus.NOT_INSTALLED
                    } else {
                        CodexCliVersionCheckStatus.LOCAL_VERSION_UNAVAILABLE
                    },
                ),
            )
            latestVersion.isNullOrBlank() -> CodexCliVersionSnapshot(
                checkStatus = CodexCliVersionCheckStatus.REMOTE_CHECK_FAILED,
                currentVersion = currentVersion.toString(),
                ignoredVersion = ignoredVersion,
                upgradeSource = source,
                displayCommand = action.displayCommand,
                isUpgradeSupported = action.isUpgradeSupported,
                lastCheckedAt = now,
                message = defaultCodexCliVersionMessage(CodexCliVersionCheckStatus.REMOTE_CHECK_FAILED),
            )
            parseCodexCliSemVer(latestVersion) == null -> CodexCliVersionSnapshot(
                checkStatus = CodexCliVersionCheckStatus.REMOTE_CHECK_FAILED,
                currentVersion = currentVersion.toString(),
                latestVersion = latestVersion,
                ignoredVersion = ignoredVersion,
                upgradeSource = source,
                displayCommand = action.displayCommand,
                isUpgradeSupported = action.isUpgradeSupported,
                lastCheckedAt = now,
                message = "The latest Codex CLI version response could not be parsed.",
            )
            parseCodexCliSemVer(latestVersion)!! > currentVersion -> CodexCliVersionSnapshot(
                checkStatus = CodexCliVersionCheckStatus.UPDATE_AVAILABLE,
                currentVersion = currentVersion.toString(),
                latestVersion = latestVersion,
                ignoredVersion = ignoredVersion,
                upgradeSource = source,
                displayCommand = action.displayCommand,
                isUpgradeSupported = action.isUpgradeSupported,
                lastCheckedAt = now,
                message = defaultCodexCliVersionMessage(CodexCliVersionCheckStatus.UPDATE_AVAILABLE),
            )
            else -> CodexCliVersionSnapshot(
                checkStatus = CodexCliVersionCheckStatus.UP_TO_DATE,
                currentVersion = currentVersion.toString(),
                latestVersion = latestVersion,
                ignoredVersion = ignoredVersion,
                upgradeSource = source,
                displayCommand = action.displayCommand,
                isUpgradeSupported = action.isUpgradeSupported,
                lastCheckedAt = now,
                message = defaultCodexCliVersionMessage(CodexCliVersionCheckStatus.UP_TO_DATE),
            )
        }
        persistSnapshot(nextSnapshot)
        return nextSnapshot
    }

    /** Updates the ignored version marker and keeps the cached snapshot in sync. */
    fun ignoreVersion(version: String): CodexCliVersionSnapshot {
        settingsService.setCodexCliIgnoredVersion(version)
        val nextSnapshot = cachedSnapshot.copy(ignoredVersion = version.trim())
        cachedSnapshot = nextSnapshot
        return nextSnapshot
    }

    /** Returns true when the snapshot should emit a one-shot reminder notification. */
    fun shouldNotify(snapshot: CodexCliVersionSnapshot): Boolean {
        val latest = snapshot.latestVersion.trim()
        if (snapshot.checkStatus != CodexCliVersionCheckStatus.UPDATE_AVAILABLE) return false
        if (latest.isBlank()) return false
        if (snapshot.ignoredVersion.trim() == latest) return false
        return settingsService.codexCliLastNotifiedVersion() != latest
    }

    /** Persists the reminder marker for the latest notified version. */
    fun markNotified(version: String) {
        settingsService.setCodexCliLastNotifiedVersion(version)
    }

    /** Executes the inferred upgrade command and refreshes the snapshot afterwards. */
    fun upgrade(
        configuredCodexPathOverride: String? = null,
        configuredNodePathOverride: String? = null,
    ): CodexCliVersionSnapshot {
        val resolution = resolveForLaunch(
            configuredCodexPathOverride = configuredCodexPathOverride,
            configuredNodePathOverride = configuredNodePathOverride,
        )
        val action = codexCliUpgradeActionFor(sourceDetector.detect(resolution.codexPath))
        val previousVersion = parseCodexCliSemVer(cachedSnapshot.currentVersion)
        if (!action.isUpgradeSupported) {
            val unsupported = cachedSnapshot.copy(
                checkStatus = CodexCliVersionCheckStatus.UPGRADE_FAILED,
                message = "Automatic upgrade is unavailable for the current Codex CLI installation source.",
            )
            cachedSnapshot = unsupported
            return unsupported
        }
        cachedSnapshot = cachedSnapshot.copy(
            checkStatus = CodexCliVersionCheckStatus.UPGRADE_IN_PROGRESS,
            message = "Upgrading Codex CLI...",
        )
        val resolvedCommand = upgradeCommandResolver.resolve(
            command = action.command,
            shellEnvironment = resolution.environmentOverrides,
        )
        val result = commandRunner(resolvedCommand, resolution.environmentOverrides)
        if (result.exitCode != 0) {
            val failed = cachedSnapshot.copy(
                checkStatus = CodexCliVersionCheckStatus.UPGRADE_FAILED,
                message = result.stderr.ifBlank { result.stdout }.ifBlank { "Codex CLI upgrade failed." },
            )
            cachedSnapshot = failed
            return failed
        }
        val refreshed = refresh(
            force = true,
            configuredCodexPathOverride = configuredCodexPathOverride,
            configuredNodePathOverride = configuredNodePathOverride,
        )
        val refreshedCurrent = parseCodexCliSemVer(refreshed.currentVersion)
        val refreshedLatest = parseCodexCliSemVer(refreshed.latestVersion)
        val currentAdvanced = refreshedCurrent != null && previousVersion != null && refreshedCurrent > previousVersion
        val confirmedLatest = refreshedCurrent != null && refreshedLatest != null && refreshedCurrent >= refreshedLatest
        return if (refreshed.checkStatus == CodexCliVersionCheckStatus.UP_TO_DATE || confirmedLatest || currentAdvanced) {
            refreshed.copy(
                checkStatus = CodexCliVersionCheckStatus.UPGRADE_SUCCEEDED,
                message = if (refreshed.checkStatus == CodexCliVersionCheckStatus.REMOTE_CHECK_FAILED) {
                    "Codex CLI was upgraded successfully, but the latest version could not be confirmed."
                } else {
                    defaultCodexCliVersionMessage(CodexCliVersionCheckStatus.UPGRADE_SUCCEEDED)
                },
            ).also { cachedSnapshot = it }
        } else {
            refreshed.copy(
                checkStatus = CodexCliVersionCheckStatus.UPGRADE_FAILED,
                message = "Codex CLI upgrade finished, but the installed version could not be confirmed.",
            ).also { cachedSnapshot = it }
        }
    }

    /** Executes the selected install command and refreshes the snapshot afterwards. */
    fun install(
        packageManager: RuntimePackageManager,
        configuredCodexPathOverride: String? = null,
        configuredNodePathOverride: String? = null,
    ): CodexCliVersionSnapshot {
        val resolution = resolveForLaunch(
            configuredCodexPathOverride = configuredCodexPathOverride,
            configuredNodePathOverride = configuredNodePathOverride,
        )
        val action = codexCliInstallActionFor(packageManager)
        cachedSnapshot = cachedSnapshot.copy(
            checkStatus = CodexCliVersionCheckStatus.INSTALL_IN_PROGRESS,
            message = defaultCodexCliVersionMessage(CodexCliVersionCheckStatus.INSTALL_IN_PROGRESS),
        )
        val resolvedCommand = upgradeCommandResolver.resolve(
            command = action.command,
            shellEnvironment = resolution.environmentOverrides,
        )
        val result = commandRunner(resolvedCommand, resolution.environmentOverrides)
        if (result.exitCode != 0) {
            val failed = cachedSnapshot.copy(
                checkStatus = CodexCliVersionCheckStatus.INSTALL_FAILED,
                message = result.stderr.ifBlank { result.stdout }.ifBlank {
                    defaultCodexCliVersionMessage(CodexCliVersionCheckStatus.INSTALL_FAILED)
                },
            )
            cachedSnapshot = failed
            return failed
        }
        return refresh(
            force = true,
            configuredCodexPathOverride = configuredCodexPathOverride,
            configuredNodePathOverride = configuredNodePathOverride,
        )
    }

    private fun readInstalledVersion(resolution: CodexEnvironmentResolution): CodexCliSemVer? {
        val result = runCatching {
            commandRunner(listOf(resolution.codexPath, "--version"), resolution.environmentOverrides)
        }.getOrNull() ?: return null
        val output = result.stdout.ifBlank { result.stderr }
        return if (result.exitCode == 0) parseCodexCliSemVer(output) else null
    }

    private fun persistSnapshot(snapshot: CodexCliVersionSnapshot) {
        cachedSnapshot = snapshot
        settingsService.setCodexCliLastCheckAt(snapshot.lastCheckedAt)
        settingsService.setCodexCliLastKnownCurrentVersion(snapshot.currentVersion)
        settingsService.setCodexCliLastKnownLatestVersion(snapshot.latestVersion)
    }

    private fun restoreSnapshotFromCache(action: CodexCliUpgradeAction): CodexCliVersionSnapshot {
        return restoreCodexCliVersionSnapshot(
            currentVersion = settingsService.codexCliLastKnownCurrentVersion(),
            latestVersion = settingsService.codexCliLastKnownLatestVersion(),
            ignoredVersion = settingsService.codexCliIgnoredVersion(),
            lastCheckedAt = settingsService.codexCliLastCheckAt(),
            action = action,
        )
    }

    private fun detectUpgradeAction(
        configuredCodexPathOverride: String? = null,
        configuredNodePathOverride: String? = null,
    ): CodexCliUpgradeAction {
        val resolution = resolveForLaunch(
            configuredCodexPathOverride = configuredCodexPathOverride,
            configuredNodePathOverride = configuredNodePathOverride,
        )
        return codexCliUpgradeActionFor(sourceDetector.detect(resolution.codexPath))
    }

    private fun resolveForLaunch(
        configuredCodexPathOverride: String?,
        configuredNodePathOverride: String?,
    ): CodexEnvironmentResolution {
        return environmentDetector.resolveForLaunch(
            configuredCodexPath = configuredCodexPathOverride ?: settingsService.state.executablePathFor("codex"),
            configuredNodePath = configuredNodePathOverride ?: settingsService.nodeExecutablePath(),
        )
    }

    companion object {
        private val AUTO_REFRESH_INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(12)
    }
}

private fun CodexCliVersionCheckStatus.isTransientOperationInProgress(): Boolean {
    return this == CodexCliVersionCheckStatus.UPGRADE_IN_PROGRESS ||
        this == CodexCliVersionCheckStatus.INSTALL_IN_PROGRESS
}

internal fun runCodexCliCommand(
    command: List<String>,
    environment: Map<String, String>,
): CodexCliCommandResult {
    val process = ProcessBuilder(command)
        .apply {
            redirectErrorStream(false)
            environment().putAll(environment)
        }
        .start()
    val finished = process.waitFor(2, TimeUnit.MINUTES)
    if (!finished) {
        process.destroyForcibly()
        return CodexCliCommandResult(exitCode = -1, stdout = "", stderr = "Command timed out.")
    }
    val stdout = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)
    val stderr = process.errorStream.bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)
    return CodexCliCommandResult(
        exitCode = process.exitValue(),
        stdout = stdout.trim(),
        stderr = stderr.trim(),
    )
}

private fun fetchLatestCodexCliVersionFromNpm(): String {
    val connection = URL("https://registry.npmjs.org/@openai/codex/latest").openConnection() as HttpURLConnection
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
