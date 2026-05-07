package com.auracode.assistant.provider.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface ClaudeStreamJsonSession {
    suspend fun collect(
        onStdoutLine: suspend (String) -> Unit,
        onStderrLine: suspend (String) -> Unit,
    ): Int

    /** 向 Claude CLI 的 stdin 写入一行 JSON，用于发送 control_response。 */
    suspend fun writeStdin(line: String)

    fun cancel()
}

internal class ProcessClaudeStreamJsonSession(
    private val process: Process,
) : ClaudeStreamJsonSession {
    private val stdinWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)

    override suspend fun collect(
        onStdoutLine: suspend (String) -> Unit,
        onStderrLine: suspend (String) -> Unit,
    ): Int = coroutineScope {
        val stderrJob = launch(Dispatchers.IO) {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        onStderrLine(line)
                    }
                }
            }
        }
        withContext(Dispatchers.IO) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        onStdoutLine(line)
                    }
                }
            }
        }
        val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
        stderrJob.join()
        exitCode
    }

    /** 向 stdin 写入一行 JSON；进程已退出时静默忽略，避免 IOException 上抛。 */
    override suspend fun writeStdin(line: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                stdinWriter.write(line)
                stdinWriter.newLine()
                stdinWriter.flush()
            }
        }
    }

    override fun cancel() {
        process.destroy()
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
}
