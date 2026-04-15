package com.auracode.assistant.settings.mcp

import com.auracode.assistant.coroutine.AppCoroutineManager
import com.auracode.assistant.coroutine.ManagedCoroutineScope
import com.auracode.assistant.provider.CodexProviderFactory
import com.auracode.assistant.provider.codex.CodexAppServerJsonSupport
import com.auracode.assistant.provider.codex.CodexEnvironmentDetector
import com.auracode.assistant.provider.codex.CodexEnvironmentResolution
import com.auracode.assistant.provider.codex.CodexEnvironmentStatus
import com.auracode.assistant.settings.AgentSettingsService
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalSerializationApi::class)
internal class CodexMcpManagementAdapter(
    private val settings: AgentSettingsService,
    private val environmentDetector: CodexEnvironmentDetector = CodexEnvironmentDetector(),
    private val launchEnvironmentResolver: (String, String) -> CodexEnvironmentResolution = { configuredCodexPath, configuredNodePath ->
        environmentDetector.resolveForLaunch(
            configuredCodexPath = configuredCodexPath,
            configuredNodePath = configuredNodePath,
        )
    },
    private val commandRunner: (CommandExecutionRequest) -> CommandExecutionResult = ::runCommand,
    private val appServerClientFactory: (String, Map<String, String>) -> CodexMcpAppServerClient = { binary, environmentOverrides ->
        RealCodexMcpAppServerClient(binary = binary, environmentOverrides = environmentOverrides)
    },
) : McpManagementAdapter {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val prettyJson = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = true; prettyPrintIndent = "  " }

    override suspend fun listServers(): List<McpServerSummary> {
        val response = runCli("mcp", "list", "--json")
        if (response.exitCode != 0) {
            error(response.combinedOutput.ifBlank { "Failed to list MCP servers." })
        }
        if (response.stdout.isBlank()) return emptyList()
        val array = json.parseToJsonElement(response.stdout).jsonArray
        return array.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val name = entry.string("name") ?: return@mapNotNull null
            val transport = entry.objectValue("transport") ?: return@mapNotNull null
            val transportType = transport.transportType() ?: return@mapNotNull null
            McpServerSummary(
                name = name,
                engineId = CodexProviderFactory.ENGINE_ID,
                transportType = transportType,
                displayTarget = when (transportType) {
                    McpTransportType.STDIO -> buildString {
                        append(transport.string("command").orEmpty())
                        val args = transport.arrayValue("args")
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            .orEmpty()
                        if (args.isNotEmpty()) {
                            append(" ")
                            append(args.joinToString(" "))
                        }
                    }.trim()
                    McpTransportType.STREAMABLE_HTTP -> transport.string("url").orEmpty()
                },
                enabled = entry.boolean("enabled") ?: true,
                authState = parseAuthState(entry.string("auth_status")),
            )
        }
    }

    override suspend fun getServer(name: String): McpServerDraft? {
        val response = runCli("mcp", "get", name, "--json")
        if (response.exitCode != 0) {
            if (response.combinedOutput.contains("No MCP server named", ignoreCase = true)) {
                return null
            }
            error(response.combinedOutput.ifBlank { "Failed to read MCP server '$name'." })
        }
        val entry = json.parseToJsonElement(response.stdout).jsonObject
        val transport = entry.objectValue("transport") ?: return null
        val configJson = McpServerDraft.entryConfigJson(name, entry.toFlatConfigJson())
        return when (transport.transportType()) {
            McpTransportType.STDIO -> McpServerDraft(
                originalName = name,
                name = entry.string("name").orEmpty(),
                transport = McpTransportDraft.Stdio(
                    command = transport.string("command").orEmpty(),
                    args = transport.arrayValue("args")
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty()
                        .joinToString("\n"),
                    env = transport.objectValue("env")
                        ?.entries
                        ?.sortedBy { it.key }
                        ?.joinToString("\n") { (key, value) ->
                            "$key=${value.jsonPrimitive.contentOrNull.orEmpty()}"
                        }
                        .orEmpty(),
                    cwd = transport.string("cwd").orEmpty(),
                ),
                configJson = configJson,
            )

            McpTransportType.STREAMABLE_HTTP -> McpServerDraft(
                originalName = name,
                name = entry.string("name").orEmpty(),
                transport = McpTransportDraft.StreamableHttp(
                    url = transport.string("url").orEmpty(),
                    bearerTokenEnvVar = transport.string("bearer_token_env_var").orEmpty(),
                ),
                configJson = configJson,
            )

            null -> null
        }
    }

    override suspend fun getEditorDraft(): McpServerDraft {
        val servers = listServers()
        if (servers.isEmpty()) return McpServerDraft()
        val entries = servers.mapNotNull { server ->
            getServer(server.name)?.parsedConfigJson()?.getOrNull()?.let { config ->
                NamedMcpServerConfig(server.name, config)
            }
        }
        return McpServerDraft(
            configJson = McpServerDraft.serversConfigJson(entries),
        )
    }

    override suspend fun saveServers(drafts: List<McpServerDraft>) {
        if (drafts.isEmpty()) return
        val binary = binary()
        val edits = drafts.map { draft ->
            val config = draft.parsedConfigJson().getOrElse { throw it }
            ConfigEditRequest(
                keyPath = "mcp_servers.${draft.name.trim()}",
                value = config,
            )
        }
        withAppServerClient(binary) { client ->
            client.initialize()
            client.batchWrite(edits)
            client.reloadMcpServers()
        }
    }

    override suspend fun saveServer(draft: McpServerDraft) {
        if (draft.originalName.isNullOrBlank()) {
            saveServers(listOf(draft))
            return
        }
        val binary = binary()
        val config = draft.parsedConfigJson().getOrElse { throw it }
        val edits = mutableListOf(
            ConfigEditRequest(
                keyPath = "mcp_servers.${draft.name.trim()}",
                value = config,
            ),
        )
        val originalName = draft.originalName?.trim().orEmpty()
        val newName = draft.name.trim()
        if (originalName.isNotBlank() && originalName != newName) {
            edits += ConfigEditRequest(
                keyPath = "mcp_servers.$originalName",
                value = null,
            )
        }
        withAppServerClient(binary) { client ->
            client.initialize()
            client.batchWrite(edits)
            client.reloadMcpServers()
        }
    }

    override suspend fun deleteServer(name: String): Boolean {
        if (name.isBlank()) return false
        val binary = binary()
        withAppServerClient(binary) { client ->
            client.initialize()
            client.batchWrite(
                listOf(
                    ConfigEditRequest(
                        keyPath = "mcp_servers.${name.trim()}",
                        value = null,
                    ),
                ),
            )
            client.reloadMcpServers()
        }
        return true
    }

    override suspend fun setServerEnabled(name: String, enabled: Boolean) {
        if (name.isBlank()) return
        val binary = binary()
        withAppServerClient(binary) { client ->
            client.initialize()
            client.batchWrite(
                listOf(
                    ConfigEditRequest(
                        keyPath = "mcp_servers.${name.trim()}.enabled",
                        value = JsonPrimitive(enabled),
                    ),
                ),
            )
            client.reloadMcpServers()
        }
    }

    override suspend fun refreshStatuses(): Map<String, McpRuntimeStatus> {
        val binary = binary()
        return withAppServerClient(binary) { client ->
            client.initialize()
            client.listStatuses().remapStatusNames(listServers().map { it.name })
        }
    }

    override suspend fun testServer(name: String): McpTestResult {
        val status = refreshStatuses()[name]
            ?: return McpTestResult(
                success = false,
                summary = "No runtime status returned.",
                details = "Codex did not report a loaded MCP server named '$name'.",
            )
        if (!status.error.isNullOrBlank()) {
            return McpTestResult(
                success = false,
                summary = "Runtime reported an error.",
                details = status.error,
            )
        }
        val parts = mutableListOf<String>()
        parts += "${status.tools.size} tools"
        if (status.resources.isNotEmpty()) {
            parts += "${status.resources.size} resources"
        }
        if (status.resourceTemplates.isNotEmpty()) {
            parts += "${status.resourceTemplates.size} templates"
        }
        parts += when (status.authState) {
            McpAuthState.UNSUPPORTED -> "auth unsupported"
            McpAuthState.NOT_LOGGED_IN -> "not logged in"
            McpAuthState.BEARER_TOKEN -> "bearer token"
            McpAuthState.OAUTH -> "oauth ready"
            McpAuthState.UNKNOWN -> "auth unknown"
        }
        return McpTestResult(
            success = true,
            summary = parts.joinToString(", "),
        )
    }

    override suspend fun login(name: String): McpAuthActionResult {
        val binary = binary()
        val url = withAppServerClient(binary) { client ->
            client.initialize()
            client.startOAuthLogin(name)
        }
        return if (url.isNullOrBlank()) {
            McpAuthActionResult(
                success = true,
                message = "OAuth login started for '$name'.",
            )
        } else {
            McpAuthActionResult(
                success = true,
                message = "Open the authorization URL for '$name'.",
                authorizationUrl = url,
            )
        }
    }

    override suspend fun logout(name: String): McpAuthActionResult {
        val response = runCli("mcp", "logout", name)
        if (response.exitCode != 0) {
            return McpAuthActionResult(
                success = false,
                message = response.combinedOutput.ifBlank { "Failed to log out '$name'." },
            )
        }
        return McpAuthActionResult(
            success = true,
            message = response.combinedOutput.ifBlank { "Logged out '$name'." },
        )
    }

    private fun binary(): String {
        val resolution = launchResolution()
        when (resolution.codexStatus) {
            CodexEnvironmentStatus.MISSING -> error("Aura Code runtime path is not configured.")
            CodexEnvironmentStatus.FAILED -> error("Configured Codex Runtime Path is not executable. Update Settings and try again.")
            else -> return resolution.codexPath.trim()
        }
    }

    private fun runCli(vararg args: String): CommandExecutionResult {
        val resolution = launchResolution()
        val command = listOf(resolution.codexPath) + args.toList()
        return commandRunner(
            CommandExecutionRequest(
                command = command,
                environmentOverrides = resolution.environmentOverrides,
            ),
        )
    }

    private suspend fun <T> withAppServerClient(
        binary: String,
        block: suspend (CodexMcpAppServerClient) -> T,
    ): T {
        val client = appServerClientFactory(binary, launchResolution().environmentOverrides)
        return try {
            block(client)
        } finally {
            client.close()
        }
    }

    private fun launchResolution(): CodexEnvironmentResolution {
        return launchEnvironmentResolver(
            settings.state.executablePathFor(CodexProviderFactory.ENGINE_ID),
            settings.nodeExecutablePath(),
        )
    }

    private fun JsonObject.transportType(): McpTransportType? {
        return when (string("type")?.trim()?.lowercase()) {
            "stdio" -> McpTransportType.STDIO
            "streamable_http" -> McpTransportType.STREAMABLE_HTTP
            else -> null
        }
    }

    private fun parseAuthState(raw: String?): McpAuthState {
        return when (raw?.trim()) {
            "unsupported" -> McpAuthState.UNSUPPORTED
            "notLoggedIn" -> McpAuthState.NOT_LOGGED_IN
            "bearerToken" -> McpAuthState.BEARER_TOKEN
            "oAuth" -> McpAuthState.OAUTH
            else -> McpAuthState.UNKNOWN
        }
    }

    private fun JsonObject.toFlatConfigJson(): JsonObject {
        val transport = objectValue("transport") ?: return this
        return buildJsonObject {
            put("enabled", this@toFlatConfigJson["enabled"] ?: JsonPrimitive(true))
            when (transport.transportType()) {
                McpTransportType.STDIO -> {
                    transport["command"]?.let { put("command", it) }
                    transport["args"]?.let { put("args", it) }
                    transport["env"]?.let { put("env", it) }
                    transport["cwd"]?.let { put("cwd", it) }
                }

                McpTransportType.STREAMABLE_HTTP -> {
                    transport["url"]?.let { put("url", it) }
                    transport["bearer_token_env_var"]?.let { put("bearer_token_env_var", it) }
                    transport["http_headers"]?.let { put("http_headers", it) }
                    transport["env_http_headers"]?.let { put("env_http_headers", it) }
                }

                null -> {
                    this@toFlatConfigJson.forEach { (key, value) -> put(key, value) }
                }
            }
            this@toFlatConfigJson["required"]?.let { put("required", it) }
            this@toFlatConfigJson["startup_timeout_sec"]?.let { put("startup_timeout_sec", it) }
            this@toFlatConfigJson["tool_timeout_sec"]?.let { put("tool_timeout_sec", it) }
            this@toFlatConfigJson["enabled_tools"]?.let { put("enabled_tools", it) }
            this@toFlatConfigJson["disabled_tools"]?.let { put("disabled_tools", it) }
            this@toFlatConfigJson["scopes"]?.let { put("scopes", it) }
            this@toFlatConfigJson["oauth_resource"]?.let { put("oauth_resource", it) }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CodexMcpManagementAdapter::class.java)

        private fun runCommand(request: CommandExecutionRequest): CommandExecutionResult {
            val command = request.command
            val cli = GeneralCommandLine(command.first(), *command.drop(1).toTypedArray())
            cli.withWorkDirectory(File(System.getProperty("user.home").orEmpty().ifBlank { "." }))
            cli.environment.putAll(request.environmentOverrides)
            val output = CapturingProcessHandler(cli).runProcess(60_000)
            return CommandExecutionResult(
                exitCode = output.exitCode,
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }
    }
}

