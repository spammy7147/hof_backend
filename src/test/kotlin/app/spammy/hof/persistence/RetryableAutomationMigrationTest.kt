package app.spammy.hof.persistence

import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.flywaydb.core.Flyway

class RetryableAutomationMigrationTest {
    @Test
    fun `v20 resumes existing automatic stops before enforcing retryable lifecycle`() {
        val databaseName = "retryable_automation_${UUID.randomUUID().toString().replace("-", "")}"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        val configuration = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")

        configuration.target("19").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "insert into hof_accounts(id,login_id,encrypted_password,created_at) " +
                        "values (1,'retryable-migration','encrypted',current_timestamp)",
                )
                statement.executeUpdate(
                    "insert into typed_automation_runtime_states(" +
                        "account_id,lifecycle_status,stop_reason,retry_attempt,created_at,updated_at,version" +
                        ") values (1,'STOPPED','FATAL',3,current_timestamp,current_timestamp,0)",
                )
                statement.executeUpdate(
                    "insert into adventure_daily_preflight_states(" +
                        "account_id,refresh_date,failed_attempts,stop_reason,updated_at" +
                        ") values (1,current_date,3,'AUTHENTICATION',current_timestamp)",
                )
            }
        }

        configuration.target("20").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select lifecycle_status,stop_reason,retry_attempt,wait_reason from typed_automation_runtime_states where account_id=1",
                ).use { rows ->
                    rows.next()
                    assertEquals("RUNNING", rows.getString("lifecycle_status"))
                    assertEquals("FATAL", rows.getString("stop_reason"))
                    assertEquals(0, rows.getInt("retry_attempt"))
                    assertEquals("HOF_CONNECTION", rows.getString("wait_reason"))
                }
                statement.executeQuery(
                    "select failed_attempts,stop_reason from adventure_daily_preflight_states where account_id=1",
                ).use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt("failed_attempts"))
                    assertNull(rows.getString("stop_reason"))
                }
            }
        }
    }
}
