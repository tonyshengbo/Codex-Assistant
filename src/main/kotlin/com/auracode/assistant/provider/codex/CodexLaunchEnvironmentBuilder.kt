package com.auracode.assistant.provider.codex

import java.io.File

/** Builds the process environment used to launch Codex and the app-server. */
internal class CodexLaunchEnvironmentBuilder(
    private val systemEnvironment: () -> Map<String, String> = { System.getenv() },
) {
    fun build(
        shellEnvironment: Map<String, String>,
        nodePath: String?,
    ): Map<String, String> {
        val system = systemEnvironment()
        val overrides = linkedMapOf<String, String>()
        SHELL_ENV_KEYS.forEach { key ->
            shellEnvironment[key]
                ?.takeIf { it.isNotBlank() }
                ?.let { overrides[key] = it }
                ?: system[key]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { overrides[key] = it }
        }
        val currentPath = overrides["PATH"].orEmpty()
        val nodeDir = nodePath?.trim()
            ?.takeIf { it.isNotBlank() && (it.contains('/') || it.contains('\\')) }
            ?.let { File(it).parentFile?.absolutePath }
            ?.takeIf { it.isNotBlank() }
        val mergedPath = listOfNotNull(nodeDir, currentPath.takeIf { it.isNotBlank() })
            .distinct()
            .joinToString(File.pathSeparator)
            .ifBlank { null }
        if (mergedPath != null) {
            overrides["PATH"] = mergedPath
        }
        return overrides
    }
}

private val SHELL_ENV_KEYS = listOf(
    "PATH",
    "HOME",
    "SHELL",
    "NVM_DIR",
    "FNM_DIR",
    "ASDF_DIR",
    "VOLTA_HOME",
    "PNPM_HOME",
)
