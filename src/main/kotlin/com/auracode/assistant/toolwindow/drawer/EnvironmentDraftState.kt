package com.auracode.assistant.toolwindow.drawer

/**
 * Stores the persisted environment paths alongside the editable draft values
 * so auto-detection can avoid overwriting unsaved manual edits.
 */
internal data class EnvironmentDraftState(
    val codexCliPath: String = "",
    val nodePath: String = "",
    val savedCodexCliPath: String = "",
    val savedNodePath: String = "",
    val codexPathManuallyEdited: Boolean = false,
    val nodePathManuallyEdited: Boolean = false,
) {
    /** Returns true when the visible environment draft differs from persisted settings. */
    val isDirty: Boolean
        get() = codexCliPath.trim() != savedCodexCliPath.trim() || nodePath.trim() != savedNodePath.trim()

    /** Applies a user edit to the Codex executable path and marks the draft as manual when needed. */
    fun withEditedCodexPath(value: String): EnvironmentDraftState {
        return copy(
            codexCliPath = value,
            codexPathManuallyEdited = value.trim() != savedCodexCliPath.trim(),
        )
    }

    /** Applies a user edit to the Node executable path and marks the draft as manual when needed. */
    fun withEditedNodePath(value: String): EnvironmentDraftState {
        return copy(
            nodePath = value,
            nodePathManuallyEdited = value.trim() != savedNodePath.trim(),
        )
    }

    /**
     * Refreshes the draft from persisted settings while preserving unsaved manual edits
     * that still differ from the previously saved values.
     */
    fun withPersistedPaths(
        codexCliPath: String,
        nodePath: String,
    ): EnvironmentDraftState {
        val nextSavedCodex = codexCliPath.trim()
        val nextSavedNode = nodePath.trim()
        val keepManualCodex = codexPathManuallyEdited && this.codexCliPath.trim() != savedCodexCliPath.trim()
        val keepManualNode = nodePathManuallyEdited && this.nodePath.trim() != savedNodePath.trim()
        val nextCodexDraft = if (keepManualCodex) this.codexCliPath else nextSavedCodex
        val nextNodeDraft = if (keepManualNode) this.nodePath else nextSavedNode
        return copy(
            codexCliPath = nextCodexDraft,
            nodePath = nextNodeDraft,
            savedCodexCliPath = nextSavedCodex,
            savedNodePath = nextSavedNode,
            codexPathManuallyEdited = keepManualCodex && nextCodexDraft.trim() != nextSavedCodex,
            nodePathManuallyEdited = keepManualNode && nextNodeDraft.trim() != nextSavedNode,
        )
    }

    /**
     * Merges detected paths into the current draft without replacing fields that the user
     * has manually changed and not saved yet.
     */
    fun withDetectedPaths(
        codexCliPath: String,
        nodePath: String,
        updateDraftPaths: Boolean,
    ): EnvironmentDraftState {
        if (!updateDraftPaths) return this
        return copy(
            codexCliPath = if (!codexPathManuallyEdited && codexCliPath.isNotBlank()) codexCliPath else this.codexCliPath,
            nodePath = if (!nodePathManuallyEdited && nodePath.isNotBlank()) nodePath else this.nodePath,
        )
    }
}
