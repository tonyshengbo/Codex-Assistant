package com.auracode.assistant.provider.runtime

import com.auracode.assistant.provider.codex.CodexExecutableResolver
import java.util.concurrent.TimeUnit

/** Loads the preferred shell environment so GUI-launched IDE sessions can recover user PATH entries. */
internal fun resolvePreferredShellEnvironment(): Map<String, String> {
    val candidates = resolveShellEnvironmentCandidates()
    if (candidates.isEmpty()) return System.getenv()
    val executableResolver = CodexExecutableResolver()
    return candidates.mapIndexed { index, environment ->
        ShellEnvironmentCandidateScore(
            environment = environment,
            resolvedCommandCount = preferredShellCommands().count { commandName ->
                executableResolver.resolve(
                    configuredPath = "",
                    commandName = commandName,
                    shellEnvironment = environment,
                ).path != null
            },
            pathEntryCount = environment["PATH"]
                ?.split(java.io.File.pathSeparator)
                ?.count { it.isNotBlank() }
                ?: 0,
            index = index,
        )
    }.maxWithOrNull(
        compareBy<ShellEnvironmentCandidateScore> { it.resolvedCommandCount }
            .thenBy { it.pathEntryCount }
            .thenByDescending { it.index },
    )?.environment ?: System.getenv()
}

/** Loads shell environment candidates from system and interactive login shells. */
internal fun resolveShellEnvironmentCandidates(): List<Map<String, String>> {
    val systemEnvironment = System.getenv()
    val operatingSystemName = System.getProperty("os.name").orEmpty()
    if (operatingSystemName.contains("win", ignoreCase = true)) {
        return listOf(systemEnvironment)
    }
    val shell = systemEnvironment["SHELL"].takeUnless { it.isNullOrBlank() } ?: "/bin/zsh"
    val commands = listOf(
        listOf(shell, "-lc", "env"),
        listOf(shell, "-ilc", "env"),
    )
    return buildList {
        add(systemEnvironment)
        commands.forEach { command ->
            loadShellEnvironmentCandidate(command)?.let(::add)
        }
    }.distinctBy { environment ->
        buildString {
            append(environment["PATH"].orEmpty())
            append('\n')
            append(environment["HOME"].orEmpty())
            append('\n')
            append(environment["SHELL"].orEmpty())
        }
    }
}

/** Scores one shell environment candidate when selecting the preferred PATH source. */
private data class ShellEnvironmentCandidateScore(
    val environment: Map<String, String>,
    val resolvedCommandCount: Int,
    val pathEntryCount: Int,
    val index: Int,
)

/** Returns the executable names used to rank competing shell environment candidates. */
private fun preferredShellCommands(): List<String> {
    return listOf("codex", "claude", "node", "npm", "pnpm", "bun", "brew")
}

/** Runs one shell command and parses the emitted environment variables. */
private fun loadShellEnvironmentCandidate(command: List<String>): Map<String, String>? {
    val systemEnvironment = System.getenv()
    val process = runCatching { ProcessBuilder(command).start() }.getOrNull() ?: return null
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
