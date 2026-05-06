package com.auracode.assistant.persistence.chat

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * Defines the persistence contract for session usage ledger records.
 */
internal interface SessionUsageLedgerRepository {
    /**
     * Appends one usage snapshot record into the ledger.
     */
    fun appendRecord(record: PersistedSessionUsageLedgerEntry)

    /**
     * Returns all usage records for one provider ordered by session and capture time.
     */
    fun listRecordsByProvider(providerId: String): List<PersistedSessionUsageLedgerEntry>

    /**
     * Returns all usage records for one session ordered by capture time.
     */
    fun listRecordsBySession(sessionId: String): List<PersistedSessionUsageLedgerEntry>

    /**
     * Returns the latest usage record for one session.
     */
    fun loadLatestRecord(sessionId: String): PersistedSessionUsageLedgerEntry?
}

/**
 * Persists usage ledger records inside the shared chat history SQLite database.
 */
internal class SQLiteSessionUsageLedgerRepository(
    private val dbPath: Path,
    ensureSchema: Boolean = true,
) : SessionUsageLedgerRepository {
    init {
        Class.forName("org.sqlite.JDBC")
        dbPath.parent?.let { Files.createDirectories(it) }
        if (ensureSchema) {
            withConnection { connection ->
                SessionUsageLedgerSchema.ensureAll(connection)
            }
        }
    }

    /**
     * Appends one immutable usage ledger record.
     */
    override fun appendRecord(record: PersistedSessionUsageLedgerEntry) {
        withConnection { connection ->
            connection.prepareStatement(
                """
                INSERT INTO session_usage_ledger (
                    id, session_id, provider_id, model, context_window, input_tokens,
                    cached_input_tokens, output_tokens, captured_at, source_turn_id, is_baseline
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, record.id)
                statement.setString(2, record.sessionId)
                statement.setString(3, record.providerId)
                statement.setString(4, record.model)
                statement.setInt(5, record.contextWindow)
                statement.setInt(6, record.inputTokens)
                statement.setInt(7, record.cachedInputTokens)
                statement.setInt(8, record.outputTokens)
                statement.setLong(9, record.capturedAt)
                statement.setString(10, record.sourceTurnId.orEmpty())
                statement.setInt(11, if (record.isBaseline) 1 else 0)
                statement.executeUpdate()
            }
        }
    }

    /**
     * Returns all usage records for one provider ordered for aggregation.
     */
    override fun listRecordsByProvider(providerId: String): List<PersistedSessionUsageLedgerEntry> {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, session_id, provider_id, model, context_window, input_tokens,
                       cached_input_tokens, output_tokens, captured_at, source_turn_id, is_baseline
                FROM session_usage_ledger
                WHERE provider_id = ?
                ORDER BY session_id ASC, captured_at ASC, id ASC
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, providerId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.toUsageLedgerEntry())
                    }
                }
            }
        }
    }

    /**
     * Returns all usage records for one session ordered for delta calculation.
     */
    override fun listRecordsBySession(sessionId: String): List<PersistedSessionUsageLedgerEntry> {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, session_id, provider_id, model, context_window, input_tokens,
                       cached_input_tokens, output_tokens, captured_at, source_turn_id, is_baseline
                FROM session_usage_ledger
                WHERE session_id = ?
                ORDER BY captured_at ASC, id ASC
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(rs.toUsageLedgerEntry())
                    }
                }
            }
        }
    }

    /**
     * Returns the newest usage record for one session to support dedup checks.
     */
    override fun loadLatestRecord(sessionId: String): PersistedSessionUsageLedgerEntry? {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, session_id, provider_id, model, context_window, input_tokens,
                       cached_input_tokens, output_tokens, captured_at, source_turn_id, is_baseline
                FROM session_usage_ledger
                WHERE session_id = ?
                ORDER BY captured_at DESC, id DESC
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.toUsageLedgerEntry() else null
                }
            }
        }
    }

    /**
     * Opens one SQLite connection with foreign keys enabled.
     */
    private fun <T> withConnection(block: (Connection) -> T): T {
        return DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
            }
            block(connection)
        }
    }

    /**
     * Maps one SQLite row into a usage ledger record.
     */
    private fun java.sql.ResultSet.toUsageLedgerEntry(): PersistedSessionUsageLedgerEntry {
        return PersistedSessionUsageLedgerEntry(
            id = getString("id"),
            sessionId = getString("session_id"),
            providerId = getString("provider_id").orEmpty().ifBlank { "codex" },
            model = getString("model").orEmpty(),
            contextWindow = getInt("context_window"),
            inputTokens = getInt("input_tokens"),
            cachedInputTokens = getInt("cached_input_tokens"),
            outputTokens = getInt("output_tokens"),
            capturedAt = getLong("captured_at"),
            sourceTurnId = getString("source_turn_id").orEmpty().takeIf { it.isNotBlank() },
            isBaseline = getInt("is_baseline") == 1,
        )
    }
}

/**
 * Creates and migrates the usage ledger schema inside the shared chat database.
 */
internal object SessionUsageLedgerSchema {
    /**
     * Ensures the usage ledger table and indexes exist.
     */
    fun ensureAll(connection: Connection) {
        ensureTable(connection)
        ensureIndexes(connection)
    }

    /**
     * Ensures the usage ledger table exists.
     */
    private fun ensureTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS session_usage_ledger (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    provider_id TEXT NOT NULL DEFAULT 'codex',
                    model TEXT NOT NULL DEFAULT '',
                    context_window INTEGER NOT NULL DEFAULT 0,
                    input_tokens INTEGER NOT NULL DEFAULT 0,
                    cached_input_tokens INTEGER NOT NULL DEFAULT 0,
                    output_tokens INTEGER NOT NULL DEFAULT 0,
                    captured_at INTEGER NOT NULL,
                    source_turn_id TEXT NOT NULL DEFAULT '',
                    is_baseline INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * Ensures the usage ledger indexes exist.
     */
    private fun ensureIndexes(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_session_usage_ledger_session_time
                ON session_usage_ledger(session_id, captured_at ASC, id ASC)
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_session_usage_ledger_provider_time
                ON session_usage_ledger(provider_id, captured_at ASC, id ASC)
                """.trimIndent(),
            )
        }
    }
}
