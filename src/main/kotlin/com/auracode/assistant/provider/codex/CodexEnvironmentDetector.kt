package com.auracode.assistant.provider.codex

import com.auracode.assistant.provider.runtime.resolvePreferredShellEnvironment
import com.auracode.assistant.provider.runtime.resolveShellEnvironmentCandidates
import com.auracode.assistant.provider.runtime.RuntimeLaunchResolution
import com.auracode.assistant.provider.runtime.resolveBestShellEnvironment

/** Represents how a required Codex runtime dependency was discovered. */
internal enum class CodexEnvironmentStatus {
    CONFIGURED,
    DETECTED,
    MISSING,
    FAILED,
}

/** Describes the result of checking the Codex runtime environment. */
internal data class CodexEnvironmentCheckResult(
    val codexPath: String = "",
    val nodePath: String = "",
    val codexStatus: CodexEnvironmentStatus = CodexEnvironmentStatus.MISSING,
    val nodeStatus: CodexEnvironmentStatus = CodexEnvironmentStatus.MISSING,
    val appServerStatus: CodexEnvironmentStatus = CodexEnvironmentStatus.MISSING,
    val message: String = "",
)

/** Contains the resolved executables and environment used to launch Codex. */
internal data class CodexEnvironmentResolution(
    val codexPath: String,
    val nodePath: String?,
    val shellEnvironment: Map<String, String>,
    val environmentOverrides: Map<String, String>,
    val codexStatus: CodexEnvironmentStatus = CodexEnvironmentStatus.DETECTED,
    val nodeStatus: CodexEnvironmentStatus = CodexEnvironmentStatus.MISSING,
)

