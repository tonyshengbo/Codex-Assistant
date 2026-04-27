package com.auracode.assistant.toolwindow.eventing

internal data class ActivePlanRunContext(
    val localTurnId: String,
    val preferredExecutionMode: SubmissionMode,
    var remoteTurnId: String? = null,
    var threadId: String? = null,
    var latestPlanBody: String? = null,
)