internal interface CodexMcpAppServerClient : AutoCloseable {
    suspend fun initialize()
    suspend fun batchWrite(edits: List<ConfigEditRequest>)
    suspend fun reloadMcpServers()
    suspend fun listStatuses(): Map<String, McpRuntimeStatus>
    suspend fun startOAuthLogin(name: String): String?
}

@OptIn(ExperimentalSerializationApi::class)
private class RealCodexMcpAppServerClient(
    binary: String,
    environmentOverrides: Map<String, String>,
    private val diagnosticLogger: (String) -> Unit = { message ->
        Logger.getInstance(CodexMcpManagementAdapter::class.java).info(message)
    },
) : CodexMcpAppServerClient {
    private val process = ProcessBuilder(listOf(binary, "app-server"))
        .apply {
            environment().putAll(environmentOverrides)
        }
        .redirectErrorStream(false)
        .start()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val managedScope: ManagedCoroutineScope = AppCoroutineManager.createScope(
        scopeName = "CodexMcpAppServerClient",
        dispatcher = Dispatchers.IO,
        failureReporter = { scopeName, label, error ->
            diagnosticLogger(
                "$scopeName coroutine failed${label?.let { ": $it" }.orEmpty()}: ${error.message}\n${error.stackTraceToString()}",
            )
        },
    )

    init {
        startReader()
    }

    override suspend fun initialize() {
        request(
            method = "initialize",
            params = CodexAppServerJsonSupport.initializeParams()
        )
        notify(method = "initialized", params = buildJsonObject { })
    }

    override suspend fun batchWrite(edits: List<ConfigEditRequest>) {
        request(
            method = "config/batchWrite",
            params = buildJsonObject {
                put(
                    "edits",
                    buildJsonArray {
                        edits.forEach { edit ->
                            add(
                                buildJsonObject {
                                    put("keyPath", edit.keyPath)
                                    put("mergeStrategy", edit.mergeStrategy)
                                    put("value", edit.value ?: JsonNull)
                                },
                            )
                        }
                    },
                )
                put("reloadUserConfig", true)
            },
        )
    }

    override suspend fun reloadMcpServers() {
        request(method = "config/mcpServer/reload", params = buildJsonObject { })
    }

    override suspend fun listStatuses(): Map<String, McpRuntimeStatus> {
        val statuses = linkedMapOf<String, McpRuntimeStatus>()
        var cursor: String? = null
        do {
            val result = request(
                method = "mcpServerStatus/list",
                params = buildJsonObject {
                    cursor?.let { put("cursor", it) }
                    put("limit", JsonPrimitive(100))
                },
            )
            result.arrayValue("data").orEmpty()
                .mapNotNull { it as? JsonObject }
                .forEach { status ->
                    val name = status.string("name") ?: return@forEach
                    val tools = status.objectValue("tools")
                        ?.keys
                        ?.sorted()
                        .orEmpty()
                    val resources = status.arrayValue("resources")
                        ?.mapNotNull { (it as? JsonObject)?.string("uri") }
                        .orEmpty()
                    val resourceTemplates = status.arrayValue("resourceTemplates")
                        ?.mapNotNull { (it as? JsonObject)?.string("uriTemplate") }
                        .orEmpty()
                    statuses[name] = McpRuntimeStatus(
                        authState = when (status.string("authStatus")) {
                            "unsupported" -> McpAuthState.UNSUPPORTED
                            "notLoggedIn" -> McpAuthState.NOT_LOGGED_IN
                            "bearerToken" -> McpAuthState.BEARER_TOKEN
                            "oAuth" -> McpAuthState.OAUTH
                            else -> McpAuthState.UNKNOWN
                        },
                        tools = tools,
                        resources = resources,
                        resourceTemplates = resourceTemplates,
                    )
                }
            cursor = result.string("nextCursor")
        } while (!cursor.isNullOrBlank())
        return statuses
    }

    override suspend fun startOAuthLogin(name: String): String? {
        val result = request(
            method = "mcpServer/oauth/login",
            params = buildJsonObject {
                put("name", name)
            },
        )
        return result.string("authorizationUrl")
    }

    override fun close() {
        managedScope.cancel()
        process.destroy()
    }

    private fun startReader() {
        managedScope.launch(label = "stderrReader") {
            process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        diagnosticLogger("Codex MCP app-server stderr: ${line.take(4000)}")
                    }
                }
            }
        }
        managedScope.launch(label = "stdoutReader") {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) return@forEach
                    diagnosticLogger("Codex MCP app-server recv: ${trimmed.take(4000)}")
                    val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return@forEach
                    val id = obj.string("id") ?: return@forEach
                    if (obj.containsKey("result") || obj.containsKey("error")) {
                        pending.remove(id)?.complete(obj)
                    }
                }
            }
        }
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject {
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
        val obj = response as? JsonObject ?: buildJsonObject { }
        obj["error"]?.let { error(it.toString()) }
        return obj.objectValue("result") ?: obj
    }

    private suspend fun notify(method: String, params: JsonObject) {
        writeJson(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            },
        )
    }

    private suspend fun writeJson(payload: JsonObject) {
        val serialized = json.encodeToString(JsonObject.serializer(), payload)
        diagnosticLogger("Codex MCP app-server send: ${serialized.take(4000)}")
        writer.write(serialized)
        writer.newLine()
        writer.flush()
    }
}

