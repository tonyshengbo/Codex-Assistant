package com.auracode.assistant.provider.codex

/** Describes one selectable Codex model option exposed in settings and composer UI. */
data class CodexModelOption(
    val id: String,
    val description: String,
)

/** Holds the built-in Codex model list and default model selection. */
object CodexModelCatalog {
    const val defaultModel: String = "gpt-5.4"

    val options: List<CodexModelOption> = listOf(
        CodexModelOption(
            id = "gpt-5.3-codex",
            description = "GPT 5.3 Codex",
        ),
        CodexModelOption(
            id = "gpt-5.4",
            description = "GPT 5.4",
        ),
        CodexModelOption(
            id = "gpt-5.2-codex",
            description = "GPT 5.2 Codex",
        ),
        CodexModelOption(
            id = "gpt-5.1-codex-max",
            description = "GPT 5.1 Codex Max",
        ),
        CodexModelOption(
            id = "gpt-5.1-codex-mini",
            description = "GPT 5.1 Codex Mini",
        ),
    )

    /** 返回当前 Codex 引擎内置模型的稳定标识列表。 */
    fun ids(): List<String> = options.map { it.id }

    /** 通过模型标识解析对应的展示选项。 */
    fun option(modelId: String?): CodexModelOption? {
        val normalized = modelId?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return options.firstOrNull { it.id == normalized }
    }
}
