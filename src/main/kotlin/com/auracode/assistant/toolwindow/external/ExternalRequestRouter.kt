package com.auracode.assistant.toolwindow.external

import com.auracode.assistant.integration.build.BuildErrorAuraRequest
import com.auracode.assistant.integration.ide.IdeExternalRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import java.util.ArrayDeque

@Service(Service.Level.PROJECT)
class ExternalRequestRouter {
    private val pendingRequests = ArrayDeque<IdeExternalRequest>()
    private var requestHandler: ((IdeExternalRequest) -> Unit)? = null

    /**
     * Queues or dispatches normalized IDE requests coming from entrypoints outside the tool window.
     */
    @Synchronized
    fun submitRequest(request: IdeExternalRequest) {
        val handler = requestHandler
        if (handler != null) {
            handler(request)
        } else {
            pendingRequests.addLast(request)
        }
    }

    /**
     * Preserves the original build-error entrypoint while routing through the generic request channel.
     */
    @Synchronized
    fun submitBuildErrorRequest(request: BuildErrorAuraRequest) {
        submitRequest(request.toIdeExternalRequest())
    }

    /**
     * Registers the active tool-window handler and drains any queued requests in arrival order.
     */
    @Synchronized
    fun registerHandler(handler: (IdeExternalRequest) -> Unit): Disposable {
        requestHandler = handler
        while (pendingRequests.isNotEmpty()) {
            handler(pendingRequests.removeFirst())
        }
        return Disposable {
            synchronized(this) {
                if (requestHandler === handler) {
                    requestHandler = null
                }
            }
        }
    }
}
