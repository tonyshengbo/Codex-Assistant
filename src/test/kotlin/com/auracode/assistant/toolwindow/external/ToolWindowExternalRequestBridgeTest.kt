package com.auracode.assistant.toolwindow.external

import com.auracode.assistant.integration.ide.IdeExternalRequest
import com.auracode.assistant.integration.ide.IdeRequestSource
import com.auracode.assistant.model.ContextFile
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolWindowExternalRequestBridgeTest {
    @Test
    fun `queued requests drain in submission order after handler registration`() {
        val bridge = ToolWindowExternalRequestBridge()
        val delivered = mutableListOf<IdeExternalRequest>()
        val first = IdeExternalRequest(
            source = IdeRequestSource.EDITOR_SELECTION,
            title = "Explain Selected Code",
            prompt = "Explain selection",
            contextFiles = listOf(ContextFile(path = "/src/Main.kt:3-5", content = "println()")),
        )
        val second = IdeExternalRequest(
            source = IdeRequestSource.CURRENT_FILE,
            title = "Explain Current File",
            prompt = "Explain file",
            contextFiles = listOf(ContextFile(path = "/src/Main.kt")),
        )

        bridge.submitRequest(first)
        bridge.submitRequest(second)

        bridge.registerHandler { delivered += it }

        assertEquals(listOf(first, second), delivered)
    }
}
