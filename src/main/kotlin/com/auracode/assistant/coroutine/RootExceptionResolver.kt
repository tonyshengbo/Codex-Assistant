package com.auracode.assistant.coroutine

/**
 * Resolves the deepest non-null cause so coroutine failures can report a stable root exception.
 */
internal object RootExceptionResolver {
    fun resolve(error: Throwable): Throwable {
        val visited = mutableSetOf<Throwable>()
        var current = error
        while (true) {
            val next = current.cause ?: return current
            if (!visited.add(current) || visited.contains(next)) {
                return current
            }
            current = next
        }
    }
}
