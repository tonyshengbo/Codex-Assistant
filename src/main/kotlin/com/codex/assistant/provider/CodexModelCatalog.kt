package com.codex.assistant.provider

data class CodexModelOption(
    val id: String,
    val description: String,
)

object CodexModelCatalog {
    const val defaultModel: String = "gpt-5.3-codex"

    val options: List<CodexModelOption> = listOf(
        CodexModelOption(
            id = "gpt-5.3-codex",
            description = "最新前沿智能模型，能力全面增强",
        ),
        CodexModelOption(
            id = "gpt-5.4",
            description = "最新前沿模型，能力进一步增强",
        ),
        CodexModelOption(
            id = "gpt-5.2-codex",
            description = "最新前沿智能模型",
        ),
        CodexModelOption(
            id = "gpt-5.1-codex-max",
            description = "针对 Codex 优化的旗舰模型，深度与快速推理",
        ),
        CodexModelOption(
            id = "gpt-5.1-codex-mini",
            description = "针对 Codex 优化。更便宜、更快，但性能较弱",
        ),
    )

    fun ids(): List<String> = options.map { it.id }

    fun option(modelId: String?): CodexModelOption? {
        val normalized = modelId?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return options.firstOrNull { it.id == normalized }
    }
}