/** Detects Codex and Node executables and prepares environment overrides for launches. */
internal class CodexEnvironmentDetector(
    private val shellEnvironmentLoader: () -> Map<String, String> = ::resolvePreferredShellEnvironment,
    private val shellEnvironmentCandidatesLoader: (() -> List<Map<String, String>>)? = ::resolveShellEnvironmentCandidates,
    private val commonSearchPaths: List<String> = defaultCodexCommonSearchPaths(System.getProperty("os.name").orEmpty()),
    private val executableResolver: CodexExecutableResolver = CodexExecutableResolver(commonSearchPaths = commonSearchPaths),
    private val launchEnvironmentBuilder: CodexLaunchEnvironmentBuilder = CodexLaunchEnvironmentBuilder(),
    private val appServerProbe: CodexRuntimeProbe = CodexRuntimeProbe(),
) {
    @Volatile
    private var cachedShellEnvironment: Map<String, String>? = null

    fun autoDetect(
        configuredCodexPath: String,
        configuredNodePath: String,
    ): CodexEnvironmentCheckResult {
        return inspect(
            configuredCodexPath = configuredCodexPath,
            configuredNodePath = configuredNodePath,
            refreshShellEnvironment = true,
            testAppServer = false,
        )
    }

    fun testEnvironment(
        configuredCodexPath: String,
        configuredNodePath: String,
    ): CodexEnvironmentCheckResult {
        return inspect(
            configuredCodexPath = configuredCodexPath,
            configuredNodePath = configuredNodePath,
            refreshShellEnvironment = true,
            testAppServer = true,
        )
    }

    fun resolveForLaunch(
        configuredCodexPath: String,
        configuredNodePath: String,
    ): CodexEnvironmentResolution {
        val resolution = resolveLaunch(
            commandName = "codex",
            configuredCliPath = configuredCodexPath,
            configuredNodePath = configuredNodePath,
            refresh = false,
        )
        return CodexEnvironmentResolution(
            codexPath = resolution.cliPath,
            nodePath = resolution.nodePath.takeIf { it.isNotBlank() },
            shellEnvironment = resolution.shellEnvironment,
            environmentOverrides = resolution.environmentOverrides,
            codexStatus = resolution.cliStatus,
            nodeStatus = resolution.nodeStatus,
        )
    }

    private fun inspect(
        configuredCodexPath: String,
        configuredNodePath: String,
        refreshShellEnvironment: Boolean,
        testAppServer: Boolean,
    ): CodexEnvironmentCheckResult {
        val resolution = resolveLaunch(
            commandName = "codex",
            configuredCliPath = configuredCodexPath,
            configuredNodePath = configuredNodePath,
            refresh = refreshShellEnvironment,
        )
        val appServerProbeResult: Pair<CodexEnvironmentStatus, String> = when {
            resolution.cliStatus == CodexEnvironmentStatus.MISSING -> CodexEnvironmentStatus.MISSING to buildSummary(
                resolution.cliStatus,
                resolution.nodeStatus,
                CodexEnvironmentStatus.MISSING,
            )
            configuredNodePath.trim().isNotBlank() && resolution.nodeStatus == CodexEnvironmentStatus.FAILED -> CodexEnvironmentStatus.FAILED to buildSummary(
                resolution.cliStatus,
                resolution.nodeStatus,
                CodexEnvironmentStatus.FAILED,
            )
            !testAppServer -> if (resolution.cliStatus == CodexEnvironmentStatus.CONFIGURED || resolution.nodeStatus == CodexEnvironmentStatus.CONFIGURED) {
                CodexEnvironmentStatus.CONFIGURED to buildSummary(
                    resolution.cliStatus,
                    resolution.nodeStatus,
                    CodexEnvironmentStatus.CONFIGURED,
                )
            } else {
                CodexEnvironmentStatus.DETECTED to buildSummary(
                    resolution.cliStatus,
                    resolution.nodeStatus,
                    CodexEnvironmentStatus.DETECTED,
                )
            }
            else -> appServerProbe.probe(
                codexPath = resolution.cliPath,
                environmentOverrides = resolution.environmentOverrides,
            ).let { it.status to it.message }
        }
        return CodexEnvironmentCheckResult(
            codexPath = if (resolution.cliStatus == CodexEnvironmentStatus.MISSING) "" else resolution.cliPath,
            nodePath = resolution.nodePath,
            codexStatus = resolution.cliStatus,
            nodeStatus = resolution.nodeStatus,
            appServerStatus = appServerProbeResult.first,
            message = appServerProbeResult.second,
        )
    }

    private fun resolveLaunch(
        commandName: String,
        configuredCliPath: String,
        configuredNodePath: String,
        refresh: Boolean,
    ): RuntimeLaunchResolution {
        val shellEnvironment = shellEnvironment(
            configuredCodexPath = configuredCliPath,
            configuredNodePath = configuredNodePath,
            refresh = refresh,
        )
        val cli = executableResolver.resolve(
            configuredPath = configuredCliPath,
            commandName = commandName,
            shellEnvironment = shellEnvironment,
        )
        val node = executableResolver.resolve(
            configuredPath = configuredNodePath,
            commandName = "node",
            shellEnvironment = shellEnvironment,
        )
        return RuntimeLaunchResolution(
            cliPath = cli.path?.takeIf { it.isNotBlank() }
                ?: configuredCliPath.trim().takeIf { it.isNotBlank() }
                ?: commandName,
            cliStatus = cli.status,
            nodePath = node.path.orEmpty(),
            nodeStatus = node.status,
            shellEnvironment = shellEnvironment,
            environmentOverrides = launchEnvironmentBuilder.build(
                shellEnvironment = shellEnvironment,
                nodePath = node.path,
            ),
        )
    }

    private fun shellEnvironment(
        configuredCodexPath: String,
        configuredNodePath: String,
        refresh: Boolean,
    ): Map<String, String> {
        if (refresh) {
            cachedShellEnvironment = null
        }
        return cachedShellEnvironment ?: chooseShellEnvironment(
            configuredCodexPath = configuredCodexPath,
            configuredNodePath = configuredNodePath,
        ).also {
            cachedShellEnvironment = it
        }
    }

    private fun chooseShellEnvironment(
        configuredCodexPath: String,
        configuredNodePath: String,
    ): Map<String, String> {
        return resolveBestShellEnvironment(
            executableResolver = executableResolver,
            shellEnvironmentLoader = shellEnvironmentLoader,
            shellEnvironmentCandidatesLoader = shellEnvironmentCandidatesLoader,
            configuredCliPath = configuredCodexPath,
            configuredNodePath = configuredNodePath,
            commandName = "codex",
        )
    }

    private fun buildSummary(
        codexStatus: CodexEnvironmentStatus,
        nodeStatus: CodexEnvironmentStatus,
        appServerStatus: CodexEnvironmentStatus,
    ): String {
        val codexSummary = "Codex ${statusWord(codexStatus)}"
        val nodeSummary = "Node ${statusWord(nodeStatus)}"
        val appServerSummary = "App Server ${statusWord(appServerStatus)}"
        return listOf(codexSummary, nodeSummary, appServerSummary).joinToString(" · ")
    }

    private fun statusWord(status: CodexEnvironmentStatus): String = when (status) {
        CodexEnvironmentStatus.CONFIGURED -> "configured"
        CodexEnvironmentStatus.DETECTED -> "detected"
        CodexEnvironmentStatus.MISSING -> "missing"
        CodexEnvironmentStatus.FAILED -> "failed"
    }
}

internal fun buildLaunchEnvironmentOverrides(
    shellEnvironment: Map<String, String>,
    nodePath: String?,
): Map<String, String> {
    return CodexLaunchEnvironmentBuilder().build(
        shellEnvironment = shellEnvironment,
        nodePath = nodePath,
    )
}
