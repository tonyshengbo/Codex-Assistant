package com.auracode.assistant.coroutine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppCoroutineManagerTest {
    @Test
    fun `root exception resolver returns deepest cause`() {
        val root = IllegalArgumentException("root")
        val wrapped = IllegalStateException("outer", RuntimeException("middle", root))

        val resolved = RootExceptionResolver.resolve(wrapped)

        assertIs<IllegalArgumentException>(resolved)
        assertEquals("root", resolved.message)
    }

    @Test
    fun `managed coroutine scope reports root exception to launch failure callback`() = runBlocking {
        val failures = mutableListOf<Throwable>()
        val scope = AppCoroutineManager.createScope(
            scopeName = "test-scope",
            failureReporter = { _: String, _: String?, _: Throwable -> Unit },
        )

        val job = scope.launch(
            label = "explode",
            onFailure = { error: Throwable -> failures += error },
        ) {
            throw IllegalStateException("outer", IllegalArgumentException("root"))
        }

        job.join()

        assertEquals(1, failures.size)
        val failure = assertIs<IllegalArgumentException>(failures.single())
        assertEquals("root", failure.message)
        scope.cancel()
    }

    @Test
    fun `managed coroutine scope notifies launch cancellation callback`() = runBlocking {
        val cancelled = CompletableDeferred<String>()
        val scope = AppCoroutineManager.createScope(
            scopeName = "test-scope",
            failureReporter = { _: String, _: String?, _: Throwable -> Unit },
        )

        val job = scope.launch(
            label = "await-cancel",
            onCancellation = { label: String -> cancelled.complete(label) },
        ) {
            awaitCancellation()
        }

        job.cancelAndJoin()

        assertEquals("await-cancel", cancelled.await())
        scope.cancel()
    }
}
