package app.spammy.hof.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

class ShadowConvergenceCheckpointMigrationTest {
    @Test
    fun h2MigrationPreservesComparisonsAndOrdersLateCheckpoints() = verifyUpgrade(
        "jdbc:h2:mem:shadow_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "sa", "",
    )

    @Test
    @EnabledIfEnvironmentVariable(named = "HOF_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
    fun postgresMigrationPreservesComparisonsAndOrdersLateCheckpoints() = verifyUpgrade(
        System.getenv("HOF_TEST_POSTGRES_URL"), "hof_test", "local-fixture-only",
    )

    private fun verifyUpgrade(url: String, user: String, password: String) {
        val schema = "shadow_${UUID.randomUUID().toString().replace("-", "")}"
        val configuration = Flyway.configure().dataSource(url, user, password).schemas(schema)
            .locations("filesystem:src/main/resources/db/migration").cleanDisabled(false)
        try {
            configuration.target("61").load().migrate()
            val before = DriverManager.getConnection(url, user, password).use { connection ->
                connection.schema = schema
                connection.createStatement().use { statement ->
                    statement.executeUpdate("insert into hof_accounts(id, login_id, encrypted_password, created_at) " +
                        "values (1, 'shadow-upgrade', 'encrypted', current_timestamp)")
                }
                insertComparison(connection, "old-row", "2026-09-11 00:00:10+00", "HELD")
                snapshot(connection).single()
            }
            configuration.target("62").load().migrate()
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.schema = schema
                val migrated = snapshot(connection).single()
                assertEquals(before, migrated.filterKeys { it in before }, "이전 비교 결과와 원래 관측 시각을 보존한다.")
                assertNull(migrated["successful_observation_count"], "과거 비교 행의 관측 횟수를 추정하지 않는다.")
                assertNull(migrated["checkpoint_updated_at"])
                assertNull(migrated["selection_entry_id"])
                assertNull(migrated["baseline_fingerprint_hash"])
                assertNull(migrated["observation_only"])
                insertComparison(connection, "late-original", "2026-09-11 00:00:00+00", "APPLIED")
                connection.createStatement().use { statement ->
                    statement.executeUpdate("update automation_convergence_shadow_evaluations " +
                        "set successful_observation_count=5, first_pending_at=timestamp with time zone '2026-09-11 00:00:01+00', " +
                        "checkpoint_updated_at=timestamp with time zone '2026-09-11 00:00:00+00', " +
                        "selection_entry_id=7, baseline_fingerprint_hash='${"a".repeat(64)}', observation_only=false " +
                        "where id='late-original'")
                    statement.executeQuery("select id, recorded_sequence from automation_convergence_shadow_evaluations " +
                        "order by recorded_sequence").use { rows ->
                        assertTrue(rows.next()); assertEquals("old-row", rows.getString(1)); val first = rows.getLong(2)
                        assertTrue(rows.next()); assertEquals("late-original", rows.getString(1)); assertTrue(rows.getLong(2) > first)
                        assertEquals(false, rows.next())
                    }
                    statement.executeQuery("select selection_entry_id, baseline_fingerprint_hash, observation_only " +
                        "from automation_convergence_shadow_evaluations where id='late-original'").use { row ->
                        assertTrue(row.next())
                        assertEquals(7L, row.getLong(1))
                        assertEquals("a".repeat(64), row.getString(2))
                        assertEquals(false, row.getBoolean(3))
                    }
                }
                assertEquals(before, snapshot(connection).single { it["id"] == "old-row" }.filterKeys { it in before })
                assertEquals("5", snapshot(connection).single { it["id"] == "late-original" }["successful_observation_count"])
            }
        } finally {
            configuration.load().clean()
        }
    }

    private fun insertComparison(connection: Connection, id: String, observedAt: String, result: String) {
        connection.prepareStatement("""
            insert into automation_convergence_shadow_evaluations(
                id, account_id, execution_identity_hash, action_kind, scope_kind, scope_key_hash,
                evidence_kind, evidence_completeness, response_shape_fingerprint, sanitized_snippet,
                legacy_decision, legacy_reason_code, new_result, new_reason_code,
                result_differs, reason_differs, shape_differs, completeness_differs,
                policy_version, build_version, created_at, expires_at)
            values (?, 1, 'execution-hash', 'QUEST_ACCEPT', 'QUEST_TARGET', 'scope-hash',
                'SameState', 'COMPLETE', 'shape-hash', 'fixture', 'HELD', 'fixture', ?, 'fixture',
                false, false, false, false, 'automation-action-convergence-v1', 'fixture',
                cast(? as timestamp with time zone), timestamp with time zone '2026-10-11 00:00:00+00')
        """.trimIndent()).use { statement ->
            statement.setString(1, id); statement.setString(2, result); statement.setString(3, observedAt)
            statement.executeUpdate()
        }
    }

    private fun snapshot(connection: Connection): List<Map<String, String?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery("select * from automation_convergence_shadow_evaluations order by id").use { rows ->
                val columns = (1..rows.metaData.columnCount).map(rows.metaData::getColumnLabel)
                buildList { while (rows.next()) add(columns.associateWith(rows::getString)) }
            }
        }
}
