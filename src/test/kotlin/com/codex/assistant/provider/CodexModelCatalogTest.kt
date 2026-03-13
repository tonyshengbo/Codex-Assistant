package com.codex.assistant.provider

import kotlin.test.Test
import kotlin.test.assertEquals

class CodexModelCatalogTest {
    @Test
    fun `catalog exposes default model and menu descriptions in display order`() {
        assertEquals("gpt-5.3-codex", CodexModelCatalog.defaultModel)
        assertEquals(
            listOf(
                "gpt-5.3-codex",
                "gpt-5.4",
                "gpt-5.2-codex",
                "gpt-5.1-codex-max",
                "gpt-5.1-codex-mini",
            ),
            CodexModelCatalog.options.map { it.id },
        )
        assertEquals("最新前沿智能模型，能力全面增强", CodexModelCatalog.option("gpt-5.3-codex")?.description)
        assertEquals("针对 Codex 优化。更便宜、更快，但性能较弱", CodexModelCatalog.option("gpt-5.1-codex-mini")?.description)
    }
}
