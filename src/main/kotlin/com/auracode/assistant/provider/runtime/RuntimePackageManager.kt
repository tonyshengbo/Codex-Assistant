package com.auracode.assistant.provider.runtime

import com.auracode.assistant.provider.codex.CodexExecutableResolver

/** Represents one supported package manager that can install a runtime CLI. */
internal enum class RuntimePackageManager(
    val id: String,
    val executableName: String,
) {
    NPM(id = "npm", executableName = "npm"),
    PNPM(id = "pnpm", executableName = "pnpm"),
    BUN(id = "bun", executableName = "bun"),
    BREW(id = "brew", executableName = "brew"),
    ;

    companion object {
        /** Resolves one package manager from a stable string id. */
        fun fromId(id: String): RuntimePackageManager? {
            return entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
        }
    }
}

/** Stores whether one package manager can be launched in the current shell environment. */
internal data class RuntimePackageManagerAvailability(
    val packageManager: RuntimePackageManager,
    val available: Boolean,
)

/** Detects which package managers are executable in the current shell environment. */
internal class RuntimePackageManagerDetector(
    private val executableResolver: CodexExecutableResolver = CodexExecutableResolver(),
    private val shellEnvironmentLoader: () -> Map<String, String> = ::resolvePreferredShellEnvironment,
) {
    /** Returns availability flags for every supported package manager in stable display order. */
    fun detectAvailability(): List<RuntimePackageManagerAvailability> {
        val shellEnvironment = shellEnvironmentLoader()
        return RuntimePackageManager.entries.map { packageManager ->
            val resolved = executableResolver.resolve(
                configuredPath = "",
                commandName = packageManager.executableName,
                shellEnvironment = shellEnvironment,
            )
            RuntimePackageManagerAvailability(
                packageManager = packageManager,
                available = resolved.path != null,
            )
        }
    }
}
