package com.auracode.assistant.notification

import com.intellij.notification.NotificationGroupManager

/**
 * Resolves IntelliJ notification groups used by Aura Code reminder flows.
 */
internal object AuraNotificationGroup {
    private const val CHAT_COMPLETION_GROUP_ID = "Aura Code Chat Completion"
    private const val CODEX_CLI_VERSION_GROUP_ID = "Aura Code Codex CLI Version"

    /**
     * Returns the notification group dedicated to background chat completion reminders.
     */
    fun chatCompletion() = NotificationGroupManager.getInstance().getNotificationGroup(CHAT_COMPLETION_GROUP_ID)

    /** Returns the notification group dedicated to Codex CLI version update reminders. */
    fun codexCliVersion() = NotificationGroupManager.getInstance().getNotificationGroup(CODEX_CLI_VERSION_GROUP_ID)
}
