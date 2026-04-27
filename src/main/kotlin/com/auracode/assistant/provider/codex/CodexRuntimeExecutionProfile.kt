package com.auracode.assistant.provider.codex

import com.auracode.assistant.model.AgentApprovalMode

/**
 * Describes the approval and sandbox pairing Aura should use for one execution mode.
 */
internal data class CodexRuntimeExecutionProfile(
    val approvalPolicy: String,
    val sandboxMode: CodexRuntimeSandboxMode,
)

/**
 * Resolves Aura's two execution modes into the app-server approval and sandbox settings.
 *
 * `REQUIRE_CONFIRMATION` matches the official `workspace-write + on-request` behavior,
 * while `AUTO` keeps Aura's current no-prompt full-access execution behavior.
 */
internal fun resolveCodexRuntimeExecutionProfile(
    mode: AgentApprovalMode,
): CodexRuntimeExecutionProfile {
    return when (mode) {
        AgentApprovalMode.AUTO -> CodexRuntimeExecutionProfile(
            approvalPolicy = "never",
            sandboxMode = CodexRuntimeSandboxMode.DANGER_FULL_ACCESS,
        )

        AgentApprovalMode.REQUIRE_CONFIRMATION -> CodexRuntimeExecutionProfile(
            approvalPolicy = "on-request",
            sandboxMode = CodexRuntimeSandboxMode.WORKSPACE_WRITE,
        )
    }
}
