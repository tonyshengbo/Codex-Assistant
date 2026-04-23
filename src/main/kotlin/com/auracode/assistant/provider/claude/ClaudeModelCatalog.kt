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
    /** 当前 Claude 默认模型，对应 CLI 里的 Default/sonnet。 */
    const val defaultModel: String = "claude-sonnet-4-6"
    private const val standardContextWindow: Int = 200_000
    private const val extendedContextWindow: Int = 1_000_000
    private const val sonnetOneMillionModel: String = "claude-sonnet-4-6[1m]"
    private const val opusModel: String = "claude-opus-4-6"
    private const val opusOneMillionModel: String = "claude-opus-4-6[1m]"
    private const val haikuModel: String = "claude-haiku-4-5-20251001"
    private val curatedOptions: List<ClaudeModelOption> = listOf(
        ClaudeModelOption(
            id = defaultModel,
            shortName = "Sonnet 4.6",
        ),
        ClaudeModelOption(
            id = sonnetOneMillionModel,
            shortName = "Sonnet 4.6 1M",
        ),
        ClaudeModelOption(
            id = opusModel,
            shortName = "Opus 4.6",
        ),
        ClaudeModelOption(
            id = opusOneMillionModel,
            shortName = "Opus 4.6 1M",
        ),
        ClaudeModelOption(
            id = haikuModel,
            shortName = "Haiku 4.5",
        ),
    )
    private val legacyModelReplacements: Map<String, String> = mapOf(
        "claude-sonnet-4-5" to defaultModel,
        "claude-opus-4-1" to opusModel,
        "claude-haiku-4-5" to haikuModel,
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
        return if (normalized.endsWith("[1m]")) {
            extendedContextWindow
        } else {
            standardContextWindow
        }
    }
}
