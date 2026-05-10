package com.auracode.assistant.provider.runtime

import com.auracode.assistant.provider.codex.CodexEnvironmentStatus
import com.auracode.assistant.provider.codex.CodexExecutableResolver
import java.io.File
import com.auracode.assistant.provider.codex.buildLaunchEnvironmentOverrides

/** Captures the resolved executable paths and environment used to launch one runtime command. */
internal data class RuntimeLaunchResolution(
    val cliPath: String,
    val cliStatus: CodexEnvironmentStatus,
    val nodePath: String,
    val nodeStatus: CodexEnvironmentStatus,
    val shellEnvironment: Map<String, String>,
    val environmentOverrides: Map<String, String>,
)

/** Resolves one runtime command and its launch environment from settings plus shell state. */
internal fun interface RuntimeLaunchResolver {
    fun resolve(
        commandName: String,
        configuredCliPath: String,
        configuredNodePath: String,
    ): RuntimeLaunchResolution
}

/** Default implementation shared by runtime settings checks and actual process launches. */
internal class DefaultRuntimeLaunchResolver(
    private val executableResolver: CodexExecutableResolver = CodexExecutableResolver(),
    private val shellEnvironmentLoader: () -> Map<String, String> = ::resolvePreferredShellEnvironment,
    private val shellEnvironmentCandidatesLoader: (() -> List<Map<String, String>>)? = ::resolveShellEnvironmentCandidates,
) : RuntimeLaunchResolver {
    override fun resolve(
        commandName: String,
        configuredCliPath: String,
        configuredNodePath: String,
    ): RuntimeLaunchResolution {
        val shellEnvironment = resolveBestShellEnvironment(
            executableResolver = executableResolver,
            shellEnvironmentLoader = shellEnvironmentLoader,
            shellEnvironmentCandidatesLoader = shellEnvironmentCandidatesLoader,
            configuredCliPath = configuredCliPath,
            configuredNodePath = configuredNodePath,
            commandName = commandName,
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
        val resolvedCliPath = cli.path?.takeIf { it.isNotBlank() }
            ?: configuredCliPath.trim().takeIf { it.isNotBlank() }
            ?: commandName
        val resolvedNodePath = node.path.orEmpty()
        return RuntimeLaunchResolution(
            cliPath = resolvedCliPath,
            cliStatus = cli.status,
            nodePath = resolvedNodePath,
            nodeStatus = node.status,
            shellEnvironment = shellEnvironment,
            environmentOverrides = buildLaunchEnvironmentOverrides(
                shellEnvironment = shellEnvironment,
                nodePath = node.path,
            ),
        )
    }
}

internal fun resolveBestShellEnvironment(
    executableResolver: CodexExecutableResolver,
    shellEnvironmentLoader: () -> Map<String, String>,
    shellEnvironmentCandidatesLoader: (() -> List<Map<String, String>>)?,
    configuredCliPath: String,
    configuredNodePath: String,
    commandName: String,
): Map<String, String> {
    val fallback = runCatching(shellEnvironmentLoader).getOrNull().orEmpty()
    val candidates = buildList {
        add(fallback)
        shellEnvironmentCandidatesLoader?.let { loader ->
            addAll(runCatching(loader).getOrNull().orEmpty())
        }
    }.distinctBy(::runtimeEnvironmentSignature)
        .filter { it.isNotEmpty() }
    if (candidates.isEmpty()) {
        return fallback
    }
    return candidates.mapIndexed { index, environment ->
        RuntimeShellEnvironmentCandidateScore(
            environment = environment,
            cliResolved = executableResolver.resolve(
                configuredPath = configuredCliPath,
                commandName = commandName,
                shellEnvironment = environment,
            ).path != null,
            nodeResolved = executableResolver.resolve(
                configuredPath = configuredNodePath,
                commandName = "node",
                shellEnvironment = environment,
            ).path != null,
            pathEntryCount = environment["PATH"]
                ?.split(File.pathSeparator)
                ?.count { it.isNotBlank() }
                ?: 0,
            index = index,
        )
    }.maxWithOrNull(
        compareBy<RuntimeShellEnvironmentCandidateScore> { it.cliResolved }
            .thenBy { it.nodeResolved }
            .thenBy { it.pathEntryCount }
            .thenByDescending { it.index },
    )?.environment ?: fallback
}

private fun runtimeEnvironmentSignature(environment: Map<String, String>): String {
    val path = environment["PATH"].orEmpty()
    val home = environment["HOME"].orEmpty()
    val shell = environment["SHELL"].orEmpty()
    val comSpec = environment["ComSpec"].orEmpty()
    val systemRoot = environment["SystemRoot"].orEmpty()
    return "$path\n$home\n$shell\n$comSpec\n$systemRoot"
}

private data class RuntimeShellEnvironmentCandidateScore(
    val environment: Map<String, String>,
    val cliResolved: Boolean,
    val nodeResolved: Boolean,
    val pathEntryCount: Int,
    val index: Int,
)
