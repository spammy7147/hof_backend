package app.spammy.hof.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway

class StableQuestIdentityMigrationTest {
    @Test
    fun `v10 resets only quest automation and adds display identity`() {
        val databaseName = "quest_identity_${UUID.randomUUID().toString().replace("-", "")}"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")
            .target("9")
            .load()
            .migrate()
        DriverManager.getConnection(url, "sa", "").use(::seedLegacyAutomation)
        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")
            .target("10")
            .load()
            .migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            assertFalse(connection.booleanValue("select enabled from automation_entries where id = 10"))
            assertTrue(connection.booleanValue("select enabled from automation_entries where id = 20"))
            assertEquals(0, connection.count("quest_automation_selections"))
            assertEquals(0, connection.count("quest_automation_cycles"))
            assertEquals(0, connection.count("quest_map_execution_counters"))
            assertEquals(0, connection.count("quest_automation_processed_results"))
            assertEquals(1, connection.count("battle_automation_maps"))
            assertEquals("STOPPED", connection.textValue("select status from automation_work_sessions where id = 100"))
            assertEquals("RUNNING", connection.textValue("select status from automation_work_sessions where id = 200"))
            assertEquals("FAILED", connection.textValue("select status from typed_automation_action_runs where id = 1000"))
            assertEquals("PREPARED", connection.textValue("select status from typed_automation_action_runs where id = 2000"))
            assertTrue(connection.requiredColumn("quest_automation_selections", "display_code"))
            assertTrue(connection.requiredColumn("quest_automation_selections", "quest_name"))
        }
    }

    private fun seedLegacyAutomation(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "insert into hof_accounts(id, login_id, encrypted_password, created_at) " +
                    "values (1, 'migration-test', 'encrypted', current_timestamp)",
            )
            statement.executeUpdate(
                "insert into automation_entries(id, account_id, automation_type, priority, enabled, created_at, updated_at) values " +
                    "(10, 1, 'QUEST', 0, true, current_timestamp, current_timestamp), " +
                    "(20, 1, 'BATTLE_MAP', 1, true, current_timestamp, current_timestamp)",
            )
            statement.executeUpdate(
                "insert into quest_automation_selections(id, automation_entry_id, quest_code, enabled, source_order) " +
                    "values (30, 10, 'legacy-action-number', true, 0)",
            )
            statement.executeUpdate(
                "insert into battle_automation_maps(id, automation_entry_id, category_id, map_code, daily_target_count, " +
                    "preset_mode, party_preset_id, execution_order) values " +
                    "(40, 20, 'battle_map', 'gb0', 1, 'PRIMARY', null, 0)",
            )
            statement.executeUpdate(
                "insert into quest_automation_cycles(id, account_id, quest_code, current_cycle) " +
                    "values (50, 1, 'legacy-action-number', 1)",
            )
            statement.executeUpdate(
                "insert into quest_map_execution_counters(id, account_id, quest_code, quest_cycle, mission_key, " +
                    "category_id, map_code, successful_runs) values " +
                    "(60, 1, 'legacy-action-number', '1', 'mission', 'battle_map', 'gb0', 1)",
            )
            statement.executeUpdate(
                "insert into quest_automation_processed_results(id, account_id, result_kind, result_identity, " +
                    "action_fingerprint, result_value, processed_at) values " +
                    "(70, 1, 'ACCEPT', 'legacy-result', '${"0".repeat(64)}', '1', current_timestamp)",
            )
            statement.executeUpdate(
                "insert into automation_work_sessions(id, account_id, automation_entry_id, work_type, target_key, " +
                    "status, config_version, confirmed_count, created_at, updated_at) values " +
                    "(100, 1, 10, 'QUEST', 'legacy-action-number', 'RUNNING', 'v1', 0, current_timestamp, current_timestamp), " +
                    "(200, 1, 20, 'BATTLE_MAP', 'battle_map:gb0', 'RUNNING', 'v1', 0, current_timestamp, current_timestamp)",
            )
            statement.executeUpdate(
                "insert into typed_automation_action_runs(id, account_id, automation_entry_id, execution_identity, " +
                    "action_kind, schema_version, payload_json, action_fingerprint, status, retry_attempt, lease_token, " +
                    "created_at, updated_at) values " +
                    "(1000, 1, 10, 'quest-action', 'QUEST_ACCEPT', 1, '{}', '${"1".repeat(64)}', 'PREPARED', 0, 'q', current_timestamp, current_timestamp), " +
                    "(2000, 1, 20, 'battle-action', 'BATTLE_REQUEST', 1, '{}', '${"2".repeat(64)}', 'PREPARED', 0, 'b', current_timestamp, current_timestamp)",
            )
        }
    }
}

private fun Connection.count(table: String): Int =
    createStatement().use { statement ->
        statement.executeQuery("select count(*) from $table").use { rows -> rows.next(); rows.getInt(1) }
    }

private fun Connection.booleanValue(sql: String): Boolean =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> rows.next(); rows.getBoolean(1) }
    }

private fun Connection.textValue(sql: String): String =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> rows.next(); rows.getString(1) }
    }

private fun Connection.requiredColumn(table: String, column: String): Boolean =
    metaData.getColumns(null, null, table, column).use { rows -> rows.next() && rows.getInt("NULLABLE") == 0 }
