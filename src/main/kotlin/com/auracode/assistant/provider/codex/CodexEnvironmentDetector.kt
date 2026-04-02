package com.auracode.assistant.provider.codex

import java.io.File
import java.util.concurrent.TimeUnit

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
    private val shellEnvironmentLoader: () -> Map<String, String> = ::loadShellEnvironment,
    private val commonSearchPaths: List<String> = defaultCodexCommonSearchPaths(System.getProperty("os.name").orEmpty()),
    private val executableResolver: CodexExecutableResolver = CodexExecutableResolver(commonSearchPaths = commonSearchPaths),
    private val launchEnvironmentBuilder: CodexLaunchEnvironmentBuilder = CodexLaunchEnvironmentBuilder(),
    private val appServerProbe: CodexAppServerProbe = CodexAppServerProbe(),
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
        val shellEnvironment = shellEnvironment(refresh = false)
        val codex = executableResolver.resolve(
            configuredPath = configuredCodexPath,
            commandName = "codex",
            shellEnvironment = shellEnvironment,
        )
        val node = executableResolver.resolve(
            configuredPath = configuredNodePath,
            commandName = "node",
            shellEnvironment = shellEnvironment,
        )
        val codexPath = codex.path?.takeIf { it.isNotBlank() }
            ?: configuredCodexPath.trim().takeIf { it.isNotBlank() }
            ?: "codex"
        return CodexEnvironmentResolution(
            codexPath = codexPath,
            nodePath = node.path?.takeIf { it.isNotBlank() },
            shellEnvironment = shellEnvironment,
            environmentOverrides = launchEnvironmentBuilder.build(shellEnvironment = shellEnvironment, nodePath = node.path),
            codexStatus = codex.status,
            nodeStatus = node.status,
        )
    }

    private fun inspect(
        configuredCodexPath: String,
        configuredNodePath: String,
        refreshShellEnvironment: Boolean,
        testAppServer: Boolean,
    ): CodexEnvironmentCheckResult {
        val shellEnvironment = shellEnvironment(refresh = refreshShellEnvironment)
        val codex = executableResolver.resolve(
            configuredPath = configuredCodexPath,
            commandName = "codex",
            shellEnvironment = shellEnvironment,
        )
        val node = executableResolver.resolve(
            configuredPath = configuredNodePath,
            commandName = "node",
            shellEnvironment = shellEnvironment,
        )
        val environmentOverrides = launchEnvironmentBuilder.build(
            shellEnvironment = shellEnvironment,
            nodePath = node.path,
        )
        val appServerProbeResult: Pair<CodexEnvironmentStatus, String> = when {
            codex.path.isNullOrBlank() -> CodexEnvironmentStatus.MISSING to buildSummary(
                codex.status,
                node.status,
                CodexEnvironmentStatus.MISSING,
            )
            configuredNodePath.trim().isNotBlank() && node.path.isNullOrBlank() -> CodexEnvironmentStatus.FAILED to buildSummary(
                codex.status,
                node.status,
                CodexEnvironmentStatus.FAILED,
            )
            !testAppServer -> if (codex.status == CodexEnvironmentStatus.CONFIGURED || node.status == CodexEnvironmentStatus.CONFIGURED) {
                CodexEnvironmentStatus.CONFIGURED to buildSummary(
                    codex.status,
                    node.status,
                    CodexEnvironmentStatus.CONFIGURED,
                )
            } else {
                CodexEnvironmentStatus.DETECTED to buildSummary(
                    codex.status,
                    node.status,
                    CodexEnvironmentStatus.DETECTED,
                )
            }
            else -> appServerProbe.probe(
                codexPath = codex.path,
                environmentOverrides = environmentOverrides,
            ).let { it.status to it.message }
        }
        return CodexEnvironmentCheckResult(
            codexPath = codex.path.orEmpty(),
            nodePath = node.path.orEmpty(),
            codexStatus = codex.status,
            nodeStatus = node.status,
            appServerStatus = appServerProbeResult.first,
            message = appServerProbeResult.second,
        )
    }

    private fun shellEnvironment(refresh: Boolean): Map<String, String> {
        if (refresh) {
            cachedShellEnvironment = null
        }
        return cachedShellEnvironment ?: runCatching(shellEnvironmentLoader).getOrNull().orEmpty().also {
            cachedShellEnvironment = it
        }
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

private fun loadShellEnvironment(): Map<String, String> {
    val systemEnvironment = System.getenv()
    val operatingSystemName = System.getProperty("os.name").orEmpty()
    if (operatingSystemName.contains("win", ignoreCase = true)) {
        return systemEnvironment
    }
    val shell = systemEnvironment["SHELL"].takeUnless { it.isNullOrBlank() } ?: "/bin/zsh"
    val process = runCatching { ProcessBuilder(shell, "-lc", "env").start() }.getOrNull()
        ?: return systemEnvironment
    return try {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator) to line.substring(separator + 1)
                }
            }.toMap().ifEmpty { systemEnvironment }
        }
    } finally {
        process.waitFor(2, TimeUnit.SECONDS)
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
}
