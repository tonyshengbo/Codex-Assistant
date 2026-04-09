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
    private val shellEnvironmentCandidatesLoader: (() -> List<Map<String, String>>)? = ::loadShellEnvironmentCandidates,
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
        val shellEnvironment = shellEnvironment(
            configuredCodexPath = configuredCodexPath,
            configuredNodePath = configuredNodePath,
            refresh = false,
        )
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
        val shellEnvironment = shellEnvironment(
            configuredCodexPath = configuredCodexPath,
            configuredNodePath = configuredNodePath,
            refresh = refreshShellEnvironment,
        )
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
        val fallback = runCatching(shellEnvironmentLoader).getOrNull().orEmpty()
        val candidates = buildList {
            add(fallback)
            shellEnvironmentCandidatesLoader?.let { loader ->
                addAll(runCatching(loader).getOrNull().orEmpty())
            }
        }.distinctBy { environmentSignature(it) }
            .filter { it.isNotEmpty() }
        if (candidates.isEmpty()) {
            return fallback
        }
        return candidates.mapIndexed { index, environment ->
            candidateScore(
                environment = environment,
                configuredCodexPath = configuredCodexPath,
                configuredNodePath = configuredNodePath,
                index = index,
            )
        }.maxWithOrNull(
            compareBy<CandidateEnvironmentScore> { it.codexResolved }
                .thenBy { it.nodeResolved }
                .thenBy { it.pathEntryCount }
                .thenByDescending { it.index },
        )?.environment ?: fallback
    }

    private fun environmentSignature(environment: Map<String, String>): String {
        val path = environment["PATH"].orEmpty()
        val home = environment["HOME"].orEmpty()
        val shell = environment["SHELL"].orEmpty()
        return "$path\n$home\n$shell"
    }

    private fun candidateScore(
        environment: Map<String, String>,
        configuredCodexPath: String,
        configuredNodePath: String,
        index: Int,
    ): CandidateEnvironmentScore {
        val codexResolved = executableResolver.resolve(
            configuredPath = configuredCodexPath,
            commandName = "codex",
            shellEnvironment = environment,
        ).path != null
        val nodeResolved = executableResolver.resolve(
            configuredPath = configuredNodePath,
            commandName = "node",
            shellEnvironment = environment,
        ).path != null
        val pathEntryCount = environment["PATH"]
            ?.split(File.pathSeparator)
            ?.count { it.isNotBlank() }
            ?: 0
        return CandidateEnvironmentScore(
            environment = environment,
            codexResolved = codexResolved,
            nodeResolved = nodeResolved,
            pathEntryCount = pathEntryCount,
            index = index,
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

    private data class CandidateEnvironmentScore(
        val environment: Map<String, String>,
        val codexResolved: Boolean,
        val nodeResolved: Boolean,
        val pathEntryCount: Int,
        val index: Int,
    )
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
    return loadShellEnvironmentCandidates().firstOrNull().orEmpty().ifEmpty { System.getenv() }
}

private fun loadShellEnvironmentCandidates(): List<Map<String, String>> {
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
