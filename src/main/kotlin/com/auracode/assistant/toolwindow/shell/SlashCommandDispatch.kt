package com.auracode.assistant.toolwindow.shell

import com.auracode.assistant.toolwindow.submission.normalizeSlashCommand

/**
 * Keeps slash-command side effects explicit so the panel can stay thin and the
 * routing policy can be verified without constructing UI objects.
 */
internal enum class SlashCommandDispatch {
    PUBLISH_ONLY,
    START_NEW_SESSION,
    START_NEW_TAB,
}

internal fun resolveSlashCommandDispatch(command: String): SlashCommandDispatch {
    return when (normalizeSlashCommand(command)) {
        "new" -> SlashCommandDispatch.START_NEW_SESSION
        "tab" -> SlashCommandDispatch.START_NEW_TAB
        else -> SlashCommandDispatch.PUBLISH_ONLY
    }
}
