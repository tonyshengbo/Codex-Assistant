package com.auracode.assistant.toolwindow.conversation

/**
 * Builds the stable identifier used by turn-scoped activity nodes.
 */
internal fun timelineActivityNodeId(
    prefix: String,
    turnId: String?,
    sourceId: String,
): String = listOfNotNull(prefix, turnId?.takeIf { it.isNotBlank() }, sourceId).joinToString(":")
