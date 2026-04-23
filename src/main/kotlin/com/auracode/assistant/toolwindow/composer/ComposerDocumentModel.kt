package com.auracode.assistant.toolwindow.composer

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText

internal data class MentionQueryMatch(
    val query: String,
    val start: Int,
    val end: Int,
)

internal data class AgentQueryMatch(
    val query: String,
    val start: Int,
    val end: Int,
)

internal fun mentionLabel(displayName: String): String = "@$displayName"

internal fun findMentionQuery(
    value: TextFieldValue,
    mentions: List<MentionEntry>,
): MentionQueryMatch? {
    if (value.composition != null) return null
    if (!value.selection.collapsed) return null
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    if (mentions.any { cursor in it.start until it.endExclusive }) return null

    val text = value.text
    var scan = cursor
    while (scan > 0) {
        val previous = text[scan - 1]
        if (previous.isWhitespace()) break
        if (previous == '@') {
            val start = scan - 1
            if (mentions.any { start in it.start until it.endExclusive }) return null
            val queryText = text.substring(start + 1, cursor)
            if (queryText.any { !it.isLetterOrDigit() && it != '.' && it != '_' && it != '-' && it != '/' }) {
                return null
            }
            return MentionQueryMatch(query = queryText, start = start, end = cursor)
        }
        scan--
    }
    return null
}

internal fun findAgentQuery(
    value: TextFieldValue,
    mentions: List<MentionEntry>,
): AgentQueryMatch? {
    if (value.composition != null) return null
    if (!value.selection.collapsed) return null
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    if (mentions.any { cursor in it.start until it.endExclusive }) return null

    val text = value.text
    var scan = cursor
    while (scan > 0) {
        val previous = text[scan - 1]
        if (previous.isWhitespace()) break
        if (previous == '#') {
            val start = scan - 1
            if (mentions.any { start in it.start until it.endExclusive }) return null
            val queryText = text.substring(start + 1, cursor)
            if (queryText.any { !it.isLetterOrDigit() && it != '.' && it != '_' && it != '-' }) {
                return null
            }
            return AgentQueryMatch(query = queryText, start = start, end = cursor)
        }
        scan--
    }
    return null
}

internal fun insertMentionLabel(
    document: TextFieldValue,
    mentions: List<MentionEntry>,
    mentionPath: String,
    displayName: String,
    kind: MentionEntryKind = MentionEntryKind.FILE,
): Pair<TextFieldValue, MentionEntry>? {
    val match = findMentionQuery(document, mentions) ?: return null
    val label = mentionLabel(displayName)
    val nextText = buildString {
        append(document.text.substring(0, match.start))
        append(label)
        append(document.text.substring(match.end))
    }
    val nextMention = MentionEntry(
        id = java.util.UUID.randomUUID().toString(),
        kind = kind,
        path = mentionPath,
        displayName = displayName,
        start = match.start,
        endExclusive = match.start + label.length,
    )
    val nextSelection = TextRange(nextMention.endExclusive)
    return TextFieldValue(nextText, nextSelection) to nextMention
}

/**
 * Inserts a mention at the current cursor or selection, even when no active @ query is open.
 */
internal fun insertMentionAtCursor(
    document: TextFieldValue,
    mentions: List<MentionEntry>,
    mentionPath: String,
    displayName: String,
    kind: MentionEntryKind = MentionEntryKind.FILE,
): Pair<TextFieldValue, MentionEntry> {
    val label = mentionLabel(displayName)
    val selectionStart = document.selection.min.coerceIn(0, document.text.length)
    val selectionEnd = document.selection.max.coerceIn(0, document.text.length)
    val prefix = if (selectionStart > 0 && !document.text[selectionStart - 1].isWhitespace()) " " else ""
    val suffix = if (selectionEnd < document.text.length && !document.text[selectionEnd].isWhitespace()) " " else ""
    val insertion = "$prefix$label$suffix"
    val nextText = buildString {
        append(document.text.substring(0, selectionStart))
        append(insertion)
        append(document.text.substring(selectionEnd))
    }
    val mentionStart = selectionStart + prefix.length
    val nextMention = MentionEntry(
        id = java.util.UUID.randomUUID().toString(),
        kind = kind,
        path = mentionPath,
        displayName = displayName,
        start = mentionStart,
        endExclusive = mentionStart + label.length,
    )
    return TextFieldValue(nextText, TextRange(mentionStart + insertion.length)) to nextMention
}

