package com.codex.assistant.toolwindow.timeline

enum class TimelineNodeChrome {
    NARRATIVE,
    EXECUTION,
    RESULT,
    ALERT,
    SUPPORTING,
}

data class TimelineNodePresentation(
    val chrome: TimelineNodeChrome,
    val isToggleable: Boolean,
) {
    companion object {
        fun forKind(kind: TimelineNodeKind): TimelineNodePresentation {
            return when (kind) {
                TimelineNodeKind.ASSISTANT_NOTE,
                TimelineNodeKind.THINKING,
                -> TimelineNodePresentation(
                    chrome = TimelineNodeChrome.NARRATIVE,
                    isToggleable = false,
                )

                TimelineNodeKind.TOOL_STEP,
                TimelineNodeKind.COMMAND_STEP,
                -> TimelineNodePresentation(
                    chrome = TimelineNodeChrome.EXECUTION,
                    isToggleable = true,
                )

                TimelineNodeKind.RESULT -> TimelineNodePresentation(
                    chrome = TimelineNodeChrome.RESULT,
                    isToggleable = false,
                )

                TimelineNodeKind.FAILURE -> TimelineNodePresentation(
                    chrome = TimelineNodeChrome.ALERT,
                    isToggleable = false,
                )

                TimelineNodeKind.SYSTEM_AUX -> TimelineNodePresentation(
                    chrome = TimelineNodeChrome.SUPPORTING,
                    isToggleable = false,
                )
            }
        }
    }
}
