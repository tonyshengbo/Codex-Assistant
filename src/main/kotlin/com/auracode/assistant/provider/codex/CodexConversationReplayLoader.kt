package com.auracode.assistant.provider.codex

import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.conversation.ConversationSummaryPage
import com.auracode.assistant.provider.CodexProviderFactory
import com.auracode.assistant.provider.session.ProviderProtocolDomainMapper
import com.auracode.assistant.settings.AgentSettingsService
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/** Loads thread history and thread summaries through a dedicated lightweight client. */
internal class CodexConversationReplayLoader(
    private val settings: AgentSettingsService,
    private val environmentDetector: CodexEnvironmentDetector,
    private val diagnosticLogger: (String) -> Unit,
    private val providerId: String,
) {
    /** Reads the first history page for a thread. */
    suspend fun loadInitialHistory(remoteConversationId: String, pageSize: Int): ConversationHistoryPage {
        val turns = readThreadTurns(remoteConversationId)
        return buildConversationHistoryPage(
            turns = turns,
            pageSize = pageSize,
            cursor = null,
            providerId = providerId,
            diagnosticLogger = diagnosticLogger,
        )
    }

    /** Reads an older history page for a thread. */
    suspend fun loadOlderHistory(
        remoteConversationId: String,
        cursor: String,
        pageSize: Int,
    ): ConversationHistoryPage {
        val turns = readThreadTurns(remoteConversationId)
        return buildConversationHistoryPage(
            turns = turns,
            pageSize = pageSize,
            cursor = cursor,
            providerId = providerId,
            diagnosticLogger = diagnosticLogger,
        )
    }

    /** Lists remote conversations exposed by the app-server thread registry. */
    suspend fun listRemoteConversations(
        pageSize: Int,
        cursor: String?,
        cwd: String?,
        searchTerm: String?,
    ): ConversationSummaryPage {
        val (threads, nextCursor) = readThreadSummaries(
            cursor = cursor,
            limit = pageSize,
            cwd = cwd,
            searchTerm = searchTerm,
        )
        return ConversationSummaryPage(
            conversations = threads.map { thread ->
                ConversationSummary(
                    remoteConversationId = thread.string("id").orEmpty(),
                    title = thread.string("name").orEmpty().ifBlank { thread.string("preview").orEmpty() },
                    createdAt = thread.long("createdAt", "created_at"),
                    updatedAt = thread.long("updatedAt", "updated_at"),
                    status = thread.objectValue("status")?.string("type").orEmpty(),
                )
            }.filter { it.remoteConversationId.isNotBlank() },
            nextCursor = nextCursor,
        )
    }

    private suspend fun readThreadTurns(remoteConversationId: String): List<JsonObject> {
        val resolution = environmentDetector.resolveForLaunch(
            configuredCodexPath = settings.getState().executablePathFor(CodexProviderFactory.ENGINE_ID),
            configuredNodePath = settings.nodeExecutablePath(),
        )
        if (resolution.codexStatus == CodexEnvironmentStatus.MISSING || resolution.codexStatus == CodexEnvironmentStatus.FAILED) {
            return emptyList()
        }
        val binary = resolution.codexPath.trim()
        val process = createCodexRuntimeProcess(
            binary = binary,
            environmentOverrides = resolution.environmentOverrides,
        )
        return try {
            val session = CodexProcessRuntimeSession(process, diagnosticLogger)
            val client = CodexRuntimeClient(session, diagnosticLogger)
            session.start()
            withTimeout(APP_SERVER_HANDSHAKE_TIMEOUT_MS) {
                session.initialize()
                client.readThreadTurns(remoteConversationId)
            }
        } finally {
            process.destroy()
        }
    }

    private suspend fun readThreadSummaries(
        cursor: String?,
        limit: Int,
        cwd: String?,
        searchTerm: String?,
    ): Pair<List<JsonObject>, String?> {
        val resolution = environmentDetector.resolveForLaunch(
            configuredCodexPath = settings.getState().executablePathFor(CodexProviderFactory.ENGINE_ID),
            configuredNodePath = settings.nodeExecutablePath(),
        )
        if (resolution.codexStatus == CodexEnvironmentStatus.MISSING || resolution.codexStatus == CodexEnvironmentStatus.FAILED) {
            return emptyList<JsonObject>() to null
        }
        val binary = resolution.codexPath.trim()
        val process = createCodexRuntimeProcess(
            binary = binary,
            environmentOverrides = resolution.environmentOverrides,
        )
        return try {
            val session = CodexProcessRuntimeSession(process, diagnosticLogger)
            val client = CodexRuntimeClient(session, diagnosticLogger)
            session.start()
            withTimeout(APP_SERVER_HANDSHAKE_TIMEOUT_MS) {
                session.initialize()
                client.readThreadSummaries(cursor = cursor, limit = limit, cwd = cwd, searchTerm = searchTerm)
            }
        } finally {
            process.destroy()
        }
    }
}

/** Creates a paged history view from raw historical turns returned by the app-server. */
internal fun buildConversationHistoryPage(
    turns: List<JsonObject>,
    pageSize: Int,
    cursor: String?,
    providerId: String,
    diagnosticLogger: (String) -> Unit,
): ConversationHistoryPage {
    if (turns.isEmpty() || pageSize <= 0) {
        return ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)
    }
    val endExclusive = cursor?.toIntOrNull()?.coerceIn(0, turns.size) ?: turns.size
    if (endExclusive <= 0) {
        return ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)
    }
    val startInclusive = (endExclusive - pageSize).coerceAtLeast(0)
    val parser = CodexRuntimeNotificationParser(
        requestId = "history:$providerId",
        diagnosticLogger = diagnosticLogger,
    )
    val mapper = ProviderProtocolDomainMapper()
    return ConversationHistoryPage(
        events = turns.subList(startInclusive, endExclusive).flatMap { turn ->
            parser.parseHistoricalTurn(turn).flatMap(mapper::map)
        },
        hasOlder = startInclusive > 0,
        olderCursor = startInclusive.takeIf { it > 0 }?.toString(),
    )
}

/** Creates a new app-server process with optional working-directory scoping. */
internal fun createCodexRuntimeProcess(
    binary: String,
    environmentOverrides: Map<String, String> = emptyMap(),
    workingDirectory: File? = null,
): Process {
    val launch = buildCodexRuntimeLaunchConfig(
        binary = binary,
        environmentOverrides = environmentOverrides,
    )
    return ProcessBuilder(launch.command)
        .apply {
            if (workingDirectory != null) {
                directory(workingDirectory)
            }
            environment().putAll(launch.environmentOverrides)
            redirectErrorStream(false)
        }
        .start()
}
