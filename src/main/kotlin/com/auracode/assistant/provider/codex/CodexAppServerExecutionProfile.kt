package com.auracode.assistant.provider.codex

import com.auracode.assistant.model.AgentApprovalMode

/**
 * Describes the approval and sandbox pairing Aura should use for one execution mode.
 */
internal data class CodexAppServerExecutionProfile(
    val approvalPolicy: String,
    val sandboxMode: CodexAppServerSandboxMode,
)

/**
 * Resolves Aura's two execution modes into the app-server approval and sandbox settings.
 *
 * `REQUIRE_CONFIRMATION` matches the official `workspace-write + on-request` behavior,
 * while `AUTO` keeps Aura's current no-prompt full-access execution behavior.
 */
internal fun resolveCodexAppServerExecutionProfile(
    mode: AgentApprovalMode,
): CodexAppServerExecutionProfile {
    return when (mode) {
        AgentApprovalMode.AUTO -> CodexAppServerExecutionProfile(
            approvalPolicy = "never",
            sandboxMode = CodexAppServerSandboxMode.DANGER_FULL_ACCESS,
        )

        AgentApprovalMode.REQUIRE_CONFIRMATION -> CodexAppServerExecutionProfile(
            approvalPolicy = "on-request",
            sandboxMode = CodexAppServerSandboxMode.WORKSPACE_WRITE,
        )
    }
}
