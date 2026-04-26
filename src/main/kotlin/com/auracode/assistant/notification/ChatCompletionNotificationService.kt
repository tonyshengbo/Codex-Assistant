package com.auracode.assistant.notification

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.sessions.SessionAttentionStore
import com.intellij.notification.NotificationType

/**
 * Decides whether a completed chat turn should trigger an unread marker and an IDE reminder.
 */
internal class ChatCompletionNotificationService(
    private val settingsService: AgentSettingsService,
    private val attentionStateProvider: IdeAttentionStateProvider,
    private val attentionStore: SessionAttentionStore,
    private val publisher: CompletionNotificationPublisher = IdeCompletionNotificationPublisher(),
) {
    // Publishes a reminder only when the completed session is no longer being actively watched.
    /**
     * Emits reminder side effects for background completions while respecting user settings and focus state.
     */
    fun notifyIfNeeded(
        sessionId: String,
        sessionTitle: String,
        outcome: TurnOutcome,
        completedAt: Long = System.currentTimeMillis(),
    ) {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank()) return
        if (!settingsService.backgroundCompletionNotificationsEnabled()) return
        if (outcome == TurnOutcome.CANCELLED) return
        if (attentionStateProvider.currentState().isWatchingSession(normalizedSessionId)) return

        attentionStore.markCompleted(
            sessionId = normalizedSessionId,
            outcome = outcome,
            completedAt = completedAt,
        )
        publisher.publish(
            ChatCompletionSignal(
                sessionId = normalizedSessionId,
                sessionTitle = sessionTitle.trim().ifBlank {
                    AuraCodeBundle.message("toolwindow.finished.default")
                },
                outcome = outcome,
            ),
        )
    }
}

/**
 * Delivers completion reminders through the IntelliJ balloon notification system.
 */
internal class IdeCompletionNotificationPublisher : CompletionNotificationPublisher {
    /**
     * Builds and publishes a localized notification from the normalized completion signal.
     */
    override fun publish(signal: ChatCompletionSignal) {
        // The first implementation keeps the notification payload simple and stable for regression safety.
        val content = AuraCodeBundle.message(
            "notification.chatCompletion.content",
            signal.sessionTitle,
            signal.outcome.name.lowercase(),
        )
        AuraNotificationGroup.chatCompletion()
            .createNotification(
                AuraCodeBundle.message("notification.chatCompletion.title"),
                content,
                NotificationType.INFORMATION,
            )
            .notify(null)
    }
}
