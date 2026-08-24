package app.spammy.hof.persistence

import java.sql.DriverManager
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.flywaydb.core.Flyway

class MapAutomationGroupsMigrationTest {
    @Test
    fun `map group migrations preserve settings enforce ownership and allow only map types to repeat`() {
        val databaseName = "map_groups_${UUID.randomUUID().toString().replace("-", "") }"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        val configuration = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")

        configuration.target("41").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "insert into hof_accounts(id,login_id,encrypted_password,created_at) " +
                        "values (1,'map-group-migration','encrypted',current_timestamp)",
                )
                statement.executeUpdate(
                    "insert into hof_accounts(id,login_id,encrypted_password,created_at) " +
                        "values (2,'other-map-group-account','encrypted',current_timestamp)",
                )
                statement.executeUpdate(
                    "insert into automation_entries(id,account_id,automation_type,priority,enabled,created_at,updated_at) " +
                        "values (10,1,'BATTLE_MAP',0,true,current_timestamp,current_timestamp)," +
                        "(20,1,'QUEST',1,false,current_timestamp,current_timestamp)," +
                        "(30,2,'BATTLE_MAP',0,false,current_timestamp,current_timestamp)",
                )
                statement.executeUpdate(
                    "insert into battle_automation_maps(" +
                        "id,automation_entry_id,category_id,map_code,daily_target_count,preset_mode,execution_order" +
                        ") values (100,10,'battle_map','map-1',3,'PRIMARY',0)",
                )
                statement.executeUpdate(
                    "insert into battle_automation_daily_progress(" +
                        "id,account_id,progress_date,category_id,map_code,source,successful_runs,updated_at" +
                        ") values (200,1,current_date,'battle_map','map-1','BATTLE_MAP_AUTOMATION',2,current_timestamp)",
                )
            }
        }

        configuration.target("43").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select singleton_type_marker,display_name,settings_revision from automation_entries where id = 10",
                ).use { row ->
                    row.next()
                    assertNull(row.getString("singleton_type_marker"))
                    assertNull(row.getString("display_name"))
                    assertEquals(0, row.getLong("settings_revision"))
                }
                assertEquals(1, connection.rowCount("battle_automation_maps"))
                assertEquals(2, connection.intValue("select successful_runs from battle_automation_daily_progress where id = 200"))
                assertEquals(1, connection.intValue("select account_id from battle_automation_maps where id = 100"))

                statement.executeUpdate(
                    "insert into automation_entries(id,account_id,automation_type,priority,enabled,created_at,updated_at," +
                        "singleton_type_marker,settings_revision) values " +
                        "(11,1,'BATTLE_MAP',2,false,current_timestamp,current_timestamp,null,0)," +
                        "(12,1,'ADVENTURE_MAP',3,false,current_timestamp,current_timestamp,null,0)," +
                        "(13,1,'ADVENTURE_MAP',4,false,current_timestamp,current_timestamp,null,0)",
                )
                assertFailsWith<SQLException> {
                    statement.executeUpdate(
                        "insert into automation_entries(id,account_id,automation_type,priority,enabled,created_at,updated_at," +
                            "singleton_type_marker,settings_revision) values " +
                            "(21,1,'QUEST',5,false,current_timestamp,current_timestamp,'QUEST',0)",
                    )
                }
                assertFailsWith<SQLException> {
                    statement.executeUpdate(
                        "insert into automation_entries(id,account_id,automation_type,priority,enabled,created_at,updated_at," +
                            "singleton_type_marker,settings_revision) values " +
                            "(22,1,'HOME_QUEST',6,false,current_timestamp,current_timestamp,null,0)",
                    )
                }
                assertFailsWith<SQLException> {
                    statement.executeUpdate(
                        "insert into battle_automation_maps(" +
                            "id,automation_entry_id,account_id,category_id,map_code,daily_target_count,preset_mode,execution_order" +
                            ") values (101,30,1,'battle_map','wrong-owner',1,'PRIMARY',0)",
                    )
                }
            }
        }
    }
}

private fun Connection.rowCount(table: String): Int =
    createStatement().use { statement ->
        statement.executeQuery("select count(*) from $table").use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }

private fun Connection.intValue(query: String): Int =
    createStatement().use { statement ->
        statement.executeQuery(query).use { rows ->
            rows.next()
            rows.getInt(1)
        }
    }
