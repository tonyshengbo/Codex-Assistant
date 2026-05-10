package com.auracode.assistant.provider.runtime

import com.auracode.assistant.provider.codex.CodexExecutableResolver
import java.io.File
import java.util.concurrent.TimeUnit

/** Loads the preferred shell environment so GUI-launched IDE sessions can recover user PATH entries. */
internal fun resolvePreferredShellEnvironment(
    systemEnvironment: Map<String, String> = System.getenv(),
    operatingSystemName: String = System.getProperty("os.name").orEmpty(),
    loadCandidates: (Map<String, String>) -> List<Map<String, String>> = { env ->
        resolveShellEnvironmentCandidates(
            systemEnvironment = env,
            operatingSystemName = operatingSystemName,
        )
    },
): Map<String, String> {
    val candidates = loadCandidates(systemEnvironment)
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
    )?.environment ?: systemEnvironment
}

/** Loads shell environment candidates from system and interactive login shells. */
internal fun resolveShellEnvironmentCandidates(
    systemEnvironment: Map<String, String> = System.getenv(),
    operatingSystemName: String = System.getProperty("os.name").orEmpty(),
    loadCandidate: (List<String>) -> Map<String, String>? = ::loadShellEnvironmentCandidate,
): List<Map<String, String>> {
    if (operatingSystemName.contains("win", ignoreCase = true)) {
        return buildList {
            add(systemEnvironment)
            windowsEnvironmentCommands(systemEnvironment).forEach { command ->
                loadCandidate(command)?.let(::add)
            }
        }.distinctBy { environment ->
            buildString {
                append(environment["PATH"].orEmpty())
                append('\n')
                append(environment["ComSpec"].orEmpty())
                append('\n')
                append(environment["SystemRoot"].orEmpty())
            }
        }
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

/** Builds Windows shell commands that can recover a fuller environment from cmd.exe. */
private fun windowsEnvironmentCommands(systemEnvironment: Map<String, String>): List<List<String>> {
    val comSpec = normalizedWindowsExecutablePath(systemEnvironment["ComSpec"])
    val systemRoot = normalizedWindowsDirectory(systemEnvironment["SystemRoot"])
        ?: normalizedWindowsDirectory(systemEnvironment["windir"])
    val candidates = buildList {
        comSpec?.let(::add)
        systemRoot?.let { root ->
            add("$root${File.separator}System32${File.separator}cmd.exe")
            add("$root${File.separator}Sysnative${File.separator}cmd.exe")
        }
        add("cmd.exe")
    }.distinct()
    return candidates.map { listOf(it, "/d", "/c", "set") }
}

private fun normalizedWindowsExecutablePath(value: String?): String? {
    return value
        ?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotBlank() }
}

private fun normalizedWindowsDirectory(value: String?): String? {
    return value
        ?.trim()
        ?.trim('"')
        ?.removeSuffix("\\")
        ?.removeSuffix("/")
        ?.takeIf { it.isNotBlank() }
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
