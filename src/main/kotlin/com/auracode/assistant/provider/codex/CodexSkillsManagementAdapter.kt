package com.auracode.assistant.provider.codex

import com.auracode.assistant.provider.CodexProviderFactory
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.settings.skills.RuntimeSkillRecord
import com.auracode.assistant.settings.skills.SkillSelector
import com.auracode.assistant.settings.skills.SkillsManagementAdapter
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Provides Codex-specific runtime skills management using short-lived
 * `codex app-server` requests. This keeps settings-page reads and writes
 * aligned with the same runtime protocol that the conversation provider uses.
 */
internal class CodexSkillsManagementAdapter(
    private val settings: AgentSettingsService,
    private val environmentDetector: CodexEnvironmentDetector = CodexEnvironmentDetector(),
    private val diagnosticLogger: (String) -> Unit = {},
) : SkillsManagementAdapter {
    override val engineId: String = CodexProviderFactory.ENGINE_ID

    override fun supportsRuntimeSkills(): Boolean = true

    override suspend fun listRuntimeSkills(
        cwd: String,
        forceReload: Boolean,
    ): List<RuntimeSkillRecord> {
        return withClient(cwd) { client ->
            client.listSkills(cwd = cwd, forceReload = forceReload)
        }
    }

    override suspend fun setSkillEnabled(
        cwd: String,
        selector: SkillSelector,
        enabled: Boolean,
    ) {
        withClient(cwd) { client ->
            client.setSkillEnabled(selector = selector, enabled = enabled)
        }
    }

    private suspend fun <T> withClient(
        cwd: String,
        block: suspend (client: CodexAppServerClient) -> T,
    ): T {
        val resolution = environmentDetector.resolveForLaunch(
            configuredCodexPath = settings.getState().executablePathFor(CodexProviderFactory.ENGINE_ID),
            configuredNodePath = settings.nodeExecutablePath(),
        )
        require(resolution.codexStatus != CodexEnvironmentStatus.MISSING) { "Aura Code runtime path is not configured." }
        require(resolution.codexStatus != CodexEnvironmentStatus.FAILED) {
            "Configured Codex Runtime Path is not executable. Update Settings and try again."
        }
        val binary = resolution.codexPath.trim()
        val process = createCodexAppServerProcess(
            binary = binary,
            environmentOverrides = resolution.environmentOverrides,
            workingDirectory = File(cwd),
        )
        return try {
            val session = CodexProcessAppServerSession(process, diagnosticLogger)
            val client = CodexAppServerClient(session, diagnosticLogger)
            session.start()
            withTimeout(APP_SERVER_HANDSHAKE_TIMEOUT_MS) {
                session.initialize()
                block(client)
            }
        } finally {
            process.destroy()
        }
    }
}
