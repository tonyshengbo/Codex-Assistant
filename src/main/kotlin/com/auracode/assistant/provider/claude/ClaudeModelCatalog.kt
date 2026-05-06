package com.auracode.assistant.provider.claude

/** 描述一个可在界面中展示的 Claude 模型选项。 */
internal data class ClaudeModelOption(
    val id: String,
    val shortName: String,
)

/**
 * 维护 Claude 可选模型列表，并兼容旧版本持久化下来的模型标识。
 */
internal object ClaudeModelCatalog {
    /** 当前 Claude 默认模型。 */
    const val defaultModel: String = "claude-opus-4-7"
    private const val standardContextWindow: Int = 200_000
    private const val extendedContextWindow: Int = 1_000_000
    private const val opusNewModel: String = "claude-opus-4-7"
    private const val sonnetModel: String = "claude-sonnet-4-6"
    private const val opusModel: String = "claude-opus-4-6"
    private const val haikuModel: String = "claude-haiku-4-5-20251001"
    private val curatedOptions: List<ClaudeModelOption> = listOf(
        ClaudeModelOption(
            id = opusNewModel,
            shortName = "Opus 4.7 [1m]",
        ),
        ClaudeModelOption(
            id = sonnetModel,
            shortName = "Sonnet 4.6 [1m]",
        ),
        ClaudeModelOption(
            id = haikuModel,
            shortName = "Haiku 4.5 [200k]",
        ),
        ClaudeModelOption(
            id = opusModel,
            shortName = "Opus 4.6 [1m]",
        ),
    )
    private val legacyModelReplacements: Map<String, String> = mapOf(
        "claude-sonnet-4-5" to sonnetModel,
        "claude-opus-4-1" to opusNewModel,
        "claude-haiku-4-5" to haikuModel,
        "claude-sonnet-4-6[1m]" to sonnetModel,
        "claude-opus-4-6[1m]" to opusModel,
    )

    /** 返回当前 Claude 引擎内置的模型列表。 */
    fun ids(): List<String> = curatedOptions.map { it.id }

    /** 返回当前 Claude 引擎内置的模型选项及展示短名。 */
    fun options(): List<ClaudeModelOption> = curatedOptions

    /** 通过模型标识解析对应的展示选项。 */
    fun option(modelId: String?): ClaudeModelOption? {
        val normalized = normalize(modelId.orEmpty())
        if (normalized.isBlank()) return null
        return curatedOptions.firstOrNull { it.id == normalized }
    }

    /** 将旧版本保存的 Claude 模型值迁移到当前仍可用的模型标识。 */
    fun normalize(modelId: String): String {
        val normalized = modelId.trim()
        if (normalized.isBlank()) return defaultModel
        return legacyModelReplacements[normalized] ?: normalized
    }

    /** 返回模型的默认上下文窗口，用于 result 未显式回传时的兜底。 */
    fun contextWindow(modelId: String?): Int {
        val normalized = normalize(modelId.orEmpty())
        return when (normalized) {
            opusNewModel, sonnetModel, opusModel -> extendedContextWindow
            else -> standardContextWindow
        }
    }
}
