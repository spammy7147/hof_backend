package app.spammy.hof.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

class AdventureWorkExecutionBindingMigrationTest {
    @Test
    fun h2MigrationPreservesExistingWorkAndOwnership() = verifyUpgrade(
        "jdbc:h2:mem:adventure_" + UUID.randomUUID() +
            ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "",
    )

    @Test
    @EnabledIfEnvironmentVariable(named = "HOF_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
    fun postgresMigrationPreservesExistingWorkAndOwnership() = verifyUpgrade(
        System.getenv("HOF_TEST_POSTGRES_URL"), "hof_test", "local-fixture-only",
    )

    private fun verifyUpgrade(url: String, user: String, password: String) {
        val schema = "adventure_" + UUID.randomUUID().toString().replace("-", "")
        val configuration = Flyway.configure().dataSource(url, user, password).schemas(schema)
            .locations("filesystem:src/main/resources/db/migration").cleanDisabled(false)
        try {
            configuration.target("60").load().migrate()
            val before = DriverManager.getConnection(url, user, password).use { connection ->
                connection.schema = schema
                connection.createStatement().use { statement ->
                    statement.executeUpdate("""
                        insert into hof_accounts(id, login_id, encrypted_password, created_at) values
                        (1, 'adventure-migration', 'encrypted', current_timestamp),
                        (2, 'quest-migration', 'encrypted', current_timestamp)
                    """.trimIndent())
                    statement.executeUpdate("""
                        insert into automation_entries(id, account_id, automation_type, singleton_type_marker,
                            priority, enabled, created_at, updated_at) values
                        (10, 1, 'ADVENTURE_MAP', null, 0, true, current_timestamp, current_timestamp),
                        (11, 1, 'ADVENTURE_MAP', null, 1, true, current_timestamp, current_timestamp),
                        (20, 2, 'QUEST', 'QUEST', 0, true, current_timestamp, current_timestamp)
                    """.trimIndent())
                    statement.executeUpdate("""
                        insert into automation_work_sessions(id, account_id, automation_entry_id, work_type,
                            target_key, status, running_slot, config_version, target_count, confirmed_count,
                            next_check_at, finished_at, material_name, material_missing,
                            created_at, updated_at, version) values
                        (100,1,10,'ADVENTURE_MAP','adventure_map/0003','RUNNING',1,'config-running',2,1,
                            null,null,null,null,current_timestamp,current_timestamp,7),
                        (101,1,11,'ADVENTURE_MAP','adventure_map/0004','WAITING_COOLDOWN',null,'config-wait',3,2,
                            current_timestamp,null,null,null,current_timestamp,current_timestamp,9),
                        (102,1,10,'ADVENTURE_MAP','adventure_map/0003','COMPLETED',null,'config-done',1,1,
                            null,current_timestamp,null,null,current_timestamp,current_timestamp,11),
                        (200,2,20,'QUEST','quest-material','WAITING_RESOURCE',null,'config-quest',4,2,
                            current_timestamp,null,'SilverIngot',2,current_timestamp,current_timestamp,13)
                    """.trimIndent())
                }
                snapshot(connection).also { rows ->
                    assertEquals(listOf("100", "101", "102", "200"), rows.map { it["id"] })
                    assertEquals(listOf("RUNNING", "WAITING_COOLDOWN", "COMPLETED", "WAITING_RESOURCE"),
                        rows.map { it["status"] })
                }
            }
            configuration.target("61").load().migrate()
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.schema = schema
                val migrated = snapshot(connection)
                assertEquals(before, migrated.map { it - "last_prepared_execution_identity" },
                    "기존 작업의 대상·진행도·대기 시각·완료 시각·revision을 그대로 보존한다.")
                assertTrue(migrated.all {
                    it.containsKey("last_prepared_execution_identity") && it["last_prepared_execution_identity"] == null
                }, "과거 작업에 준비 실행 식별자를 추정해서 채우지 않는다.")
                connection.createStatement().use { statement ->
                    statement.executeUpdate("update automation_work_sessions " +
                        "set last_prepared_execution_identity='prepared-adventure-next' where id=100")
                    assertEquals(listOf("prepared-adventure-next", null, null, null),
                        snapshot(connection).map { it["last_prepared_execution_identity"] })
                    assertFailsWith<SQLException> {
                        statement.executeUpdate("update automation_work_sessions " +
                            "set status='RUNNING', running_slot=1 where id=101")
                    }
                }
                assertEquals(before, snapshot(connection).map { it - "last_prepared_execution_identity" },
                    "새 연결 기록과 거부된 작업권 충돌은 기존 작업 데이터를 바꾸지 않는다.")
            }
        } finally {
            configuration.load().clean()
        }
    }

    private fun snapshot(connection: Connection): List<Map<String, String?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery("select * from automation_work_sessions order by id").use { rows ->
                val columns = (1..rows.metaData.columnCount).map(rows.metaData::getColumnLabel)
                buildList {
                    while (rows.next()) add(columns.associateWith(rows::getString))
                }
            }
        }
}
