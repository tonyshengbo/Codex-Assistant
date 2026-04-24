package com.auracode.assistant.provider.runtime

import com.auracode.assistant.provider.codex.CodexEnvironmentStatus
import com.auracode.assistant.provider.codex.CodexExecutableResolver

/** Captures lightweight executable validation results for one runtime settings tab. */
internal data class RuntimeExecutableCheckResult(
    val cliPath: String = "",
    val nodePath: String = "",
    val cliStatus: CodexEnvironmentStatus = CodexEnvironmentStatus.MISSING,
    val nodeStatus: CodexEnvironmentStatus = CodexEnvironmentStatus.MISSING,
)

/** Resolves CLI and Node executables without rendering a heavyweight diagnostics flow. */
internal class RuntimeExecutableCheckService(
    private val executableResolver: CodexExecutableResolver = CodexExecutableResolver(),
    private val shellEnvironmentLoader: () -> Map<String, String> = { System.getenv() },
) {
    /** Validates the active engine CLI path together with the shared optional Node path. */
    fun check(
        commandName: String,
        configuredCliPath: String,
        configuredNodePath: String,
    ): RuntimeExecutableCheckResult {
        val shellEnvironment = shellEnvironmentLoader()
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
        return RuntimeExecutableCheckResult(
            cliPath = cli.path.orEmpty(),
            nodePath = node.path.orEmpty(),
            cliStatus = cli.status,
            nodeStatus = node.status,
        )
    }
}
