package com.auracode.assistant.toolwindow.submission

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.SubmissionMode

internal data class SlashQueryMatch(
    val query: String,
    val start: Int,
    val end: Int,
)

internal data class SlashSkillDescriptor(
    val name: String,
    val description: String,
)

internal sealed interface SlashSuggestionItem {
    val title: String
    val description: String

    data class Command(
        val command: String,
        override val title: String,
        override val description: String,
        val enabled: Boolean = true,
    ) : SlashSuggestionItem

    data class Skill(
        val name: String,
        override val description: String,
    ) : SlashSuggestionItem {
        override val title: String = "\$$name"
    }
}

/**
 * Centralizes slash command metadata so the store can stay focused on state transitions
 * while command titles, enablement, and descriptions remain easy to evolve.
 */
internal enum class SubmissionSlashCommand(
    val token: String,
) {
    PLAN("/plan"),
    AUTO("/auto"),
    NEW("/new"),
    TAB("/tab"),
}

internal fun normalizeSlashCommand(command: String): String = command.trim().removePrefix("/")

internal fun buildSlashCommandSuggestions(
    query: String,
    state: SubmissionAreaState,
): List<SlashSuggestionItem.Command> {
    val normalizedQuery = query.trim()
    return SubmissionSlashCommand.entries
        .map { command -> command.toSuggestion(state) }
        .filter { suggestion ->
            normalizedQuery.isBlank() || suggestion.command.removePrefix("/").contains(normalizedQuery, ignoreCase = true)
        }
}

private fun SubmissionSlashCommand.toSuggestion(state: SubmissionAreaState): SlashSuggestionItem.Command {
    return when (this) {
        SubmissionSlashCommand.PLAN -> SlashSuggestionItem.Command(
            command = token,
            title = token,
            description = if (state.planEnabled) {
                AuraCodeBundle.message("composer.slash.plan.disable")
            } else {
                AuraCodeBundle.message("composer.slash.plan.enable")
            },
            enabled = state.planModeAvailable,
        )
        SubmissionSlashCommand.AUTO -> SlashSuggestionItem.Command(
            command = token,
            title = token,
            description = if (state.executionMode == SubmissionMode.AUTO) {
                AuraCodeBundle.message("composer.slash.auto.disable")
            } else {
                AuraCodeBundle.message("composer.slash.auto.enable")
            },
        )
        SubmissionSlashCommand.NEW -> SlashSuggestionItem.Command(
            command = token,
            title = token,
            description = AuraCodeBundle.message("composer.slash.new.description"),
        )
        SubmissionSlashCommand.TAB -> SlashSuggestionItem.Command(
            command = token,
            title = token,
            description = AuraCodeBundle.message("composer.slash.tab.description"),
        )
    }
}

internal fun findSlashQuery(
    value: TextFieldValue,
    mentions: List<MentionEntry>,
): SlashQueryMatch? {
    if (value.composition != null) return null
    if (!value.selection.collapsed) return null
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    if (mentions.any { cursor in it.start until it.endExclusive }) return null

    val text = value.text
    if (cursor <= 0) return null

    var tokenStart = cursor - 1
    while (tokenStart > 0 && !text[tokenStart - 1].isWhitespace()) {
        tokenStart--
    }

    if (text.getOrNull(tokenStart) != '/') return null
    if (tokenStart > 0 && !text[tokenStart - 1].isWhitespace()) return null
    if (mentions.any { tokenStart in it.start until it.endExclusive }) return null

    var tokenEndExclusive = tokenStart + 1
    while (tokenEndExclusive < text.length && !text[tokenEndExclusive].isWhitespace()) {
        tokenEndExclusive++
    }
    if (cursor > tokenEndExclusive) return null

    val rawQuery = text.substring(tokenStart + 1, cursor)
    if (rawQuery.any { !it.isLetterOrDigit() && it != '.' && it != '_' && it != '-' }) {
        return null
    }
    return SlashQueryMatch(
        query = rawQuery,
        start = tokenStart,
        end = tokenEndExclusive,
    )
}

internal fun slashSkillToken(name: String): String = "\$$name "

internal fun replaceSlashQuery(
    document: TextFieldValue,
    mentions: List<MentionEntry>,
    replacement: String,
): TextFieldValue? {
    val match = findSlashQuery(document, mentions) ?: return null
    val nextText = buildString {
        append(document.text.substring(0, match.start))
        append(replacement)
        append(document.text.substring(match.end))
    }
    val nextSelection = match.start + replacement.length
    return document.copy(
        text = nextText,
        selection = TextRange(nextSelection),
    )
}

internal fun discoverAvailableSkills(@Suppress("UNUSED_PARAMETER") engineId: String): List<SlashSkillDescriptor> = emptyList()
