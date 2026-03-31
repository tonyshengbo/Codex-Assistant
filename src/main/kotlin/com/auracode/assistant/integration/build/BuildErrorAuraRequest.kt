package com.auracode.assistant.integration.build

/**
 * Carries a build error snapshot together with the generated prompt that Aura will submit.
 */
data class BuildErrorAuraRequest(
    val snapshot: BuildErrorSnapshot,
    val prompt: String,
)