internal fun syncMentions(
    previousMentions: List<MentionEntry>,
    oldText: String,
    newValue: TextFieldValue,
): List<MentionEntry> {
    if (previousMentions.isEmpty()) return emptyList()
    val newText = newValue.text
    if (oldText == newText) return previousMentions

    var prefix = 0
    val minLength = minOf(oldText.length, newText.length)
    while (prefix < minLength && oldText[prefix] == newText[prefix]) {
        prefix++
    }

    var suffix = 0
    while (
        suffix < oldText.length - prefix &&
        suffix < newText.length - prefix &&
        oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
    ) {
        suffix++
    }

    val oldChangedEnd = oldText.length - suffix
    val newChangedEnd = newText.length - suffix
    val delta = newChangedEnd - oldChangedEnd

    return previousMentions.mapNotNull { mention ->
        when {
            mention.endExclusive <= prefix -> mention
            mention.start >= oldChangedEnd -> mention.copy(
                start = mention.start + delta,
                endExclusive = mention.endExclusive + delta,
            )
            else -> null
        }
    }.filter { mention ->
        mention.start >= 0 &&
            mention.endExclusive <= newText.length &&
            newText.substring(mention.start, mention.endExclusive) == mentionLabel(mention.displayName)
    }
}

internal fun removeMentionRanges(
    text: String,
    mentions: List<MentionEntry>,
    includedKinds: Set<MentionEntryKind> = MentionEntryKind.entries.toSet(),
): String {
    if (mentions.isEmpty()) return text
    val builder = StringBuilder()
    var cursor = 0
    mentions
        .filter { it.kind in includedKinds }
        .sortedBy { it.start }
        .forEach { mention ->
        if (mention.start > cursor) {
            builder.append(text.substring(cursor, mention.start))
        }
        cursor = mention.endExclusive.coerceAtLeast(cursor)
        }
    if (cursor < text.length) {
        builder.append(text.substring(cursor))
    }
    return builder.toString()
}

internal fun normalizePromptBody(text: String): String {
    return text
        .lines()
        .joinToString("\n") { line -> line.replace(Regex("""[ \t]{2,}"""), " ").trimEnd() }
        .trim()
}

internal fun removeMentionById(
    document: TextFieldValue,
    mentions: List<MentionEntry>,
    mentionId: String,
): Pair<TextFieldValue, List<MentionEntry>>? {
    val target = mentions.firstOrNull { it.id == mentionId } ?: return null
    val nextText = buildString {
        append(document.text.substring(0, target.start))
        append(document.text.substring(target.endExclusive))
    }
    val nextSelection = TextRange(document.selection.start.coerceAtMost(nextText.length))
    val remaining = mentions.filterNot { it.id == mentionId }.map { mention ->
        if (mention.start > target.start) {
            val shift = target.endExclusive - target.start
            mention.copy(
                start = mention.start - shift,
                endExclusive = mention.endExclusive - shift,
            )
        } else {
            mention
        }
    }
    return TextFieldValue(nextText, nextSelection) to remaining
}

internal fun normalizeMentionSelection(
    previous: TextFieldValue,
    updated: TextFieldValue,
    mentions: List<MentionEntry>,
): TextFieldValue {
    if (mentions.isEmpty() || updated.composition != null) return updated
    val selection = updated.selection

    if (selection.collapsed) {
        val mention = mentions.firstOrNull { selection.start in (it.start + 1) until it.endExclusive } ?: return updated
        val previousCursor = previous.selection.start
        val updatedCursor = selection.start
        val snapped = when {
            previous.text == updated.text &&
                previous.selection.collapsed &&
                previousCursor == mention.endExclusive &&
                updatedCursor < previousCursor -> mention.start
            previous.text == updated.text &&
                previous.selection.collapsed &&
                previousCursor == mention.start &&
                updatedCursor > previousCursor -> mention.endExclusive
            updatedCursor - mention.start <= mention.endExclusive - updatedCursor -> mention.start
            else -> mention.endExclusive
        }
        return updated.copy(selection = TextRange(snapped))
    }

    var min = selection.min
    var max = selection.max
    var changed = true
    while (changed) {
        changed = false
        mentions.forEach { mention ->
            if (min < mention.endExclusive && max > mention.start) {
                val nextMin = minOf(min, mention.start)
                val nextMax = maxOf(max, mention.endExclusive)
                if (nextMin != min || nextMax != max) {
                    min = nextMin
                    max = nextMax
                    changed = true
                }
            }
        }
    }
    if (min == selection.min && max == selection.max) return updated
    val normalized = if (selection.start <= selection.end) {
        TextRange(min, max)
    } else {
        TextRange(max, min)
    }
    return updated.copy(selection = normalized)
}

