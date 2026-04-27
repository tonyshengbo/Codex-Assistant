package com.auracode.assistant.toolwindow.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationMarkdownTest {
    @Test
    fun `timeline markdown body line height uses relaxed reading rhythm`() {
        assertEquals(21f, conversationMarkdownBodyLineHeightSp(14f))
    }

    @Test
    fun `http and https links are considered safe`() {
        assertTrue(isSafeHttpUrl("https://example.com/a?b=1"))
        assertTrue(isSafeHttpUrl("http://localhost:8080/path"))
    }

    @Test
    fun `non-http links are rejected`() {
        assertFalse(isSafeHttpUrl("javascript:alert(1)"))
        assertFalse(isSafeHttpUrl("file:///tmp/a.txt"))
        assertFalse(isSafeHttpUrl("mailto:test@example.com"))
        assertFalse(isSafeHttpUrl("not a url"))
    }

    @Test
    fun `absolute file markdown links resolve to openable timeline paths`() {
        assertEquals(
            "/Users/tonysheng/StudioProject/Aura/src/main/kotlin/Main.kt",
            resolveConversationMarkdownFilePath("/Users/tonysheng/StudioProject/Aura/src/main/kotlin/Main.kt"),
        )
        assertEquals(
            "/Users/tonysheng/StudioProject/Aura/src/main/kotlin/Main.kt",
            resolveConversationMarkdownFilePath("/Users/tonysheng/StudioProject/Aura/src/main/kotlin/Main.kt#L42"),
        )
        assertEquals(
            "/Users/tonysheng/StudioProject/Aura/src/main/kotlin/Main.kt",
            resolveConversationMarkdownFilePath("file:///Users/tonysheng/StudioProject/Aura/src/main/kotlin/Main.kt"),
        )
    }

    @Test
    fun `web and unsupported markdown links do not resolve as timeline file paths`() {
        assertEquals(null, resolveConversationMarkdownFilePath("https://example.com/readme"))
        assertEquals(null, resolveConversationMarkdownFilePath("mailto:test@example.com"))
        assertEquals(null, resolveConversationMarkdownFilePath("not a path"))
    }
}
