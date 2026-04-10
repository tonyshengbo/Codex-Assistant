package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.coroutine.ManagedCoroutineScope
import com.intellij.openapi.diagnostic.Logger

internal class CoordinatorCoroutineLauncher(
    private val scope: ManagedCoroutineScope,
    private val logger: Logger,
    private val onMcpCancellation: (String) -> Unit,
    private val onMcpFailure: (String, Throwable) -> Unit,
) {
    fun launch(label: String, block: suspend () -> Unit) {
        scope.launch(
            label = label,
            onFailure = { error ->
                if (label.isMcpLabel()) {
                    onMcpFailure(label, error)
                } else {
                    logger.error("ToolWindowCoordinator coroutine failed: $label", error)
                }
            },
            onCancellation = {
                if (label.isMcpLabel()) {
                    onMcpCancellation(label)
                }
            },
            block = block,
        )
    }

    private fun String.isMcpLabel(): Boolean {
        return startsWith("loadMcp") ||
            startsWith("refreshMcp") ||
            startsWith("saveMcp") ||
            startsWith("runMcp") ||
            startsWith("authenticateMcp") ||
            startsWith("deleteMcp")
    }
}
