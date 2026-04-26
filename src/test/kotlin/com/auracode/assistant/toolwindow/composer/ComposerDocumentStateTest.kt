package com.auracode.assistant.toolwindow.submission

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import com.auracode.assistant.settings.SavedAgentDefinition
import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerDocumentStateTest {
    @Test
    fun `slash query is resolved only at input start`() {
        val query = findSlashQuery(
            value = TextFieldValue("/pl", TextRange(3)),
            mentions = emptyList(),
        )

        assertEquals(SlashQueryMatch(query = "pl", start = 0, end = 3), query)
    }

    @Test
    fun `slash query is ignored outside input start`() {
        val query = findSlashQuery(
            value = TextFieldValue("hello /pl", TextRange(9)),
            mentions = emptyList(),
        )

        assertEquals(null, query)
    }

    @Test
    fun `active mention query is resolved from cursor position`() {
        val query = findMentionQuery(
            value = TextFieldValue("hello @App world", TextRange(10)),
            mentions = emptyList(),
        )

        assertEquals(MentionQueryMatch(query = "App", start = 6, end = 10), query)
    }

    @Test
    fun `active mention query is resolved when at sign follows a letter`() {
        val query = findMentionQuery(
            value = TextFieldValue("abc@Ap", TextRange(6)),
            mentions = emptyList(),
        )

        assertEquals(MentionQueryMatch(query = "Ap", start = 3, end = 6), query)
    }

    @Test
    fun `active mention query is blocked inside existing mention label`() {
        val mention = MentionEntry(
            id = "1",
            path = "/project/src/App.kt",
            displayName = "App.kt",
            start = 6,
            endExclusive = 13,
        )
        val query = findMentionQuery(
            value = TextFieldValue("hello @App.kt", TextRange(10)),
            mentions = listOf(mention),
        )

        assertEquals(null, query)
    }

    @Test
    fun `active mention query is ignored while ime composition is active`() {
        val query = findMentionQuery(
            value = TextFieldValue(
                text = "@App",
                selection = TextRange(4),
                composition = TextRange(1, 4),
            ),
            mentions = emptyList(),
        )

        assertEquals(null, query)
    }

    @Test
    fun `collapsed selection inside mention snaps to nearest boundary`() {
        val mention = MentionEntry(
            id = "1",
            path = "/project/src/App.kt",
            displayName = "App.kt",
            start = 6,
            endExclusive = 13,
        )

        val snappedNearStart = normalizeMentionSelection(
            previous = TextFieldValue("hello @App.kt world", TextRange(0)),
            updated = TextFieldValue("hello @App.kt world", TextRange(7)),
            mentions = listOf(mention),
        )
        val snappedNearEnd = normalizeMentionSelection(
            previous = TextFieldValue("hello @App.kt world", TextRange(18)),
            updated = TextFieldValue("hello @App.kt world", TextRange(12)),
            mentions = listOf(mention),
        )

        assertEquals(TextRange(6), snappedNearStart.selection)
        assertEquals(TextRange(13), snappedNearEnd.selection)
    }

    @Test
    fun `keyboard move left into mention snaps to mention start`() {
        val mention = MentionEntry(
            id = "1",
            path = "/project/src/App.kt",
            displayName = "App.kt",
            start = 6,
            endExclusive = 13,
        )

        val snapped = normalizeMentionSelection(
            previous = TextFieldValue("hello @App.kt world", TextRange(13)),
            updated = TextFieldValue("hello @App.kt world", TextRange(12)),
            mentions = listOf(mention),
        )

        assertEquals(TextRange(6), snapped.selection)
    }

    @Test
    fun `keyboard move right into mention snaps to mention end`() {
        val mention = MentionEntry(
            id = "1",
            path = "/project/src/App.kt",
            displayName = "App.kt",
            start = 6,
            endExclusive = 13,
        )

        val snapped = normalizeMentionSelection(
            previous = TextFieldValue("hello @App.kt world", TextRange(6)),
            updated = TextFieldValue("hello @App.kt world", TextRange(7)),
            mentions = listOf(mention),
        )

        assertEquals(TextRange(13), snapped.selection)
    }

    @Test
    fun `selection touching mention expands to mention boundaries`() {
        val mention = MentionEntry(
            id = "1",
            path = "/project/src/App.kt",
            displayName = "App.kt",
            start = 6,
            endExclusive = 13,
        )

        val normalized = normalizeMentionSelection(
            previous = TextFieldValue("hello @App.kt world", TextRange(0)),
            updated = TextFieldValue("hello @App.kt world", TextRange(4, 10)),
            mentions = listOf(mention),
        )

        assertEquals(TextRange(4, 13), normalized.selection)
    }

    @Test
    fun `backspace at mention boundary removes whole mention`() {
        val mention = MentionEntry(
            id = "1",
            path = "/project/src/App.kt",
            displayName = "App.kt",
            start = 6,
            endExclusive = 13,
        )

        val removed = removeMentionByBackspace(
            document = TextFieldValue("hello @App.kt world", TextRange(13)),
            mentions = listOf(mention),
        )

        assertEquals("hello  world", removed?.first?.text)
        assertEquals(TextRange(6), removed?.first?.selection)
        assertTrue(removed?.second?.isEmpty() == true)
    }

    @Test
    fun `delete at mention boundary removes whole mention`() {
        val mention = MentionEntry(
            id = "1",
            path = "/project/src/App.kt",
            displayName = "App.kt",
            start = 6,
            endExclusive = 13,
        )

        val removed = removeMentionByDelete(
            document = TextFieldValue("hello @App.kt world", TextRange(6)),
            mentions = listOf(mention),
        )

        assertEquals("hello  world", removed?.first?.text)
        assertEquals(TextRange(6), removed?.first?.selection)
        assertTrue(removed?.second?.isEmpty() == true)
    }

    @Test
    fun `deleting selection touching mentions removes whole mentions`() {
        val first = MentionEntry(
            id = "1",
            path = "/project/src/A.kt",
            displayName = "A.kt",
            start = 6,
            endExclusive = 11,
        )
        val second = MentionEntry(
            id = "2",
            path = "/project/src/B.kt",
            displayName = "B.kt",
            start = 16,
            endExclusive = 21,
        )

        val removed = removeMentionSelection(
            document = TextFieldValue("hello @A.kt and @B.kt", TextRange(8, 18)),
            mentions = listOf(first, second),
        )

        assertEquals("hello ", removed?.first?.text)
        assertEquals(TextRange(6), removed?.first?.selection)
        assertTrue(removed?.second?.isEmpty() == true)
    }

    @Test
    fun `select mention file replaces active query with visible label`() {
        val store = ComposerAreaStore()
        val candidate = "/project/src/App.kt"
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("explain @App please", TextRange(12))),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMentionFile(path = candidate)))

        assertEquals("explain @App.kt please", store.state.value.document.text)
        assertEquals(TextRange(15), store.state.value.document.selection)
        assertEquals(1, store.state.value.mentionEntries.size)
        assertEquals(8, store.state.value.mentionEntries.first().start)
        assertEquals(15, store.state.value.mentionEntries.first().endExclusive)
    }

    @Test
    fun `removing mention by id removes visible label from document`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("@App hi", TextRange(4))),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMentionFile(path = "/project/src/App.kt")))
        val mentionId = store.state.value.mentionEntries.first().id

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.RemoveMentionFile(mentionId)))

        assertTrue(store.state.value.mentionEntries.isEmpty())
        assertEquals(" hi", store.state.value.document.text)
    }

    @Test
    fun `serialized prompt strips mention labels and prefixes mention paths`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("@App explain this file", TextRange(4))),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMentionFile(path = "/project/src/App.kt")))

        assertEquals("/project/src/App.kt\n\nexplain this file", store.state.value.serializedPrompt())
    }

    @Test
    fun `updating document before mention shifts mention range`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("@App", TextRange(4))),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMentionFile(path = "/project/src/App.kt")))

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("prefix @App.kt", TextRange(14))),
            ),
        )

        assertEquals(1, store.state.value.mentionEntries.size)
        assertEquals(7, store.state.value.mentionEntries.first().start)
        assertEquals(14, store.state.value.mentionEntries.first().endExclusive)
    }

    @Test
    fun `editing inside mention drops mention metadata`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("@App", TextRange(4))),
            ),
        )
        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectMentionFile(path = "/project/src/App.kt")))

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("@AppX.kt", TextRange(5))),
            ),
        )

        assertTrue(store.state.value.mentionEntries.isEmpty())
    }

    @Test
    fun `document without content cannot send`() {
        val store = ComposerAreaStore()
        assertFalse(store.state.value.hasPromptContent())
    }

    @Test
    fun `mention transformation keeps text length unchanged`() {
        val transformed = buildMentionTransformedText(
            text = "hello @App.kt",
            spans = listOf(MentionTransformSpan(start = 6, endExclusive = 13)),
        ) { _, _, _ -> }

        assertEquals("hello @App.kt", transformed.text.text)
        assertEquals(OffsetMapping.Identity.originalToTransformed(8), transformed.offsetMapping.originalToTransformed(8))
    }

    @Test
    fun `select agent replaces hash query with agent context chip`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("please #rev now", TextRange(11))),
            ),
        )

        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.SelectAgent(
                    SavedAgentDefinition(
                        id = "agent-1",
                        name = "Reviewer",
                        prompt = "Review the result carefully.",
                    ),
                ),
            ),
        )

        assertEquals("please  now", store.state.value.document.text)
        assertEquals(1, store.state.value.agentEntries.size)
        assertEquals("Reviewer", store.state.value.agentEntries.single().name)
        assertEquals("please now", store.state.value.serializedPrompt())
        assertEquals(listOf("Review the result carefully."), store.state.value.serializedSystemInstructions())
    }

    @Test
    fun `prompt accepted clears transient input but keeps selected agents`() {
        val store = ComposerAreaStore()
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.SelectAgent(
                    SavedAgentDefinition(
                        id = "agent-1",
                        name = "Reviewer",
                        prompt = "Review the result carefully.",
                    ),
                ),
            ),
        )
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("please explain", TextRange(14))),
            ),
        )
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.AddAttachments(listOf("/tmp/demo.txt")),
            ),
        )

        store.onEvent(AppEvent.PromptAccepted("please explain"))

        assertEquals("", store.state.value.document.text)
        assertTrue(store.state.value.attachments.isEmpty())
        assertEquals(1, store.state.value.agentEntries.size)
        assertEquals("Reviewer", store.state.value.agentEntries.single().name)
        assertEquals(listOf("Review the result carefully."), store.state.value.serializedSystemInstructions())
    }

    @Test
    fun `select slash skill inserts visible skill token`() {
        val store = ComposerAreaStore(
            availableSkillsProvider = {
                listOf(
                    SlashSkillDescriptor(
                        name = "brainstorming",
                        description = "Explore requirements before building.",
                    ),
                )
            },
        )
        store.onEvent(
            AppEvent.UiIntentPublished(
                UiIntent.UpdateDocument(TextFieldValue("/bra", TextRange(4))),
            ),
        )

        store.onEvent(AppEvent.UiIntentPublished(UiIntent.SelectSlashSkill("brainstorming")))

        assertEquals("\$brainstorming ", store.state.value.document.text)
        assertEquals(TextRange(15), store.state.value.document.selection)
        assertEquals("\$brainstorming", store.state.value.serializedPrompt())
    }
}
