package app.spammy.hof.persistence

import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.flywaydb.core.Flyway

class AutomationWorkOwnershipMigrationTest {
    @Test
    fun `v39 keeps the latest running owner and rejects another owner for the account`() {
        val databaseName = "automation_work_owner_${UUID.randomUUID().toString().replace("-", "")}"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        val configuration = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")

        configuration.target("38").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "insert into hof_accounts(id,login_id,encrypted_password,created_at) " +
                        "values (1,'work-owner-migration','encrypted',current_timestamp)",
                )
                statement.executeUpdate(
                    "insert into automation_entries(id,account_id,automation_type,priority,enabled,created_at,updated_at) " +
                        "values (10,1,'QUEST',0,true,current_timestamp,current_timestamp)",
                )
                statement.executeUpdate(
                    "insert into automation_work_sessions(" +
                        "id,account_id,automation_entry_id,work_type,target_key,status,config_version," +
                        "confirmed_count,created_at,updated_at" +
                        ") values " +
                        "(100,1,10,'QUEST','quest-old','RUNNING','v1',2,current_timestamp,current_timestamp)," +
                        "(101,1,10,'QUEST','quest-latest','RUNNING','v1',4,current_timestamp,current_timestamp)," +
                        "(102,1,10,'QUEST','quest-wait','WAITING_COOLDOWN','v1',1,current_timestamp,current_timestamp)",
                )
            }
        }

        configuration.target("39").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select id,status,running_slot from automation_work_sessions order by id",
                ).use { rows ->
                    rows.next()
                    assertEquals(100, rows.getLong("id"))
                    assertEquals("YIELDED_PRIORITY", rows.getString("status"))
                    assertNull(rows.getObject("running_slot"))
                    rows.next()
                    assertEquals(101, rows.getLong("id"))
                    assertEquals("RUNNING", rows.getString("status"))
                    assertEquals(1, rows.getInt("running_slot"))
                    rows.next()
                    assertEquals(102, rows.getLong("id"))
                    assertEquals("WAITING_COOLDOWN", rows.getString("status"))
                    assertNull(rows.getObject("running_slot"))
                }

                assertFailsWith<SQLException> {
                    statement.executeUpdate(
                        "insert into automation_work_sessions(" +
                            "id,account_id,automation_entry_id,work_type,target_key,status,running_slot,config_version," +
                            "confirmed_count,created_at,updated_at" +
                            ") values " +
                            "(103,1,10,'QUEST','quest-conflict','RUNNING',1,'v1',0,current_timestamp,current_timestamp)",
                    )
                }
            }
        }
    }
}
