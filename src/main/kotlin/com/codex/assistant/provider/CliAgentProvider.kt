package com.codex.assistant.provider

import com.codex.assistant.model.AgentAction
import com.codex.assistant.model.AgentRequest
import com.codex.assistant.model.EngineEvent
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

abstract class CliAgentProvider(
    private val executablePath: () -> String,
    private val displayName: String,
    private val invocationSpec: CliInvocationSpec,
    private val eventParser: StructuredEventParser,
    private val diagnosticLogger: (String) -> Unit = { message -> LOG.info(message) },
) : AgentProvider {
    private val running = ConcurrentHashMap<String, Process>()

    companion object {
        private val LOG = Logger.getInstance(CliAgentProvider::class.java)
        private const val MAX_RAW_LOG_CHARS = 4_000
    }

    override fun stream(request: AgentRequest): Flow<EngineEvent> = callbackFlow {
        val binary = executablePath().trim()
        if (binary.isEmpty()) {
            trySend(EngineEvent.Error("$displayName CLI path is not configured."))
            trySend(EngineEvent.Completed(exitCode = 1))
            close()
            return@callbackFlow
        }

        val prompt = buildPrompt(request)
        val command = invocationSpec.buildCommand(binary, request, prompt)
        logSummary(
            "request.started requestId=${request.requestId} mode=${requestMode(request)} " +
                "cliSessionId=${request.cliSessionId ?: "<none>"} model=${request.model ?: "<auto>"} " +
                "cwd=${request.workingDirectory} command=${commandSummary(command)}",
        )

        val process = try {
            ProcessBuilder(command)
                .directory(File(request.workingDirectory))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            logSummary("request.failed requestId=${request.requestId} message=${e.message}")
            trySend(EngineEvent.Error("Failed to start $displayName CLI: ${e.message}"))
            trySend(EngineEvent.Completed(exitCode = 1))
            close()
            return@callbackFlow
        }

        running[request.requestId] = process

        launch(Dispatchers.IO) {
            val proposalAccumulator = ProposalAccumulator(request.workingDirectory)
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    logRaw(request.requestId, line)
                    val parsed = eventParser.parse(line)
                    if (parsed != null) {
                        when (parsed) {
                            is EngineEvent.SessionReady -> {
                                logSummary(
                                    "session.ready requestId=${request.requestId} cliSessionId=${parsed.sessionId}",
                                )
                            }

                            is EngineEvent.Error -> {
                                logSummary(
                                    "event.error requestId=${request.requestId} message=${parsed.message}",
                                )
                            }

                            else -> Unit
                        }
                        when (parsed) {
                            is EngineEvent.CommandProposal, is EngineEvent.DiffProposal -> {
                                if (proposalAccumulator.acceptStructured(parsed)) {
                                    trySend(parsed)
                                }
                            }
                            else -> trySend(parsed)
                        }
                    } else if (eventParser.shouldEmitUnparsedLine(line)) {
                        trySend(eventParser.unparsedLineEvent(line))
                    }
                }
            }
            val exitCode = process.waitFor()
            running.remove(request.requestId)
            logSummary("request.finished requestId=${request.requestId} exitCode=$exitCode")
            if (exitCode != 0) {
                trySend(EngineEvent.Error("$displayName exited with code $exitCode."))
            }
            trySend(EngineEvent.Completed(exitCode))
            close()
        }

        awaitClose {
            logSummary("request.disconnected requestId=${request.requestId}")
            running.remove(request.requestId)?.destroy()
        }
    }

    override fun cancel(requestId: String) {
        logSummary("request.cancel requestId=$requestId")
        running.remove(requestId)?.destroyForcibly()
    }

    private fun buildPrompt(request: AgentRequest): String {
        val actionPrefix = when (request.action) {
            AgentAction.CHAT -> ""
        }

        val contextBlock = if (request.contextFiles.isEmpty()) {
            ""
        } else {
            request.contextFiles.joinToString("\n\n") { file ->
                "FILE: ${file.path}\n${file.content}"
            }
        }

        val prompt = buildString {
            append(actionPrefix)
            append(request.prompt)
            if (contextBlock.isNotBlank()) {
                append("\n\nContext files:\n")
                append(contextBlock)
            }
        }

        return prompt
    }

    private fun requestMode(request: AgentRequest): String {
        return if (request.cliSessionId.isNullOrBlank()) "exec" else "resume"
    }

    private fun commandSummary(command: List<String>): String {
        return command.joinToString(" ") { it.replace("\n", "\\n") }
    }

    private fun logSummary(message: String) {
        diagnosticLogger("$displayName cli summary: $message")
    }

    private fun logRaw(requestId: String, line: String) {
        val normalized = line.replace("\n", "\\n")
        val sanitized = if (normalized.length <= MAX_RAW_LOG_CHARS) {
            normalized
        } else {
            "${normalized.take(MAX_RAW_LOG_CHARS)}...<truncated ${normalized.length - MAX_RAW_LOG_CHARS} chars>"
        }
        diagnosticLogger("$displayName cli raw: requestId=$requestId line=$sanitized")
    }
}