internal fun moveCursorLeftAcrossMention(
    document: TextFieldValue,
    mentions: List<MentionEntry>,
): TextFieldValue? {
    if (!document.selection.collapsed || document.composition != null) return null
    val cursor = document.selection.start
    val mention = mentions.firstOrNull { cursor == it.endExclusive || cursor in (it.start + 1) until it.endExclusive } ?: return null
    return document.copy(selection = TextRange(mention.start))
}

internal fun moveCursorRightAcrossMention(
    document: TextFieldValue,
    mentions: List<MentionEntry>,
): TextFieldValue? {
    if (!document.selection.collapsed || document.composition != null) return null
    val cursor = document.selection.start
    val mention = mentions.firstOrNull { cursor == it.start || cursor in it.start until it.endExclusive - 1 } ?: return null
    return document.copy(selection = TextRange(mention.endExclusive))
}

internal fun removeMentionByBackspace(
    document: TextFieldValue,
    mentions: List<MentionEntry>,
): Pair<TextFieldValue, List<MentionEntry>>? {
    if (!document.selection.collapsed || document.composition != null) return null
    val cursor = document.selection.start
    val mention = mentions.firstOrNull { cursor == it.endExclusive || cursor in (it.start + 1) until it.endExclusive } ?: return null
    return removeTextRange(document, mentions, mention.start, mention.endExclusive)
}

internal fun removeMentionByDelete(
    document: TextFieldValue,
    mentions: List<MentionEntry>,
): Pair<TextFieldValue, List<MentionEntry>>? {
    if (!document.selection.collapsed || document.composition != null) return null
    val cursor = document.selection.start
    val mention = mentions.firstOrNull { cursor == it.start || cursor in it.start until it.endExclusive - 1 } ?: return null
    return removeTextRange(document, mentions, mention.start, mention.endExclusive)
}

internal fun removeMentionSelection(
    document: TextFieldValue,
    mentions: List<MentionEntry>,
): Pair<TextFieldValue, List<MentionEntry>>? {
    if (document.selection.collapsed || document.composition != null) return null
    val normalized = normalizeMentionSelection(document, document, mentions)
    val start = normalized.selection.min
    val end = normalized.selection.max
    val overlapsMention = mentions.any { start < it.endExclusive && end > it.start }
    if (!overlapsMention) return null
    return removeTextRange(normalized, mentions, start, end)
}

private fun removeTextRange(
    document: TextFieldValue,
    mentions: List<MentionEntry>,
    start: Int,
    endExclusive: Int,
): Pair<TextFieldValue, List<MentionEntry>>? {
    if (start >= endExclusive) return null
    val nextText = buildString {
        append(document.text.substring(0, start))
        append(document.text.substring(endExclusive))
    }
    val shift = endExclusive - start
    val nextSelection = TextRange(start)
    val remaining = mentions.mapNotNull { mention ->
        when {
            mention.endExclusive <= start -> mention
            mention.start >= endExclusive -> mention.copy(
                start = mention.start - shift,
                endExclusive = mention.endExclusive - shift,
            )
            else -> null
        }
    }
    return document.copy(text = nextText, selection = nextSelection, composition = null) to remaining
}

internal data class MentionTransformSpan(
    val start: Int,
    val endExclusive: Int,
)

internal fun buildMentionTransformedText(
    text: String,
    spans: List<MentionTransformSpan>,
    mentionStyle: (AnnotatedString.Builder, Int, Int) -> Unit,
): TransformedText {
    val builder = AnnotatedString.Builder(text)
    spans.forEach { span ->
        if (span.start in 0..builder.length && span.endExclusive in 0..builder.length && span.start < span.endExclusive) {
            mentionStyle(builder, span.start, span.endExclusive)
        }
    }
    return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
}
