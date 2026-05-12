package com.auracode.assistant.settings.mcp

import com.auracode.assistant.provider.CodexProviderFactory
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal enum class McpTransportType {
    STDIO,
    STREAMABLE_HTTP,
}

internal sealed interface McpTransportDraft {
    val type: McpTransportType

    data class Stdio(
        val command: String = "",
        val args: String = "",
        val env: String = "",
        val cwd: String = "",
    ) : McpTransportDraft {
        override val type: McpTransportType = McpTransportType.STDIO
    }

    data class StreamableHttp(
        val url: String = "",
        val bearerTokenEnvVar: String = "",
    ) : McpTransportDraft {
        override val type: McpTransportType = McpTransportType.STREAMABLE_HTTP
    }
}

internal data class McpServerDraft(
    val originalName: String? = null,
    val engineId: String = CodexProviderFactory.ENGINE_ID,
    val name: String = "",
    val transport: McpTransportDraft = McpTransportDraft.Stdio(),
    val configJson: String = defaultConfigJson(),
) {
    val transportType: McpTransportType
        get() = transport.type

    fun withTransportType(type: McpTransportType): McpServerDraft {
        if (type == transport.type) return this
        return copy(
            transport = when (type) {
                McpTransportType.STDIO -> McpTransportDraft.Stdio()
                McpTransportType.STREAMABLE_HTTP -> McpTransportDraft.StreamableHttp()
            },
        )
    }

    fun parsedConfigJson(): Result<JsonObject> {
        val raw = configJson.trim()
        if (raw.isBlank()) {
            return Result.failure(IllegalArgumentException("JSON config is required."))
        }
        return runCatching {
            val root = jsonParser.parseToJsonElement(raw).jsonObject
            val wrappedServers = root["mcpServers"]
            if (wrappedServers != null) {
                val serversObject = wrappedServers as? JsonObject
                    ?: throw IllegalArgumentException("\"mcpServers\" must be a JSON object.")
                val entries = parseEntries(serversObject)
                if (entries.size != 1) {
                    throw IllegalArgumentException("JSON must contain exactly one MCP server.")
                }
                return@runCatching entries.single().config
            }
            normalizeConfigJson(root)
        }.recoverCatching { cause ->
            throw IllegalArgumentException(cause.message ?: "JSON config must be a valid JSON object.")
        }
    }

    fun configTransportType(): McpTransportType? {
        val config = parsedConfigJson().getOrNull() ?: return null
        return when {
            config["command"]?.jsonPrimitive?.content.orEmpty().isNotBlank() -> McpTransportType.STDIO
            config["url"]?.jsonPrimitive?.content.orEmpty().isNotBlank() -> McpTransportType.STREAMABLE_HTTP
            else -> null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }
        private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "  " }
        fun defaultConfigJson(): String = """
            {
              "mcpServers": {
                "server-name": {
                  "enabled": true,
                  "command": "",
                  "args": []
                }
              }
            }
        """.trimIndent()

        fun entryConfigJson(name: String, config: JsonObject): String {
            return serversConfigJson(listOf(NamedMcpServerConfig(name.trim(), config)))
        }

        fun serversConfigJson(entries: List<NamedMcpServerConfig>): String {
            return prettyJson.encodeToString(
                JsonElement.serializer(),
                buildJsonObject {
                    put(
                        "mcpServers",
                        buildJsonObject {
                            entries.forEach { entry ->
                                put(entry.name.trim(), entry.config)
                            }
                        },
                    )
                },
            )
        }

        internal fun normalizeConfigJson(config: JsonObject): JsonObject {
            val transport = config["transport"] as? JsonObject ?: return config
            val type = transport["type"]?.jsonPrimitive?.content?.trim()?.lowercase()
            return buildJsonObject {
                put("enabled", config["enabled"] ?: JsonPrimitive(true))
                when (type) {
                    "stdio" -> {
                        transport["command"]?.let { put("command", it) }
                        transport["args"]?.let { put("args", it) }
                        transport["env"]?.let { put("env", it) }
                        transport["cwd"]?.let { put("cwd", it) }
                    }

                    "streamable_http" -> {
                        transport["url"]?.let { put("url", it) }
                        transport["bearer_token_env_var"]?.let { put("bearer_token_env_var", it) }
                        transport["http_headers"]?.let { put("http_headers", it) }
                        transport["env_http_headers"]?.let { put("env_http_headers", it) }
                    }

                    else -> {
                        config.forEach { (key, value) -> put(key, value) }
                    }
                }
            }
        }
    }

    fun syncNameFromConfig(): McpServerDraft {
        val resolvedName = parsedEditorJson().getOrNull()?.entries?.singleOrNull()?.name?.trim().orEmpty()
        return if (resolvedName.isBlank()) this else copy(name = resolvedName)
    }

    fun usesLegacyConfigShape(): Boolean = false

    fun parseSingleServerEntry(): Result<NamedMcpServerConfig> {
        val raw = configJson.trim()
        if (raw.isBlank()) {
            return Result.failure(IllegalArgumentException("JSON config is required."))
        }
        return parsedEditorJson().mapCatching { parsed ->
            val entries = parsed.entries
            when (entries.size) {
                0 -> throw IllegalArgumentException("JSON must contain exactly one MCP server.")
                1 -> entries.single()
                else -> throw IllegalArgumentException("JSON must contain exactly one MCP server.")
            }
        }.recoverCatching { cause ->
            throw IllegalArgumentException(cause.message ?: "JSON config must be a valid JSON object.")
        }
    }

    fun parseServerEntries(): Result<List<NamedMcpServerConfig>> {
        return parsedEditorJson().mapCatching { parsed -> parsed.entries }
    }

    internal fun parsedEditorJson(): Result<ParsedMcpEditorJson.Servers> = runCatching {
        val root = jsonParser.parseToJsonElement(configJson.trim()).jsonObject
        val wrappedServers = root["mcpServers"]
            ?: throw IllegalArgumentException("New servers must use {\"mcpServers\": {...}} JSON.")
        val serversObject = wrappedServers as? JsonObject
            ?: throw IllegalArgumentException("\"mcpServers\" must be a JSON object.")
        ParsedMcpEditorJson.Servers(parseEntries(serversObject))
    }

    fun editorDisplayJson(): String {
        return parsedEditorJson()
            .map { parsed -> serversConfigJson(parsed.entries) }
            .getOrDefault(configJson.trim())
    }

    private fun parseEntries(root: JsonObject): List<NamedMcpServerConfig> {
        return root.entries.map { entry ->
            val entryName = entry.key.trim()
            if (entryName.isBlank()) {
                throw IllegalArgumentException("Server name cannot be blank.")
            }
            val entryConfig = entry.value as? JsonObject
                ?: throw IllegalArgumentException("Server '$entryName' must map to a JSON object.")
            NamedMcpServerConfig(entryName, normalizeConfigJson(entryConfig))
        }
    }
}

