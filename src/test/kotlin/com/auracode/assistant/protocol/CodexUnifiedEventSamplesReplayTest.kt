package com.auracode.assistant.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodexUnifiedEventSamplesReplayTest {
    @Test
    fun replaysAllEventSampleFiles() {
        val samplesDir = Path.of("docs/openai/codex-event-samples")
        Files.list(samplesDir).use { paths ->
            paths.asSequence()
                .filter { it.fileName.toString().endsWith(".jsonl") }
                .sortedBy { it.fileName.toString() }
                .forEach { file ->
                    val events = Files.readAllLines(file)
                        .asSequence()
                        .filter { it.isNotBlank() }
                        .map { line ->
                            CodexUnifiedEventParser.parseLine(line).also {
                                assertNotNull(it, "Expected parser to handle ${file.name}: $line")
                            }!!
                        }
                        .toList()
                    assertTrue(events.isNotEmpty(), "Expected at least one event in ${file.name}")
                    assertTrue(events.any { it is UnifiedEvent.TurnStarted }, "Expected turn started in ${file.name}")
                    assertTrue(events.any { it is UnifiedEvent.TurnCompleted }, "Expected turn completed in ${file.name}")

                    when (file.name) {
                        "session-thread-turn-item.jsonl" -> {
                            assertTrue(events.any { it is UnifiedEvent.ThreadTokenUsageUpdated })
                            assertTrue(events.any { it is UnifiedEvent.ItemUpdated && it.item.kind == ItemKind.NARRATIVE })
                        }
                        "approval-and-diff.jsonl" -> {
                            assertTrue(events.any { it is UnifiedEvent.TurnDiffUpdated })
                            assertTrue(events.any { it is UnifiedEvent.ItemUpdated && it.item.kind == ItemKind.DIFF_APPLY })
                            assertTrue(events.any { it is UnifiedEvent.ItemUpdated && it.item.kind == ItemKind.COMMAND_EXEC })
                        }
                        "error-and-resume.jsonl" -> {
                            assertTrue(events.any { it is UnifiedEvent.Error })
                            assertTrue(events.any { it is UnifiedEvent.ItemUpdated && it.item.kind == ItemKind.CONTEXT_COMPACTION })
                            assertTrue(events.any { it is UnifiedEvent.ItemUpdated && it.item.kind == ItemKind.PLAN_UPDATE })
                        }
                        "mcp-tool-call.jsonl" -> {
                            assertTrue(events.any { it is UnifiedEvent.ItemUpdated && it.item.name == "mcp:cloudview-gray" })
                            assertTrue(events.any { it is UnifiedEvent.ItemUpdated && it.item.text.orEmpty().contains("- Tool: `get_figma_node`") })
                        }
                    }
                }
        }
    }
}
