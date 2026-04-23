package com.auracode.assistant.provider.codex

import com.auracode.assistant.coroutine.AppCoroutineManager
import com.auracode.assistant.coroutine.ManagedCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Defines the shared JSON-RPC contract used by all Codex app-server callers. */
internal interface CodexAppServerSession {
    /** Starts the reader loop that consumes app-server messages. */
    fun start()

    /** Performs the standard initialize handshake once per process. */
    suspend fun initialize()

    /** Sends a request and awaits its JSON-RPC result payload. */
    suspend fun request(method: String, params: JsonObject): JsonObject

    /** Sends a fire-and-forget JSON-RPC notification. */
    suspend fun notify(method: String, params: JsonObject = buildJsonObject {})

    /** Sends a JSON-RPC response for a server-initiated request. */
    suspend fun respond(serverRequestId: JsonElement, result: JsonObject)
}

/**
 * Implements a process-backed Codex app-server session.
 *
 * The session owns JSON-RPC response routing and forwards notifications and
 * server requests to higher-level callers without embedding Aura-specific logic.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class CodexProcessAppServerSession(
    private val process: Process,
    private val diagnosticLogger: (String) -> Unit,
    private val onNotification: suspend (method: String, params: JsonObject) -> Unit = { _, _ -> },
    private val onServerRequest: suspend (id: JsonElement, method: String, params: JsonObject) -> Unit = { _, _, _ -> },
) : CodexAppServerSession {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val callbackScope: ManagedCoroutineScope = AppCoroutineManager.createScope(
        scopeName = "CodexProcessAppServerSession",
        dispatcher = Dispatchers.IO,
        failureReporter = { scopeName, label, error ->
            diagnosticLogger(
                "$scopeName coroutine failed${label?.let { ": $it" }.orEmpty()}: ${error.message}\n${error.stackTraceToString()}",
            )
        },
    )

    override fun start() {
        callbackScope.launch(label = "processInput") {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    handleLine(line)
                }
            }
        }
    }

    override suspend fun initialize() {
        request(method = "initialize", params = CodexAppServerJsonSupport.initializeParams())
        notify(method = "initialized")
    }

    override suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = nextId.getAndIncrement().toString()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        writeJson(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            },
        )
        val response = deferred.await()
        pending.remove(id)
        val obj = response as? JsonObject ?: buildJsonObject {}
        obj["error"]?.let { error(it.toString()) }
        return obj.objectValue("result") ?: obj
    }

    override suspend fun notify(method: String, params: JsonObject) {
        writeJson(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            },
        )
    }

    override suspend fun respond(serverRequestId: JsonElement, result: JsonObject) {
        writeJson(
            buildServerRequestResponse(
                serverRequestId = serverRequestId,
                result = result,
            ),
        )
    }

    private suspend fun writeJson(payload: JsonObject) {
        val serialized = json.encodeToString(JsonObject.serializer(), payload)
        diagnosticLogger("Codex app-server send: ${serialized.take(4000)}")
        writer.write(serialized)
        writer.newLine()
        writer.flush()
    }

    /**
     * Dispatches one app-server line while preserving notification order from the wire.
     */
    private suspend fun handleLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        diagnosticLogger("Codex app-server recv: ${trimmed.take(4000)}")
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return
        val rawId = obj["id"]
        val id = obj.string("id")
        val method = obj.string("method")
        if (id != null && (obj.containsKey("result") || obj.containsKey("error"))) {
            pending.remove(id)?.complete(obj)
            return
        }
        if (method != null && rawId != null) {
            callbackScope.launch(label = "serverRequest:$method") {
                onServerRequest(rawId, method, obj.objectValue("params") ?: buildJsonObject {})
            }
            return
        }
        if (method != null) {
            onNotification(method, obj.objectValue("params") ?: buildJsonObject {})
        }
    }
}
