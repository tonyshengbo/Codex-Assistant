package com.auracode.assistant.persistence.chat

import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.model.TurnUsageSnapshot
import com.auracode.assistant.protocol.ItemStatus
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

internal data class PersistedChatSession(
    val id: String,
    val providerId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val remoteConversationId: String,
    val usageSnapshot: TurnUsageSnapshot?,
    val isActive: Boolean,
)

internal enum class PersistedAttachmentKind {
    IMAGE,
    FILE,
    TEXT,
}

internal data class PersistedMessageAttachment(
    val id: String = UUID.randomUUID().toString(),
    val kind: PersistedAttachmentKind,
    val displayName: String,
    val assetPath: String,
    val originalPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String = "",
    val status: ItemStatus = ItemStatus.SUCCESS,
)

internal data class PersistedSessionAsset(
    val id: String,
    val sessionId: String,
    val turnId: String,
    val messageRole: MessageRole,
    val attachment: PersistedMessageAttachment,
    val createdAt: Long,
)

internal interface ChatSessionRepository {
    fun listSessions(): List<PersistedChatSession>
    fun loadSession(sessionId: String): PersistedChatSession?
    fun loadActiveSession(): PersistedChatSession?
    fun upsertSession(session: PersistedChatSession)
    fun markActiveSession(sessionId: String)
    fun deleteSession(sessionId: String)
    fun saveSessionAssets(
        sessionId: String,
        turnId: String,
        messageRole: MessageRole,
        attachments: List<PersistedMessageAttachment>,
        createdAt: Long = System.currentTimeMillis(),
    )
    fun replaceSessionAssetTurnId(sessionId: String, fromTurnId: String, toTurnId: String)
    fun loadSessionAssets(sessionId: String): List<PersistedSessionAsset>
}

