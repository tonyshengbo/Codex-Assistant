package com.auracode.assistant.provider.claude

import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.settings.AgentSettingsService

internal class ClaudeProviderFactory(
    private val settings: AgentSettingsService,
) : AgentProviderFactory {
    override val engineId: String = ENGINE_ID

    override fun create(): AgentProvider = ClaudeCliProvider(settings)

    companion object {
        const val ENGINE_ID: String = "claude"
    }
}
