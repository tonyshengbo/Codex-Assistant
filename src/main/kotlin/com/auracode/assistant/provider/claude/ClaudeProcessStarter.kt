package com.auracode.assistant.provider.claude

import java.io.File

/** Starts one Claude CLI process in a specific working directory. */
internal fun interface ClaudeProcessStarter {
    fun start(
        command: List<String>,
        workingDirectory: File,
        environmentOverrides: Map<String, String>,
    ): Process
}

/** Uses the standard JVM process builder for Claude CLI launches. */
internal class DefaultClaudeProcessStarter : ClaudeProcessStarter {
    override fun start(
        command: List<String>,
        workingDirectory: File,
        environmentOverrides: Map<String, String>,
    ): Process {
        return ProcessBuilder(command)
            .directory(workingDirectory)
            .apply {
                environment().putAll(environmentOverrides)
            }
            .redirectErrorStream(false)
            .start()
    }
}
