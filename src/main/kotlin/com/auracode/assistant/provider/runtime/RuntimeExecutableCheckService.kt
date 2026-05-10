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
    private val runtimeLaunchResolver: RuntimeLaunchResolver = DefaultRuntimeLaunchResolver(),
) {
    /** Validates the active engine CLI path together with the shared optional Node path. */
    fun check(
        commandName: String,
        configuredCliPath: String,
        configuredNodePath: String,
    ): RuntimeExecutableCheckResult {
        val resolution = runtimeLaunchResolver.resolve(
            commandName = commandName,
            configuredCliPath = configuredCliPath,
            configuredNodePath = configuredNodePath,
        )
        return RuntimeExecutableCheckResult(
            cliPath = if (resolution.cliStatus == CodexEnvironmentStatus.MISSING) "" else resolution.cliPath,
            nodePath = resolution.nodePath,
            cliStatus = resolution.cliStatus,
            nodeStatus = resolution.nodeStatus,
        )
    }
}
