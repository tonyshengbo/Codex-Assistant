package com.auracode.assistant.toolwindow.external

import com.auracode.assistant.integration.build.BuildErrorAuraRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import java.util.ArrayDeque

@Service(Service.Level.PROJECT)
class ToolWindowExternalRequestBridge {
    private val pendingBuildErrorRequests = ArrayDeque<BuildErrorAuraRequest>()
    private var buildErrorHandler: ((BuildErrorAuraRequest) -> Unit)? = null

    /**
     * Queues or dispatches build-error requests coming from IDE actions outside the tool window.
     */
    @Synchronized
    fun submitBuildErrorRequest(request: BuildErrorAuraRequest) {
        val handler = buildErrorHandler
        if (handler != null) {
            handler(request)
        } else {
            pendingBuildErrorRequests.addLast(request)
        }
    }

    /**
     * Registers the active tool-window handler and drains any queued requests in arrival order.
     */
    @Synchronized
    fun registerBuildErrorHandler(handler: (BuildErrorAuraRequest) -> Unit): Disposable {
        buildErrorHandler = handler
        while (pendingBuildErrorRequests.isNotEmpty()) {
            handler(pendingBuildErrorRequests.removeFirst())
        }
        return Disposable {
            synchronized(this) {
                if (buildErrorHandler === handler) {
                    buildErrorHandler = null
                }
            }
        }
    }
}
