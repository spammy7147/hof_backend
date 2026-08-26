package app.spammy.hof.persistence

import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway

class CaptchaPassMaintenanceMigrationTest {
    @Test
    fun `v47 jitters the first check only for existing accounts with an active app session`() {
        val databaseName = "captcha_pass_${UUID.randomUUID().toString().replace("-", "")}"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        val configuration = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")

        configuration.target("46").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "insert into hof_accounts(id,login_id,encrypted_password,created_at) values " +
                        "(301,'active-pass-migration','encrypted',current_timestamp)," +
                        "(302,'inactive-pass-migration','encrypted',current_timestamp)",
                )
                statement.executeUpdate(
                    "insert into refresh_tokens(" +
                        "account_id,token_hash,family_id,client_type,created_at,expires_at" +
                        ") values (301,'${"a".repeat(64)}','${UUID.randomUUID()}','NATIVE'," +
                        "current_timestamp,dateadd('DAY',1,current_timestamp))",
                )
            }
        }

        configuration.target("47").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select auth_suspended,next_refresh_at,updated_at " +
                        "from captcha_pass_maintenance where account_id=301",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(false, rows.getBoolean("auth_suspended"))
                    assertEquals(
                        1,
                        java.time.Duration.between(
                            rows.getTimestamp("updated_at").toInstant(),
                            rows.getTimestamp("next_refresh_at").toInstant(),
                        ).seconds,
                    )
                }
                statement.executeQuery(
                    "select auth_suspended,next_refresh_at from captcha_pass_maintenance where account_id=302",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(true, rows.getBoolean("auth_suspended"))
                    assertNull(rows.getTimestamp("next_refresh_at"))
                }
            }
        }
    }
}
