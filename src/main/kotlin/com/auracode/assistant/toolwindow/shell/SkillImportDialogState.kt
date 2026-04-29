package com.auracode.assistant.toolwindow.shell

/** Describes the lifecycle phase rendered by the Skills import status dialog. */
internal enum class SkillImportDialogPhase {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
}

/** Stores the current status dialog payload for one Skills import attempt. */
internal data class SkillImportDialogState(
    val phase: SkillImportDialogPhase,
    val title: String,
    val message: String,
    val sourcePath: String,
) {
    /** Returns true when the dialog may be dismissed by the user. */
    val dismissible: Boolean
        get() = phase != SkillImportDialogPhase.IN_PROGRESS
}
