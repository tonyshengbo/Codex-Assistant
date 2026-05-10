package com.auracode.assistant.settings.mcp

import com.auracode.assistant.provider.claude.ClaudeProviderFactory
import com.auracode.assistant.provider.codex.CodexExecutableResolver
import com.auracode.assistant.provider.runtime.DefaultRuntimeLaunchResolver
import com.auracode.assistant.provider.runtime.RuntimeLaunchResolver
import com.auracode.assistant.settings.AgentSettingsService
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 通过直接读写 ~/.claude.json 管理 Claude CLI 的 MCP 服务器配置。
 *
 * 配置文件格式（~/.claude.json）：
 * ```json
 * {
 *   "mcpServers": {
 *     "my-server": { "type": "stdio", "command": "...", "args": [...], "env": {...} },
 *     "oauth-server": { "type": "http", "url": "...", "oauth": { "clientId": "...", "callbackPort": 9999 } }
 *   },
 *   "disabledMcpServers": ["my-server"]
 * }
 * ```
 *
 * 与 Codex 适配器的主要差异：
 * - 无 app-server，CRUD 直接操作 JSON 文件
 * - 禁用状态通过顶层 disabledMcpServers 数组管理
 * - OAuth 通过 `claude mcp add --client-id ... --callback-port ...` 命令初始化
 * - 无独立的 login/logout 命令，OAuth token 由 Claude CLI 运行时自动管理
 */