internal class SQLiteChatSessionRepository(
    private val dbPath: Path,
) : ChatSessionRepository, SessionUsageLedgerRepository {
    private val usageLedgerRepository = SQLiteSessionUsageLedgerRepository(
        dbPath = dbPath,
        ensureSchema = false,
    )

    init {
        Class.forName("org.sqlite.JDBC")
        dbPath.parent?.let { Files.createDirectories(it) }
        withConnection { connection ->
            ensureSessionsTable(connection)
            ensureSessionAssetsTable(connection)
            SessionUsageLedgerSchema.ensureAll(connection)
            ensureIndexes(connection)
        }
    }

    override fun listSessions(): List<PersistedChatSession> {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, provider_id, title, created_at, updated_at, message_count, remote_conversation_id, remote_thread_id,
                       usage_model, usage_context_window, usage_input_tokens,
                       usage_cached_input_tokens, usage_output_tokens, usage_captured_at, is_active
                FROM sessions
                ORDER BY updated_at DESC, created_at DESC
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.toSession())
                    }
                }
            }
        }
    }

    override fun loadSession(sessionId: String): PersistedChatSession? {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, provider_id, title, created_at, updated_at, message_count, remote_conversation_id, remote_thread_id,
                       usage_model, usage_context_window, usage_input_tokens,
                       usage_cached_input_tokens, usage_output_tokens, usage_captured_at, is_active
                FROM sessions
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rs -> if (rs.next()) rs.toSession() else null }
            }
        }
    }

    override fun loadActiveSession(): PersistedChatSession? {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, provider_id, title, created_at, updated_at, message_count, remote_conversation_id, remote_thread_id,
                       usage_model, usage_context_window, usage_input_tokens,
                       usage_cached_input_tokens, usage_output_tokens, usage_captured_at, is_active
                FROM sessions
                WHERE is_active = 1
                ORDER BY updated_at DESC
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rs -> if (rs.next()) rs.toSession() else null }
            }
        }
    }

    override fun upsertSession(session: PersistedChatSession) {
        withConnection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO sessions (
                    id, provider_id, title, created_at, updated_at, message_count, remote_conversation_id,
                    usage_model, usage_context_window, usage_input_tokens,
                    usage_cached_input_tokens, usage_output_tokens, usage_captured_at, is_active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    provider_id = excluded.provider_id,
                    title = excluded.title,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at,
                    message_count = excluded.message_count,
                    remote_conversation_id = excluded.remote_conversation_id,
                    usage_model = excluded.usage_model,
                    usage_context_window = excluded.usage_context_window,
                    usage_input_tokens = excluded.usage_input_tokens,
                    usage_cached_input_tokens = excluded.usage_cached_input_tokens,
                    usage_output_tokens = excluded.usage_output_tokens,
                    usage_captured_at = excluded.usage_captured_at,
                    is_active = excluded.is_active
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, session.id)
                statement.setString(2, session.providerId)
                statement.setString(3, session.title)
                statement.setLong(4, session.createdAt)
                statement.setLong(5, session.updatedAt)
                statement.setInt(6, session.messageCount)
                statement.setString(7, session.remoteConversationId)
                statement.setString(8, session.usageSnapshot?.model.orEmpty())
                statement.setInt(9, session.usageSnapshot?.contextWindow ?: 0)
                statement.setInt(10, session.usageSnapshot?.inputTokens ?: 0)
                statement.setInt(11, session.usageSnapshot?.cachedInputTokens ?: 0)
                statement.setInt(12, session.usageSnapshot?.outputTokens ?: 0)
                statement.setLong(13, session.usageSnapshot?.capturedAt ?: 0L)
                statement.setInt(14, if (session.isActive) 1 else 0)
                statement.executeUpdate()
            }
        }
    }

    override fun markActiveSession(sessionId: String) {
        withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE sessions
                SET is_active = CASE WHEN id = ? THEN 1 ELSE 0 END
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeUpdate()
            }
        }
    }

    override fun deleteSession(sessionId: String) {
        withConnection { connection ->
            connection.prepareStatement("DELETE FROM sessions WHERE id = ?").use { statement ->
                statement.setString(1, sessionId)
                statement.executeUpdate()
            }
        }
    }

    override fun saveSessionAssets(
        sessionId: String,
        turnId: String,
        messageRole: MessageRole,
        attachments: List<PersistedMessageAttachment>,
        createdAt: Long,
    ) {
        if (attachments.isEmpty()) return
        withConnection { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT INTO session_assets (
                        id, session_id, turn_id, message_role, attachment_kind, display_name,
                        asset_path, original_path, mime_type, size_bytes, sha256, status, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        session_id = excluded.session_id,
                        turn_id = excluded.turn_id,
                        message_role = excluded.message_role,
                        attachment_kind = excluded.attachment_kind,
                        display_name = excluded.display_name,
                        asset_path = excluded.asset_path,
                        original_path = excluded.original_path,
                        mime_type = excluded.mime_type,
                        size_bytes = excluded.size_bytes,
                        sha256 = excluded.sha256,
                        status = excluded.status,
                        created_at = excluded.created_at
                    """.trimIndent(),
                ).use { statement ->
                    attachments.forEach { attachment ->
                        statement.setString(1, attachment.id)
                        statement.setString(2, sessionId)
                        statement.setString(3, turnId)
                        statement.setString(4, messageRole.name)
                        statement.setString(5, attachment.kind.name)
                        statement.setString(6, attachment.displayName)
                        statement.setString(7, attachment.assetPath)
                        statement.setString(8, attachment.originalPath)
                        statement.setString(9, attachment.mimeType)
                        statement.setLong(10, attachment.sizeBytes)
                        statement.setString(11, attachment.sha256)
                        statement.setString(12, attachment.status.name)
                        statement.setLong(13, createdAt)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (t: Throwable) {
                connection.rollback()
                throw t
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override fun replaceSessionAssetTurnId(sessionId: String, fromTurnId: String, toTurnId: String) {
        if (fromTurnId.isBlank() || toTurnId.isBlank() || fromTurnId == toTurnId) return
        withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE session_assets
                SET turn_id = ?
                WHERE session_id = ? AND turn_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, toTurnId)
                statement.setString(2, sessionId)
                statement.setString(3, fromTurnId)
                statement.executeUpdate()
            }
        }
    }

    override fun loadSessionAssets(sessionId: String): List<PersistedSessionAsset> {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, session_id, turn_id, message_role, attachment_kind, display_name,
                       asset_path, original_path, mime_type, size_bytes, sha256, status, created_at
                FROM session_assets
                WHERE session_id = ?
                ORDER BY created_at ASC, id ASC
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.toSessionAsset())
                    }
                }
            }
        }
    }

    /**
     * Appends one usage ledger record into the shared chat database.
     */
    override fun appendRecord(record: PersistedSessionUsageLedgerEntry) {
        usageLedgerRepository.appendRecord(record)
    }

    /**
     * Returns all usage ledger records for one provider.
     */
    override fun listRecordsByProvider(providerId: String): List<PersistedSessionUsageLedgerEntry> {
        return usageLedgerRepository.listRecordsByProvider(providerId)
    }

    /**
     * Returns all usage ledger records for one session.
     */
    override fun listRecordsBySession(sessionId: String): List<PersistedSessionUsageLedgerEntry> {
        return usageLedgerRepository.listRecordsBySession(sessionId)
    }

    /**
     * Returns the newest usage ledger record for one session.
     */
    override fun loadLatestRecord(sessionId: String): PersistedSessionUsageLedgerEntry? {
        return usageLedgerRepository.loadLatestRecord(sessionId)
    }

    private fun ensureSessionsTable(connection: Connection) {
        connection.createStatement().use { statement: java.sql.Statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    provider_id TEXT NOT NULL DEFAULT 'codex',
                    title TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    message_count INTEGER NOT NULL DEFAULT 0,
                    remote_thread_id TEXT NOT NULL DEFAULT '',
                    remote_conversation_id TEXT NOT NULL DEFAULT '',
                    usage_model TEXT NOT NULL DEFAULT '',
                    usage_context_window INTEGER NOT NULL DEFAULT 0,
                    usage_input_tokens INTEGER NOT NULL DEFAULT 0,
                    usage_cached_input_tokens INTEGER NOT NULL DEFAULT 0,
                    usage_output_tokens INTEGER NOT NULL DEFAULT 0,
                    usage_captured_at INTEGER NOT NULL DEFAULT 0,
                    is_active INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            addColumnIfMissing(statement, "sessions", "provider_id", "TEXT NOT NULL DEFAULT 'codex'")
            addColumnIfMissing(statement, "sessions", "remote_conversation_id", "TEXT NOT NULL DEFAULT ''")
        }
    }

    private fun ensureSessionAssetsTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS session_assets (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    turn_id TEXT NOT NULL DEFAULT '',
                    message_role TEXT NOT NULL DEFAULT 'USER',
                    attachment_kind TEXT NOT NULL DEFAULT 'FILE',
                    display_name TEXT NOT NULL DEFAULT '',
                    asset_path TEXT NOT NULL DEFAULT '',
                    original_path TEXT NOT NULL DEFAULT '',
                    mime_type TEXT NOT NULL DEFAULT '',
                    size_bytes INTEGER NOT NULL DEFAULT 0,
                    sha256 TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'SUCCESS',
                    created_at INTEGER NOT NULL,
                    FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
        }
    }

    private fun ensureIndexes(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_sessions_updated_at
                ON sessions(updated_at DESC)
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_session_assets_session_turn
                ON session_assets(session_id, turn_id, message_role)
                """.trimIndent(),
            )
        }
    }

    private fun addColumnIfMissing(
        statement: java.sql.Statement,
        tableName: String,
        columnName: String,
        definition: String,
    ) {
        val existingColumns = statement.connection.prepareStatement("PRAGMA table_info($tableName)").use { pragma ->
            pragma.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) add(rs.getString("name"))
                }
            }
        }
        if (columnName !in existingColumns) {
            statement.execute("ALTER TABLE $tableName ADD COLUMN $columnName $definition")
        }
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        return DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
            }
            block(connection)
        }
    }

    private fun java.sql.ResultSet.toSession(): PersistedChatSession {
        val usageModel = getString("usage_model").orEmpty()
        val usageContextWindow = getInt("usage_context_window")
        val usageInputTokens = getInt("usage_input_tokens")
        val usageCachedInputTokens = getInt("usage_cached_input_tokens")
        val usageOutputTokens = getInt("usage_output_tokens")
        val usageCapturedAt = getLong("usage_captured_at")
        val usageSnapshot = if (
            usageCapturedAt == 0L &&
            usageModel.isBlank() &&
            usageContextWindow == 0 &&
            usageInputTokens == 0 &&
            usageCachedInputTokens == 0 &&
            usageOutputTokens == 0
        ) {
            null
        } else {
            TurnUsageSnapshot(
                model = usageModel,
                contextWindow = usageContextWindow,
                inputTokens = usageInputTokens,
                cachedInputTokens = usageCachedInputTokens,
                outputTokens = usageOutputTokens,
                capturedAt = usageCapturedAt,
            )
        }
        return PersistedChatSession(
            id = getString("id"),
            providerId = getString("provider_id").orEmpty().ifBlank { "codex" },
            title = getString("title"),
            createdAt = getLong("created_at"),
            updatedAt = getLong("updated_at"),
            messageCount = getInt("message_count"),
            remoteConversationId = getString("remote_conversation_id").orEmpty().ifBlank {
                getString("remote_thread_id").orEmpty()
            },
            usageSnapshot = usageSnapshot,
            isActive = getInt("is_active") == 1,
        )
    }

    private fun java.sql.ResultSet.toSessionAsset(): PersistedSessionAsset {
        val attachment = PersistedMessageAttachment(
            id = getString("id"),
            kind = PersistedAttachmentKind.valueOf(getString("attachment_kind").orEmpty().ifBlank { PersistedAttachmentKind.FILE.name }),
            displayName = getString("display_name").orEmpty(),
            assetPath = getString("asset_path").orEmpty(),
            originalPath = getString("original_path").orEmpty(),
            mimeType = getString("mime_type").orEmpty(),
            sizeBytes = getLong("size_bytes"),
            sha256 = getString("sha256").orEmpty(),
            status = getString("status").orEmpty().takeIf { it.isNotBlank() }?.let(ItemStatus::valueOf) ?: ItemStatus.SUCCESS,
        )
        return PersistedSessionAsset(
            id = attachment.id,
            sessionId = getString("session_id"),
            turnId = getString("turn_id").orEmpty(),
            messageRole = getString("message_role").orEmpty().takeIf { it.isNotBlank() }?.let(MessageRole::valueOf) ?: MessageRole.USER,
            attachment = attachment,
            createdAt = getLong("created_at"),
        )
    }
}
