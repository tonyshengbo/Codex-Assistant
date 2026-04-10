package com.auracode.assistant.coroutine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Creates managed coroutine scopes so background work follows the same launch and exception rules.
 */
internal object AppCoroutineManager {
    fun createScope(
        scopeName: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        failureReporter: (scopeName: String, label: String?, error: Throwable) -> Unit,
    ): ManagedCoroutineScope {
        return ManagedCoroutineScope(
            scopeName = scopeName,
            dispatcher = dispatcher,
            failureReporter = failureReporter,
        )
    }
}

/**
 * Wraps a coroutine scope with centralized root exception handling and controlled cancellation.
 */
internal class ManagedCoroutineScope(
    private val scopeName: String,
    dispatcher: CoroutineDispatcher,
    private val failureReporter: (scopeName: String, label: String?, error: Throwable) -> Unit,
) {
    private val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        val root = RootExceptionResolver.resolve(throwable)
        failureReporter(scopeName, context[CoroutineName]?.name, root)
    }

    val coroutineScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + dispatcher + exceptionHandler + CoroutineName(scopeName),
    )

    /**
     * Launches a managed coroutine and routes failures through root exception resolution first.
     */
    fun launch(
        label: String,
        onFailure: ((Throwable) -> Unit)? = null,
        onCancellation: ((String) -> Unit)? = null,
        block: suspend () -> Unit,
    ): Job {
        return coroutineScope.launch(CoroutineName(label)) {
            try {
                block()
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    onCancellation?.invoke(label)
                    throw error
                }
                val root = RootExceptionResolver.resolve(error)
                onFailure?.invoke(root)
                throw root
            }
        }
    }

    /**
     * Registers a completion callback on the managed scope root job.
     */
    fun invokeOnCompletion(handler: CompletionHandler) {
        coroutineScope.coroutineContext[Job]?.invokeOnCompletion(handler)
    }

    /**
     * Cancels all work launched from this managed scope.
     */
    fun cancel() {
        coroutineScope.cancel()
    }
}
