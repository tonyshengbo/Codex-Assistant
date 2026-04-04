package com.auracode.assistant.provider.codex

import java.io.File

/** Describes one resolved executable candidate. */
internal data class CodexResolvedExecutable(
    val path: String?,
    val status: CodexEnvironmentStatus,
)

/** Resolves runtime executables across configured paths, PATH entries, and OS defaults. */
internal class CodexExecutableResolver(
    private val commonSearchPaths: List<String> = defaultCodexCommonSearchPaths(System.getProperty("os.name").orEmpty()),
    private val operatingSystemName: String = System.getProperty("os.name").orEmpty(),
    private val pathExt: String = System.getenv("PATHEXT").orEmpty(),
) {
    private val isWindows: Boolean = operatingSystemName.contains("win", ignoreCase = true)

    fun resolve(
        configuredPath: String,
        commandName: String,
        shellEnvironment: Map<String, String>,
    ): CodexResolvedExecutable {
        val normalized = configuredPath.trim()
        if (normalized.isNotBlank()) {
            resolveExactPath(normalized)?.let {
                return CodexResolvedExecutable(it, CodexEnvironmentStatus.CONFIGURED)
            }
            if (!containsDirectorySeparator(normalized)) {
                searchExecutable(normalized, shellEnvironment["PATH"])?.let {
                    return CodexResolvedExecutable(it, CodexEnvironmentStatus.CONFIGURED)
                }
            }
            return CodexResolvedExecutable(null, CodexEnvironmentStatus.FAILED)
        }

        searchExecutable(commandName, shellEnvironment["PATH"])?.let {
            return CodexResolvedExecutable(it, CodexEnvironmentStatus.DETECTED)
        }
        searchExecutable(commandName, commonSearchPaths.joinToString(File.pathSeparator))?.let {
            return CodexResolvedExecutable(it, CodexEnvironmentStatus.DETECTED)
        }
        return CodexResolvedExecutable(null, CodexEnvironmentStatus.MISSING)
    }

    private fun resolveExactPath(path: String): String? {
        resolveExistingFile(File(path))?.let { return it }
        if (!isWindows) return null
        return executableNames(path)
            .drop(1)
            .map { File(it) }
            .firstNotNullOfOrNull(::resolveExistingFile)
    }

    private fun searchExecutable(
        executable: String,
        pathValue: String?,
    ): String? {
        return pathValue
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.flatMap { directory ->
                executableNames(executable).map { candidate -> File(directory, candidate) }.asSequence()
            }
            ?.firstNotNullOfOrNull(::resolveExistingFile)
    }

    private fun executableNames(executable: String): List<String> {
        if (!isWindows) return listOf(executable)
        val lowerExecutable = executable.lowercase()
        val pathExtValues = normalizedPathExt()
        if (pathExtValues.any { lowerExecutable.endsWith(it.lowercase()) }) {
            return listOf(executable)
        }
        return buildList {
            add(executable)
            pathExtValues.forEach { extension ->
                add(executable + extension.lowercase())
                add(executable + extension.uppercase())
            }
        }.distinct()
    }

    private fun normalizedPathExt(): List<String> {
        return pathExt.split(';')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (it.startsWith(".")) it else ".$it" }
            .ifEmpty { listOf(".exe", ".cmd", ".bat", ".com") }
    }

    private fun containsDirectorySeparator(value: String): Boolean {
        return value.contains('/') || value.contains('\\')
    }

    private fun resolveExistingFile(file: File): String? {
        return file.takeIf { it.exists() && it.isFile && it.canExecute() }?.absolutePath
    }
}

internal fun defaultCodexCommonSearchPaths(operatingSystemName: String): List<String> {
    val home = System.getProperty("user.home").orEmpty()
    return if (operatingSystemName.contains("win", ignoreCase = true)) {
        listOf(
            "$home\\AppData\\Roaming\\npm",
            "$home\\AppData\\Local\\Programs\\nodejs",
            "C:\\Program Files\\nodejs",
            "C:\\Program Files\\Git\\usr\\bin",
        )
    } else {
        listOf(
            "/opt/homebrew/bin",
            "/usr/local/bin",
            "/usr/bin",
            "/bin",
        )
    }
}
