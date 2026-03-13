package com.codex.assistant.toolwindow.timeline

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarkdownRendererTest {
    @Test
    fun `renders commonmark content to html`() {
        val html = renderMarkdown(
            """
            # Result

            Use `rg` to search.

            - first
            - second

            ```bash
            ./gradlew test
            ```
            """.trimIndent(),
        )

        assertTrue(html.contains("<h1"), "heading should render to h1")
        assertTrue(html.contains("<code>rg</code>"), "inline code should render")
        assertTrue(html.contains("<ul>"), "list should render")
        assertTrue(html.contains("<pre><code"), "code fence should render")
    }

    @Test
    fun `escapes raw html when rendering markdown`() {
        val html = renderMarkdown("before <script>alert('x')</script> after")

        assertTrue(!html.contains("<script>"), "raw script tags should be escaped")
        assertTrue(html.contains("&lt;script&gt;") || html.contains("&amp;lt;script&amp;gt;"))
    }

    private fun renderMarkdown(markdown: String): String {
        val rendererClass = assertNotNull(
            runCatching { Class.forName("com.codex.assistant.toolwindow.timeline.MarkdownRenderer") }.getOrNull(),
            "MarkdownRenderer should be present",
        )
        val instance = rendererClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val method = assertNotNull(
            rendererClass.methods.firstOrNull { it.name == "renderToHtml" && it.parameterCount == 1 },
            "MarkdownRenderer.renderToHtml(String) should be present",
        )
        return method.invoke(instance, markdown) as String
    }
}