internal enum class McpAuthState {
    UNSUPPORTED,
    NOT_LOGGED_IN,
    BEARER_TOKEN,
    OAUTH,
    UNKNOWN,
}

internal data class McpRuntimeStatus(
    val authState: McpAuthState = McpAuthState.UNKNOWN,
    val tools: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
    val resourceTemplates: List<String> = emptyList(),
    val error: String? = null,
)

internal data class McpTestResult(
    val success: Boolean,
    val summary: String,
    val details: String = "",
    val testedAt: Long = System.currentTimeMillis(),
)

internal data class McpServerSummary(
    val name: String,
    val engineId: String = CodexProviderFactory.ENGINE_ID,
    val transportType: McpTransportType,
    val displayTarget: String,
    val enabled: Boolean = true,
    val authState: McpAuthState = McpAuthState.UNKNOWN,
)

internal data class McpBusyState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val testingName: String? = null,
    val deletingName: String? = null,
    val authenticatingName: String? = null,
)

internal data class McpValidationErrors(
    val name: String? = null,
    val json: String? = null,
) {
    fun hasAny(): Boolean {
        return listOf(name, json).any { !it.isNullOrBlank() }
    }
}

internal data class NamedMcpServerConfig(
    val name: String,
    val config: JsonObject,
)

internal data class McpAuthActionResult(
    val message: String,
    val authorizationUrl: String? = null,
    val success: Boolean = true,
)

internal data class CommandExecutionResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
) {
    val combinedOutput: String
        get() = listOf(stdout.trim(), stderr.trim())
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
}

internal data class ConfigEditRequest(
    val keyPath: String,
    val value: JsonElement?,
    val mergeStrategy: String = "replace",
)

internal fun McpTransportDraft.displayTarget(): String = when (this) {
    is McpTransportDraft.Stdio -> buildString {
        append(command.trim())
        if (args.isNotBlank()) {
            append(" ")
            append(args.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.joinToString(" "))
        }
    }.trim()

    is McpTransportDraft.StreamableHttp -> url.trim()
}

internal fun McpServerDraft.validate(): McpValidationErrors {
    val jsonError = parseSingleServerEntry()
        .mapCatching { entry ->
            val resolvedName = entry.name.trim()
            if (resolvedName.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) {
                throw IllegalArgumentException("Server '$resolvedName': use only letters, numbers, '-' and '_'.")
            }
            val config = entry.config
            when {
                config["command"]?.jsonPrimitive?.content.orEmpty().isNotBlank() -> {
                    if (config["url"] != null) {
                        throw IllegalArgumentException("Server '$resolvedName': stdio config cannot include url.")
                    }
                }

                config["url"]?.jsonPrimitive?.content.orEmpty().isNotBlank() -> {
                    if (config["command"] != null) {
                        throw IllegalArgumentException("Server '$resolvedName': streamable_http config cannot include command.")
                    }
                }

                else -> throw IllegalArgumentException("Server '$resolvedName': config must include either command or url.")
            }
        }
        .exceptionOrNull()
        ?.message

    return McpValidationErrors(
        name = null,
        json = jsonError,
    )
}

internal object ParsedMcpEditorJson {
    data class Servers(
        val entries: List<NamedMcpServerConfig>,
    )
}

internal fun String.parseArgsText(): List<String> {
    return lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
}

internal fun String.parseEnvText(): Result<Map<String, String>> {
    if (isBlank()) return Result.success(emptyMap())
    val values = linkedMapOf<String, String>()
    lineSequence().forEachIndexed { index, raw ->
        val line = raw.trim()
        if (line.isBlank()) return@forEachIndexed
        val separator = line.indexOf('=')
        if (separator <= 0) {
            return Result.failure(
                IllegalArgumentException("Environment entries must use KEY=VALUE on line ${index + 1}."),
            )
        }
        val key = line.substring(0, separator).trim()
        if (key.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Environment entries must use KEY=VALUE on line ${index + 1}."),
            )
        }
        values[key] = line.substring(separator + 1)
    }
    return Result.success(values)
}