@OptIn(ExperimentalSerializationApi::class)
internal class ClaudeMcpManagementAdapter(
    private val settings: AgentSettingsService,
    private val executableResolver: CodexExecutableResolver = CodexExecutableResolver(),
    private val shellEnvironmentLoader: () -> Map<String, String> = { System.getenv() },
    private val runtimeLaunchResolver: RuntimeLaunchResolver = DefaultRuntimeLaunchResolver(
        executableResolver = executableResolver,
        shellEnvironmentLoader = shellEnvironmentLoader,
    ),
    private val commandRunner: (CommandExecutionRequest) -> CommandExecutionResult = ::runCommand,
) : McpManagementAdapter {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    // Claude CLI 的全局配置文件
    private val configFile: File
        get() = File(System.getProperty("user.home"), ".claude.json")

    // ── McpManagementAdapter 实现 ─────────────────────────────────────────────

    override suspend fun listServers(): List<McpServerSummary> {
        val root = readConfig()
        val servers = root.mcpServers()
        val disabled = root.disabledServers()
        return servers.entries.map { (name, config) ->
            val transportType = config.detectTransportType()
            McpServerSummary(
                name = name,
                engineId = ClaudeProviderFactory.ENGINE_ID,
                transportType = transportType,
                displayTarget = config.buildDisplayTarget(transportType),
                enabled = name !in disabled,
                // HTTP 服务器若配置了 oauth 字段则显示 NOT_LOGGED_IN，允许用户触发授权流程
                authState = when {
                    transportType == McpTransportType.STREAMABLE_HTTP && config.hasOAuth() -> McpAuthState.NOT_LOGGED_IN
                    else -> McpAuthState.UNSUPPORTED
                },
            )
        }
    }

    override suspend fun getServer(name: String): McpServerDraft? {
        val config = readConfig().mcpServers()[name] ?: return null
        return McpServerDraft(
            originalName = name,
            engineId = ClaudeProviderFactory.ENGINE_ID,
            name = name,
            configJson = McpServerDraft.entryConfigJson(name, config.toInternalConfig()),
        )
    }

    override suspend fun getEditorDraft(): McpServerDraft {
        val servers = readConfig().mcpServers()
        if (servers.isEmpty()) return McpServerDraft(engineId = ClaudeProviderFactory.ENGINE_ID)
        val entries = servers.entries.map { (name, config) ->
            NamedMcpServerConfig(name, config.toInternalConfig())
        }
        return McpServerDraft(
            engineId = ClaudeProviderFactory.ENGINE_ID,
            configJson = McpServerDraft.serversConfigJson(entries),
        )
    }

    override suspend fun saveServers(drafts: List<McpServerDraft>) {
        if (drafts.isEmpty()) return
        val root = readConfig().toMutableMap()
        val servers = root.mcpServersMap()
        drafts.forEach { draft ->
            val config = draft.parsedConfigJson().getOrElse { throw it }
            servers[draft.name.trim()] = config.toClaudeConfig()
        }
        root["mcpServers"] = JsonObject(servers)
        writeConfig(JsonObject(root))
    }

    override suspend fun saveServer(draft: McpServerDraft) {
        val root = readConfig().toMutableMap()
        val servers = root.mcpServersMap()
        val config = draft.parsedConfigJson().getOrElse { throw it }
        val originalName = draft.originalName?.trim().orEmpty()
        val newName = draft.name.trim()
        if (originalName.isNotBlank() && originalName != newName) {
            servers.remove(originalName)
            // 同步更新 disabledMcpServers 中的旧名称
            val disabled = root.disabledList().toMutableList()
            if (disabled.remove(originalName)) {
                disabled.add(newName)
                root["disabledMcpServers"] = JsonArray(disabled.map { JsonPrimitive(it) })
            }
        }
        servers[newName] = config.toClaudeConfig()
        root["mcpServers"] = JsonObject(servers)
        writeConfig(JsonObject(root))
    }

    override suspend fun deleteServer(name: String): Boolean {
        if (name.isBlank()) return false
        val root = readConfig().toMutableMap()
        val servers = root.mcpServersMap()
        if (!servers.containsKey(name)) return false
        servers.remove(name)
        root["mcpServers"] = JsonObject(servers)
        // 同步从 disabledMcpServers 中移除
        val disabled = root.disabledList().filter { it != name }
        root["disabledMcpServers"] = JsonArray(disabled.map { JsonPrimitive(it) })
        writeConfig(JsonObject(root))
        return true
    }

    override suspend fun setServerEnabled(name: String, enabled: Boolean) {
        if (name.isBlank()) return
        val root = readConfig().toMutableMap()
        val disabled = root.disabledList().toMutableList()
        if (enabled) {
            disabled.remove(name)
        } else if (!disabled.contains(name)) {
            disabled.add(name)
        }
        root["disabledMcpServers"] = JsonArray(disabled.map { JsonPrimitive(it) })
        writeConfig(JsonObject(root))
    }

    /** Claude CLI 无 app-server，无法获取运行时状态。 */
    override suspend fun refreshStatuses(serverNames: List<String>?): Map<String, McpRuntimeStatus> = emptyMap()

    override suspend fun testServer(name: String): McpTestResult {
        val server = listServers().find { it.name == name }
            ?: return McpTestResult(success = false, summary = "Server '$name' not found in ~/.claude.json.")
        val transportLabel = server.transportType.name.lowercase().replace('_', ' ')
        val authLabel = if (server.authState == McpAuthState.OAUTH) ", oauth configured" else ""
        val enabledLabel = if (!server.enabled) ", disabled" else ""
        return McpTestResult(
            success = true,
            summary = "Configured ($transportLabel$authLabel$enabledLabel).",
        )
    }

    /**
     * 通过 `claude mcp add --transport http --client-id ... --callback-port ...` 初始化 OAuth。
     *
     * Claude CLI 会在此过程中打开浏览器完成授权，授权完成后 token 写入本地存储。
     * 仅支持 HTTP 类型的服务器。
     */
    override suspend fun login(
        name: String,
        onAuthorizationUrl: suspend (String?) -> Unit,
    ): McpAuthActionResult {
        val server = readConfig().mcpServers()[name.trim()]
            ?: return McpAuthActionResult(success = false, message = "Server '$name' not found.")
        if (server.detectTransportType() != McpTransportType.STREAMABLE_HTTP) {
            return McpAuthActionResult(
                success = false,
                message = "OAuth is only supported for HTTP MCP servers.",
            )
        }
        val url = server["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (url.isBlank()) {
            return McpAuthActionResult(success = false, message = "Server '$name' has no URL configured.")
        }
        val oauth = server["oauth"] as? JsonObject
        val clientId = oauth?.get("clientId")?.jsonPrimitive?.contentOrNull.orEmpty()
        val callbackPort = oauth?.get("callbackPort")?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        // 通知 UI 即将打开浏览器（Claude CLI 会自动处理，无法提前获取 URL）
        onAuthorizationUrl(null)

        val args = buildList {
            add("mcp")
            add("add")
            add("--scope")
            add("user")
            add("--transport")
            add("http")
            if (clientId.isNotBlank()) {
                add("--client-id")
                add(clientId)
            }
            if (callbackPort != null) {
                add("--callback-port")
                add(callbackPort.toString())
            }
            add(name.trim())
            add(url)
        }
        val result = runCli(*args.toTypedArray())
        return if (result.exitCode == 0) {
            McpAuthActionResult(success = true, message = result.combinedOutput.ifBlank { "OAuth initialized for '$name'." })
        } else {
            McpAuthActionResult(success = false, message = result.combinedOutput.ifBlank { "Failed to initialize OAuth for '$name'." })
        }
    }

    override suspend fun cancelLogin(name: String): McpAuthActionResult {
        // Claude CLI 的 OAuth 流程由 CLI 自身管理，无法从外部取消
        return McpAuthActionResult(success = false, message = "OAuth login cancellation is not supported for Claude MCP servers.")
    }

    /**
     * 通过删除服务器配置中的 oauth 字段来清除 OAuth 凭证。
     * Claude CLI 没有独立的 logout 命令，token 存储在 CLI 内部。
     */
    override suspend fun logout(name: String): McpAuthActionResult {
        if (name.isBlank()) return McpAuthActionResult(success = false, message = "Server name is required.")
        val root = readConfig().toMutableMap()
        val servers = root.mcpServersMap()
        val server = servers[name.trim()] as? JsonObject
            ?: return McpAuthActionResult(success = false, message = "Server '$name' not found.")
        if (!server.hasOAuth()) {
            return McpAuthActionResult(success = false, message = "Server '$name' has no OAuth configuration.")
        }
        // 移除 oauth 字段
        servers[name.trim()] = JsonObject(server.toMutableMap().apply { remove("oauth") })
        root["mcpServers"] = JsonObject(servers)
        writeConfig(JsonObject(root))
        return McpAuthActionResult(success = true, message = "Removed OAuth configuration for '$name'.")
    }

    // ── 私有辅助方法 ──────────────────────────────────────────────────────────

    /** 读取并解析 ~/.claude.json，文件不存在或格式损坏时返回空对象。 */
    private fun readConfig(): JsonObject {
        val file = configFile
        if (!file.exists()) return JsonObject(emptyMap())
        return runCatching {
            json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        }.onFailure { error ->
            LOG.warn("Failed to parse ~/.claude.json: ${error.message}")
        }.getOrDefault(JsonObject(emptyMap()))
    }

    /** 将 JsonObject 写回 ~/.claude.json，保留文件中的其他字段。 */
    private fun writeConfig(root: JsonObject) {
        val file = configFile
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(JsonObject.serializer(), root), Charsets.UTF_8)
    }

    /** 执行 Claude CLI 命令。 */
    private fun runCli(vararg args: String): CommandExecutionResult {
        val configuredPath = settings.state.executablePathFor(ClaudeProviderFactory.ENGINE_ID)
        val resolution = runtimeLaunchResolver.resolve(
            commandName = ClaudeProviderFactory.ENGINE_ID,
            configuredCliPath = configuredPath,
            configuredNodePath = settings.nodeExecutablePath(),
        )
        return commandRunner(
            CommandExecutionRequest(
                command = listOf(resolution.cliPath) + args.toList(),
                environmentOverrides = resolution.environmentOverrides,
            ),
        )
    }

    companion object {
        private val LOG = Logger.getInstance(ClaudeMcpManagementAdapter::class.java)

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

// ── JsonObject 扩展 ───────────────────────────────────────────────────────────

/** 提取 mcpServers 字段为 name → config 映射。 */
private fun JsonObject.mcpServers(): Map<String, JsonObject> {
    return (this["mcpServers"] as? JsonObject)
        ?.entries
        ?.mapNotNull { (name, value) -> (value as? JsonObject)?.let { name to it } }
        ?.toMap()
        ?: emptyMap()
}

/** 提取 mcpServers 为可变 map，用于写操作。 */
private fun Map<String, *>.mcpServersMap(): MutableMap<String, JsonObject> {
    return ((this as? JsonObject)?.get("mcpServers") as? JsonObject)
        ?.entries
        ?.mapNotNull { (name, value) -> (value as? JsonObject)?.let { name to it } }
        ?.toMap()
        ?.toMutableMap()
        ?: mutableMapOf()
}

/** 提取 disabledMcpServers 数组。 */
private fun JsonObject.disabledServers(): Set<String> {
    return (this["disabledMcpServers"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.toSet()
        ?: emptySet()
}

/** 提取 disabledMcpServers 为可变列表，用于写操作。 */
private fun Map<String, *>.disabledList(): List<String> {
    return ((this as? JsonObject)?.get("disabledMcpServers") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?: emptyList()
}

/** 根据配置字段判断传输类型。 */
private fun JsonObject.detectTransportType(): McpTransportType {
    val type = this["type"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
    return when {
        type == "http" || type == "sse" -> McpTransportType.STREAMABLE_HTTP
        this["url"] != null -> McpTransportType.STREAMABLE_HTTP
        else -> McpTransportType.STDIO
    }
}

/** 判断服务器是否配置了 OAuth。 */
private fun JsonObject.hasOAuth(): Boolean = this["oauth"] is JsonObject

/** 构建列表页显示文本。 */
private fun JsonObject.buildDisplayTarget(transportType: McpTransportType): String {
    return when (transportType) {
        McpTransportType.STDIO -> buildString {
            append(this@buildDisplayTarget["command"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val args = (this@buildDisplayTarget["args"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            if (args.isNotEmpty()) {
                append(" ")
                append(args.joinToString(" "))
            }
        }.trim()
        McpTransportType.STREAMABLE_HTTP -> this["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}

/**
 * 将 Claude 格式转换为内部编辑器格式。
 * 内部格式添加 enabled 字段（供编辑器展示），保留 oauth 字段。
 */
private fun JsonObject.toInternalConfig(): JsonObject {
    return buildJsonObject {
        put("enabled", JsonPrimitive(true))
        this@toInternalConfig.forEach { (key, value) -> put(key, value) }
    }
}

/**
 * 将内部编辑器格式转换为 Claude 格式。
 * 移除 enabled 字段（Claude 用 disabledMcpServers 数组管理启用状态）。
 */
private fun JsonObject.toClaudeConfig(): JsonObject {
    return JsonObject(this.toMutableMap().apply { remove("enabled") })
}
