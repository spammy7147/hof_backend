package app.spammy.hof.persistence

import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.flywaydb.core.Flyway

class LegacyReconciliationBudgetMigrationTest {
    @Test
    fun `v38 bounds an existing reconciling action and leaves prepared actions fresh`() {
        val databaseName = "legacy_reconciliation_${UUID.randomUUID().toString().replace("-", "")}"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        val configuration = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")

        configuration.target("37").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "insert into hof_accounts(id,login_id,encrypted_password,created_at) " +
                        "values (1,'legacy-reconciliation','encrypted',timestamp with time zone '2026-08-23 09:00:00+00')",
                )
                statement.executeUpdate(
                    "insert into automation_entries(id,account_id,automation_type,priority,enabled,created_at,updated_at) " +
                        "values (10,1,'QUEST',0,true,current_timestamp,current_timestamp)",
                )
                statement.executeUpdate(
                    "insert into typed_automation_action_runs(" +
                        "id,account_id,automation_entry_id,execution_identity,action_kind,payload_json," +
                        "action_fingerprint,status,retry_attempt,lease_token,created_at,submitted_at,updated_at" +
                        ") values " +
                        "(100,1,10,'reconciling','QUEST_BATTLE','{}','${"1".repeat(64)}','RECONCILING',22,'r'," +
                        "timestamp with time zone '2026-08-23 09:53:00+00'," +
                        "timestamp with time zone '2026-08-23 09:53:05+00'," +
                        "timestamp with time zone '2026-08-23 10:02:00+00')," +
                        "(101,1,10,'prepared','QUEST_ACCEPT','{}','${"2".repeat(64)}','PREPARED',0,'p'," +
                        "timestamp with time zone '2026-08-23 10:03:00+00',null," +
                        "timestamp with time zone '2026-08-23 10:03:00+00')",
                )
            }
        }

        configuration.target("38").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select reconciliation_observation_count,reconciliation_first_pending_at " +
                        "from typed_automation_action_runs where id=100",
                ).use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt("reconciliation_observation_count"))
                    assertEquals(
                        Instant.parse("2026-08-23T09:53:05Z"),
                        rows.getTimestamp("reconciliation_first_pending_at").toInstant(),
                    )
                }
                statement.executeQuery(
                    "select reconciliation_observation_count,reconciliation_first_pending_at " +
                        "from typed_automation_action_runs where id=101",
                ).use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt("reconciliation_observation_count"))
                    assertNull(rows.getTimestamp("reconciliation_first_pending_at"))
                }
            }
        }
    }
}
