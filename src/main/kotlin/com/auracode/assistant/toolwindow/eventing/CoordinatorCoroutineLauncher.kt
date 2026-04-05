package com.auracode.assistant.toolwindow.eventing

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class CoordinatorCoroutineLauncher(
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val onMcpCancellation: (String) -> Unit,
    private val onMcpFailure: (String, Throwable) -> Unit,
) {
    fun launch(label: String, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { error ->
                if (error is CancellationException) {
                    if (label.isMcpLabel()) {
                        onMcpCancellation(label)
                    }
                    return@onFailure
                }
                if (label.isMcpLabel()) {
                    onMcpFailure(label, error)
                } else {
                    logger.error("ToolWindowCoordinator coroutine failed: $label", error)
                }
            }
        }
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
