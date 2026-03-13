package com.codex.assistant.toolwindow.timeline

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

object MarkdownRenderer {
    private val parser: Parser = Parser.builder().build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder()
        .escapeHtml(true)
        .percentEncodeUrls(true)
        .build()

    fun renderToHtml(markdown: String): String {
        if (markdown.isBlank()) return ""
        val document = parser.parse(markdown)
        return renderer.render(document).trim()
    }
}
