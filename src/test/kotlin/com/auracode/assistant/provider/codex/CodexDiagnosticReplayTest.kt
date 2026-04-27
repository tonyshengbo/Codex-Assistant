package com.auracode.assistant.provider.codex

import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.provider.diagnostics.ProviderDiagnosticFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies that recorded Codex diagnostic fixtures still replay into structured unified events.
 */
class CodexDiagnosticReplayTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Ensures the recorded Codex diagnostic fixture exposes raw lines in order.
     */
    @Test
    fun `loads codex diagnostic raw fixture from classpath`() {
        val fixture = ProviderDiagnosticFixture.load("/provider/codex/codex-diagnostic-file-change.jsonl")

        assertTrue(fixture.lines.isNotEmpty())
    }

    /**
     * Verifies that the recorded Codex file-change fixture still produces structured file-change items.
     */
    @Test
    fun `replays codex file change diagnostic fixture into structured unified events`() {
        val parser = CodexRuntimeProvider.CodexRuntimeNotificationParser(
            requestId = "req-diagnostic",
            diagnosticLogger = {},
        )
        val fixture = ProviderDiagnosticFixture.load("/provider/codex/codex-diagnostic-file-change.jsonl")

        val events = replayFixture(
            fixture = fixture,
            parser = parser,
        )

        assertTrue(events.any { event ->
            event is ProviderEvent.ItemUpdated &&
                event.item.kind == ItemKind.DIFF_APPLY &&
                event.item.fileChanges.singleOrNull()?.path ==
                "/Users/tonysheng/StudioProject/Aura/docs/superpowers/specs/2026-04-25-multi-engine-render-kernel-design.md" &&
                event.item.fileChanges.singleOrNull()?.kind == "update"
        })
        assertTrue(events.any { event ->
            event is ProviderEvent.ItemUpdated &&
                event.item.kind == ItemKind.DIFF_APPLY &&
                event.item.text.orEmpty().contains("Updated the following files:")
        })
    }

    /**
     * Replays raw app-server notification lines into unified events.
     */
    private fun replayFixture(
        fixture: ProviderDiagnosticFixture,
        parser: CodexRuntimeProvider.CodexRuntimeNotificationParser,
    ): List<ProviderEvent> {
        return fixture.lines.flatMap { line ->
            val payload = json.parseToJsonElement(line).jsonObject
            val method = payload["method"]?.jsonPrimitive?.contentOrNull ?: return@flatMap emptyList()
            val params = payload["params"]?.jsonObject ?: buildJsonObject {}
            parser.parseNotification(method = method, params = params)
        }
    }
}
