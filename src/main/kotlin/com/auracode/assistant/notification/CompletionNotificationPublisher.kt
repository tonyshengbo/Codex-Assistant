package com.auracode.assistant.notification

/**
 * Abstracts notification delivery so reminder policy can be tested without IDE UI dependencies.
 */
internal fun interface CompletionNotificationPublisher {
    /**
     * Publishes a completion reminder through the configured output channel.
     */
    fun publish(signal: ChatCompletionSignal)
}
