package com.auracode.assistant.toolwindow.shared

import com.auracode.assistant.toolwindow.shell.SkillImportDialogPhase

/** Describes the semantic emphasis used by one shared dialog. */
internal enum class AssistantDialogTone {
    NEUTRAL,
    ACCENT,
    DANGER,
}

/** Stores the visual state derived from one dialog's semantic meaning. */
internal data class AssistantDialogStatePresentation(
    val tone: AssistantDialogTone,
    val showsProgressIndicator: Boolean,
    val showsStatusBadge: Boolean,
    val allowsDismiss: Boolean,
)

/** Maps the skill import phase to the shared dialog visual language. */
internal fun skillImportDialogPresentation(phase: SkillImportDialogPhase): AssistantDialogStatePresentation {
    return when (phase) {
        SkillImportDialogPhase.IN_PROGRESS -> AssistantDialogStatePresentation(
            tone = AssistantDialogTone.ACCENT,
            showsProgressIndicator = true,
            showsStatusBadge = false,
            allowsDismiss = false,
        )

        SkillImportDialogPhase.SUCCEEDED -> AssistantDialogStatePresentation(
            tone = AssistantDialogTone.ACCENT,
            showsProgressIndicator = false,
            showsStatusBadge = false,
            allowsDismiss = true,
        )

        SkillImportDialogPhase.FAILED -> AssistantDialogStatePresentation(
            tone = AssistantDialogTone.DANGER,
            showsProgressIndicator = false,
            showsStatusBadge = false,
            allowsDismiss = true,
        )
    }
}
