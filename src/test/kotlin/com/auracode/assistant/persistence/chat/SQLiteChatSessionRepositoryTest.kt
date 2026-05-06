package com.auracode.assistant.persistence.chat

import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.protocol.ItemStatus
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SQLiteChatSessionRepositoryTest {
    @Test
    fun `stores session assets and updates local turn ids after remote turn starts`() {
        val repository = SQLiteChatSessionRepository(createTempDirectory("chat-repository-test").resolve("chat.db"))
        repository.upsertSession(
            PersistedChatSession(
                id = "session-1",
                title = "First session",
                createdAt = 10L,
                updatedAt = 20L,
                messageCount = 1,
                providerId = "codex",
                remoteConversationId = "thread-1",
                usageSnapshot = null,
                isActive = true,
            ),
        )

        repository.saveSessionAssets(
            sessionId = "session-1",
            turnId = "local-turn-1",
            messageRole = MessageRole.USER,
            attachments = listOf(
                PersistedMessageAttachment(
                    id = "attachment-1",
                    kind = PersistedAttachmentKind.IMAGE,
                    displayName = "preview.png",
                    assetPath = "/assets/preview.png",
                    originalPath = "/tmp/preview.png",
                    mimeType = "image/png",
                    sizeBytes = 128L,
                    status = ItemStatus.SUCCESS,
                ),
                PersistedMessageAttachment(
                    id = "attachment-2",
                    kind = PersistedAttachmentKind.FILE,
                    displayName = "notes.pdf",
                    assetPath = "/assets/notes.pdf",
                    originalPath = "/tmp/notes.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 512L,
                    status = ItemStatus.SUCCESS,
                ),
            ),
            createdAt = 30L,
        )

        repository.replaceSessionAssetTurnId(
            sessionId = "session-1",
            fromTurnId = "local-turn-1",
            toTurnId = "turn-1",
        )

        val assets = repository.loadSessionAssets("session-1")
        assertEquals(2, assets.size)
        assertTrue(assets.all { it.turnId == "turn-1" })
        assertEquals(
            listOf(PersistedAttachmentKind.IMAGE, PersistedAttachmentKind.FILE),
            assets.map { it.attachment.kind },
        )

        val activeSession = assertNotNull(repository.loadActiveSession())
        assertEquals("session-1", activeSession.id)
    }

    @Test
    fun `stores usage ledger entries and exposes provider session and latest queries`() {
        val repository = SQLiteChatSessionRepository(createTempDirectory("chat-repository-ledger").resolve("chat.db"))
        repository.upsertSession(
            PersistedChatSession(
                id = "session-1",
                title = "First session",
                createdAt = 1L,
                updatedAt = 3L,
                messageCount = 1,
                providerId = "codex",
                remoteConversationId = "thread-1",
                usageSnapshot = null,
                isActive = true,
            ),
        )
        repository.upsertSession(
            PersistedChatSession(
                id = "session-2",
                title = "Second session",
                createdAt = 2L,
                updatedAt = 4L,
                messageCount = 1,
                providerId = "claude",
                remoteConversationId = "thread-2",
                usageSnapshot = null,
                isActive = false,
            ),
        )

        repository.appendRecord(
            PersistedSessionUsageLedgerEntry(
                id = "ledger-1",
                sessionId = "session-1",
                providerId = "codex",
                model = "gpt-5.4",
                contextWindow = 400_000,
                inputTokens = 100,
                cachedInputTokens = 20,
                outputTokens = 10,
                capturedAt = 100L,
                sourceTurnId = "turn-1",
                isBaseline = true,
            ),
        )
        repository.appendRecord(
            PersistedSessionUsageLedgerEntry(
                id = "ledger-2",
                sessionId = "session-1",
                providerId = "codex",
                model = "gpt-5.4",
                contextWindow = 400_000,
                inputTokens = 160,
                cachedInputTokens = 40,
                outputTokens = 30,
                capturedAt = 200L,
                sourceTurnId = "turn-2",
                isBaseline = false,
            ),
        )
        repository.appendRecord(
            PersistedSessionUsageLedgerEntry(
                id = "ledger-3",
                sessionId = "session-2",
                providerId = "claude",
                model = "claude-sonnet-4",
                contextWindow = 200_000,
                inputTokens = 50,
                cachedInputTokens = 0,
                outputTokens = 5,
                capturedAt = 150L,
                sourceTurnId = "turn-3",
                isBaseline = true,
            ),
        )

        val sessionRecords = repository.listRecordsBySession("session-1")
        assertEquals(listOf("ledger-1", "ledger-2"), sessionRecords.map { it.id })
        assertEquals("ledger-2", repository.loadLatestRecord("session-1")?.id)

        val providerRecords = repository.listRecordsByProvider("codex")
        assertEquals(listOf("ledger-1", "ledger-2"), providerRecords.map { it.id })
        assertTrue(providerRecords.none { it.providerId != "codex" })
    }

    @Test
    fun `deleting a session cascades stored assets`() {
        val repository = SQLiteChatSessionRepository(createTempDirectory("chat-repository-delete").resolve("chat.db"))
        repository.upsertSession(
            PersistedChatSession(
                id = "session-1",
                title = "",
                createdAt = 1L,
                updatedAt = 1L,
                messageCount = 0,
                providerId = "codex",
                remoteConversationId = "",
                usageSnapshot = null,
                isActive = true,
            ),
        )
        repository.saveSessionAssets(
            sessionId = "session-1",
            turnId = "turn-1",
            messageRole = MessageRole.USER,
            attachments = listOf(
                PersistedMessageAttachment(
                    id = "attachment-1",
                    kind = PersistedAttachmentKind.TEXT,
                    displayName = "hello.txt",
                    assetPath = "/assets/hello.txt",
                    originalPath = "/tmp/hello.txt",
                    mimeType = "text/plain",
                    sizeBytes = 5L,
                ),
            ),
            createdAt = 2L,
        )
        repository.appendRecord(
            PersistedSessionUsageLedgerEntry(
                id = "ledger-1",
                sessionId = "session-1",
                providerId = "codex",
                model = "gpt-5.4",
                contextWindow = 400_000,
                inputTokens = 10,
                cachedInputTokens = 0,
                outputTokens = 1,
                capturedAt = 3L,
                sourceTurnId = "turn-1",
                isBaseline = true,
            ),
        )

        repository.deleteSession("session-1")

        assertEquals(null, repository.loadSession("session-1"))
        assertTrue(repository.loadSessionAssets("session-1").isEmpty())
        assertTrue(repository.listRecordsBySession("session-1").isEmpty())
        assertEquals(null, repository.loadLatestRecord("session-1"))
    }
}
