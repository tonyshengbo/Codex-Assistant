package com.auracode.assistant.provider.claude

import java.io.File

/**
 * 负责根据命令与工作目录启动 Claude CLI 原生进程。
 */
internal fun interface ClaudeProcessStarter {
    /** 启动 Claude CLI 进程，并返回可供后续读取输出的进程句柄。 */
    fun start(command: List<String>, workingDirectory: File): Process
}

/**
 * 使用标准 ProcessBuilder 启动 Claude CLI 进程。
 */
internal class DefaultClaudeProcessStarter : ClaudeProcessStarter {
    /** 根据命令和工作目录启动子进程，同时保留 stdout/stderr 独立通道。 */
    override fun start(command: List<String>, workingDirectory: File): Process {
        return ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(false)
            .start()
    }
}
