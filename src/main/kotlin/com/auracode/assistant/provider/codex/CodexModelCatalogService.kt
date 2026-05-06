package com.auracode.assistant.provider.codex

import com.auracode.assistant.settings.AgentSettingsService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 管理 Codex 模型目录的获取与缓存。
 * 通过 `codex debug models` 获取最新列表，与缓存比较后返回变更结果。
 */
internal class CodexModelCatalogService(
    private val settingsService: AgentSettingsService,
    private val environmentDetector: CodexEnvironmentDetector,
    private val commandRunner: (List<String>, Map<String, String>) -> CodexCliCommandResult = ::runCodexCliCommand,
) {
    /**
     * 返回当前可用模型列表：有缓存用缓存，否则返回内置 fallback。
     */
    fun currentModels(): List<CodexModelEntry> {
        val cachedIds = settingsService.cachedCodexModelIds()
        val cachedNames = settingsService.cachedCodexModelDisplayNames()
        if (cachedIds.isNotEmpty()) {
            return cachedIds.mapIndexed { i, slug ->
                CodexModelEntry(slug = slug, displayName = cachedNames.getOrElse(i) { slug })
            }
        }
        return CodexModelCatalog.options.map { CodexModelEntry(slug = it.id, displayName = it.description) }
    }

    /**
     * 执行 CLI 获取最新列表，与缓存比较后返回结果。
     * 失败时静默返回 Failed，不影响现有缓存。
     */
    fun refresh(): CodexModelCatalogRefreshResult {
        val resolution = runCatching {
            environmentDetector.resolveForLaunch(
                configuredCodexPath = settingsService.state.executablePathFor("codex"),
                configuredNodePath = settingsService.nodeExecutablePath(),
            )
        }.getOrNull() ?: return CodexModelCatalogRefreshResult.Failed

        val result = runCatching {
            commandRunner(
                listOf(resolution.codexPath, "debug", "models"),
                resolution.environmentOverrides,
            )
        }.getOrNull() ?: return CodexModelCatalogRefreshResult.Failed

        if (result.exitCode != 0) return CodexModelCatalogRefreshResult.Failed

        val fetched = parseCodexModelEntries(result.stdout)
        if (fetched.isEmpty()) return CodexModelCatalogRefreshResult.Failed

        val currentSlugs = currentModels().map { it.slug }
        val fetchedSlugs = fetched.map { it.slug }
        if (fetchedSlugs == currentSlugs) return CodexModelCatalogRefreshResult.Unchanged

        settingsService.setCachedCodexModelIds(fetchedSlugs)
        settingsService.setCachedCodexModelDisplayNames(fetched.map { it.displayName })
        return CodexModelCatalogRefreshResult.Changed(fetched)
    }
}

/**
 * 解析 `codex debug models` 的 JSON 输出。
 * 过滤 visibility == "list" 的条目，按 priority 升序排列。
 */
private fun parseCodexModelEntries(json: String): List<CodexModelEntry> {
    return runCatching {
        val root = Json.parseToJsonElement(json)
        val array: JsonArray = when {
            root is JsonArray -> root
            else -> root.jsonObject["models"]?.jsonArray ?: return emptyList()
        }
        array.mapNotNull { element ->
            val obj = element.jsonObject
            val visibility = obj["visibility"]?.jsonPrimitive?.contentOrNull
            if (visibility != "list") return@mapNotNull null
            val slug = obj["slug"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val displayName = obj["display_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: slug
            val priority = obj["priority"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE
            Triple(priority, slug, displayName)
        }
            .sortedBy { it.first }
            .map { (_, slug, displayName) -> CodexModelEntry(slug = slug, displayName = displayName) }
    }.getOrDefault(emptyList())
}
