package app.spammy.hof.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway

class StoredActionSchemaRemovalMigrationTest {
    @Test
    fun `v11 resets stored actions and conservatively completes only uncertain battle work`() {
        val databaseName = "stored_action_schema_${UUID.randomUUID().toString().replace("-", "")}"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")
            .target("10")
            .load()
            .migrate()
        DriverManager.getConnection(url, "sa", "").use(::seedStoredActions)

        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")
            .load()
            .migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            assertEquals(0, connection.rowCount("typed_automation_action_runs"))
            assertFalse(connection.hasColumn("typed_automation_action_runs", "schema_version"))
            assertEquals("COMPLETED", connection.text("select status from automation_work_sessions where id = 200"))
            assertEquals("STOPPED", connection.text("select status from automation_work_sessions where id = 199"))
            assertEquals("RUNNING", connection.text("select status from automation_work_sessions where id = 201"))
            assertEquals("RUNNING", connection.text("select status from automation_work_sessions where id = 202"))
            assertEquals("RUNNING", connection.text("select status from automation_work_sessions where id = 203"))
            assertEquals("PAUSED", connection.text("select lifecycle_status from typed_automation_runtime_states where account_id = 1"))
            assertNull(connection.nullableLong("select stop_action_id from typed_automation_runtime_states where account_id = 1"))
            assertNull(connection.nullableText("select last_error from typed_automation_runtime_states where account_id = 1"))
            assertEquals(4, connection.rowCount("automation_entries"))
            assertEquals(1, connection.rowCount("battle_automation_processed_results"))
            assertTrue(connection.hasColumn("typed_automation_action_runs", "payload_json"))
        }
    }

    private fun seedStoredActions(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "insert into hof_accounts(id, login_id, encrypted_password, created_at) values " +
                    "(1, 'battle-uncertain', 'encrypted', current_timestamp), " +
                    "(2, 'battle-prepared', 'encrypted', current_timestamp), " +
                    "(3, 'quest-uncertain', 'encrypted', current_timestamp), " +
                    "(4, 'adventure-uncertain', 'encrypted', current_timestamp)",
            )
            statement.executeUpdate(
                "insert into automation_entries(id, account_id, automation_type, priority, enabled, created_at, updated_at) values " +
                    "(10, 1, 'BATTLE_MAP', 0, true, current_timestamp, current_timestamp), " +
                    "(20, 2, 'BATTLE_MAP', 0, true, current_timestamp, current_timestamp), " +
                    "(30, 3, 'QUEST', 0, true, current_timestamp, current_timestamp), " +
                    "(40, 4, 'ADVENTURE_MAP', 0, true, current_timestamp, current_timestamp)",
            )
            statement.executeUpdate(
                "insert into automation_work_sessions(id, account_id, automation_entry_id, work_type, target_key, " +
                    "status, config_version, confirmed_count, created_at, updated_at, finished_at) values " +
                    "(199, 1, 10, 'BATTLE_MAP', 'battle_map/old', 'STOPPED', 'v1', 0, dateadd('MINUTE', -2, current_timestamp), dateadd('MINUTE', -2, current_timestamp), dateadd('MINUTE', -2, current_timestamp)), " +
                    "(200, 1, 10, 'BATTLE_MAP', 'battle_map/gb0', 'STOPPED', 'v1', 0, dateadd('MINUTE', -1, current_timestamp), dateadd('MINUTE', -1, current_timestamp), dateadd('MINUTE', -1, current_timestamp)), " +
                    "(201, 2, 20, 'BATTLE_MAP', 'battle_map/gb1', 'RUNNING', 'v1', 0, current_timestamp, current_timestamp, null), " +
                    "(202, 3, 30, 'QUEST', 'quest', 'RUNNING', 'v1', 0, current_timestamp, current_timestamp, null), " +
                    "(203, 4, 40, 'ADVENTURE_MAP', 'adventure/ad0', 'RUNNING', 'v1', 0, current_timestamp, current_timestamp, null)",
            )
            statement.executeUpdate(
                "insert into typed_automation_action_runs(id, account_id, automation_entry_id, execution_identity, " +
                    "action_kind, schema_version, payload_json, action_fingerprint, status, retry_attempt, lease_token, " +
                    "created_at, updated_at) values " +
                    "(1000, 1, 10, 'battle-uncertain', 'BATTLE_MAP', 1, '{}', '${"1".repeat(64)}', 'RECONCILING', 0, 'a', current_timestamp, current_timestamp), " +
                    "(1001, 2, 20, 'battle-prepared', 'BATTLE_MAP', 1, '{}', '${"2".repeat(64)}', 'PREPARED', 0, 'b', current_timestamp, current_timestamp), " +
                    "(1002, 3, 30, 'quest-uncertain', 'QUEST_BATTLE', 1, '{}', '${"3".repeat(64)}', 'RECONCILING', 0, 'c', current_timestamp, current_timestamp), " +
                    "(1003, 4, 40, 'adventure-uncertain', 'ADVENTURE_MAP', 1, '{}', '${"4".repeat(64)}', 'RECONCILING', 0, 'd', current_timestamp, current_timestamp)",
            )
            statement.executeUpdate(
                "insert into typed_automation_runtime_states(account_id, lifecycle_status, stop_reason, retry_attempt, " +
                    "stop_action_id, last_error, created_at, updated_at, version) values " +
                    "(1, 'STOPPED', 'FATAL', 0, 1000, 'Stored typed action integrity check failed.', current_timestamp, current_timestamp, 0)",
            )
            statement.executeUpdate(
                "insert into battle_automation_processed_results(id, account_id, result_identity, execution_identity, " +
                    "action_fingerprint, outcome_fingerprint, victory_count, processed_at) values " +
                    "(300, 1, 'result', 'previous-execution', '${"5".repeat(64)}', '${"6".repeat(64)}', 1, current_timestamp)",
            )
        }
    }
}

private fun Connection.rowCount(table: String): Int =
    createStatement().use { statement ->
        statement.executeQuery("select count(*) from $table").use { rows -> rows.next(); rows.getInt(1) }
    }

private fun Connection.text(sql: String): String =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> rows.next(); rows.getString(1) }
    }

private fun Connection.nullableText(sql: String): String? =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> rows.next(); rows.getString(1) }
    }

private fun Connection.nullableLong(sql: String): Long? =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            rows.next()
            rows.getLong(1).takeUnless { rows.wasNull() }
        }
    }

private fun Connection.hasColumn(table: String, column: String): Boolean =
    metaData.getColumns(null, null, table, column).use { rows -> rows.next() }
