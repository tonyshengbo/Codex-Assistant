package com.auracode.assistant.provider.runtime

import com.auracode.assistant.provider.codex.CodexExecutableResolver

/** Resolves runtime upgrade commands to concrete executables before launching external processes. */
internal class RuntimeUpgradeCommandResolver(
    private val executableResolver: CodexExecutableResolver = CodexExecutableResolver(commonSearchPaths = emptyList()),
) {
    /** Rewrites the command head to an absolute executable path when the current PATH can resolve it. */
    fun resolve(
        command: List<String>,
        shellEnvironment: Map<String, String>,
    ): List<String> {
        val executable = command.firstOrNull()?.trim().orEmpty()
        if (executable.isBlank()) return command
        if (executable.contains('/') || executable.contains('\\')) return command
        val resolvedExecutable = executableResolver.resolve(
            configuredPath = "",
            commandName = executable,
            shellEnvironment = shellEnvironment,
        ).path ?: return command
        return buildList(command.size) {
            add(resolvedExecutable)
            addAll(command.drop(1))
        }
    }
}