internal data class CommandExecutionRequest(
    val command: List<String>,
    val environmentOverrides: Map<String, String> = emptyMap(),
)

private fun JsonObjectBuilder.putJsonObject(
    key: String,
    builder: JsonObjectBuilder.() -> Unit,
) {
    put(key, buildJsonObject(builder))
}

private fun JsonObjectBuilder.put(key: String, value: String) {
    put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.put(key: String, value: Boolean) {
    put(key, JsonPrimitive(value))
}

private fun JsonObject.string(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.boolean(key: String): Boolean? {
    return (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}

private fun JsonObject.objectValue(key: String): JsonObject? {
    return this[key] as? JsonObject
}

private fun JsonObject.arrayValue(key: String): JsonArray? {
    return this[key] as? JsonArray
}

private fun Map<String, McpRuntimeStatus>.remapStatusNames(
    configuredNames: List<String>,
): Map<String, McpRuntimeStatus> {
    if (isEmpty() || configuredNames.isEmpty()) return this
    val exactNames = configuredNames.toSet()
    val configuredBySanitized = configuredNames.groupBy(::sanitizeMcpServerName)
    return entries.associate { (name, status) ->
        val resolvedName = when {
            name in exactNames -> name
            else -> configuredBySanitized[sanitizeMcpServerName(name)]?.singleOrNull() ?: name
        }
        resolvedName to status
    }
}

private fun sanitizeMcpServerName(name: String): String {
    val normalized = buildString(name.length) {
        name.forEach { character ->
            if (character.isAsciiLetterOrDigit()) {
                append(character.lowercaseChar())
            } else {
                append('_')
            }
        }
    }.trim('_')
    return normalized.ifBlank { "app" }
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'
