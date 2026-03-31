package com.auracode.assistant.notification

import com.intellij.notification.NotificationGroupManager

/**
 * Resolves IntelliJ notification groups used by Aura Code reminder flows.
 */
internal object AuraNotificationGroup {
    private const val CHAT_COMPLETION_GROUP_ID = "Aura Code Chat Completion"

    /**
     * Returns the notification group dedicated to background chat completion reminders.
     */
    fun chatCompletion() = NotificationGroupManager.getInstance().getNotificationGroup(CHAT_COMPLETION_GROUP_ID)
}
