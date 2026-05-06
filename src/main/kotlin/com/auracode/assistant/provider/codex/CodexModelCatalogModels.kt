package com.auracode.assistant.provider.codex

/** 描述从 CLI 获取的单个 Codex 模型条目。 */
internal data class CodexModelEntry(
    val slug: String,
    val displayName: String,
)

/** 表示一次模型目录刷新的结果。 */
internal sealed class CodexModelCatalogRefreshResult {
    /** 模型列表有变化，包含新列表。 */
    data class Changed(val models: List<CodexModelEntry>) : CodexModelCatalogRefreshResult()
    /** 模型列表与缓存一致，无需更新。 */
    object Unchanged : CodexModelCatalogRefreshResult()
    /** CLI 不可用或解析失败，静默忽略。 */
    object Failed : CodexModelCatalogRefreshResult()
}
