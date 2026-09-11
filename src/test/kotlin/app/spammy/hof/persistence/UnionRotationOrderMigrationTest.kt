package app.spammy.hof.persistence

import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

class UnionRotationOrderMigrationTest {
    @Test
    fun `H2 기존 순환 위치와 완료된 유니온 행동 순서를 보존한다`() = verifyUpgrade(
        "jdbc:h2:mem:union_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "",
    )

    @Test
    @EnabledIfEnvironmentVariable(named = "HOF_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
    fun `PostgreSQL 기존 순환 위치와 완료된 유니온 행동 순서를 보존한다`() = verifyUpgrade(
        System.getenv("HOF_TEST_POSTGRES_URL"), "hof_test", "local-fixture-only",
    )

    private fun verifyUpgrade(url: String, user: String, password: String) {
        val schema = "union_${UUID.randomUUID().toString().replace("-", "")}"
        val configuration = Flyway.configure().dataSource(url, user, password).schemas(schema)
            .locations("filesystem:src/main/resources/db/migration").cleanDisabled(false)
        try {
            configuration.target("59").load().migrate()
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.schema = schema
                connection.createStatement().use { statement ->
                    statement.executeUpdate("insert into hof_accounts(id, login_id, encrypted_password, created_at) values " +
                        "(1, 'union-one', 'encrypted', current_timestamp), (2, 'union-two', 'encrypted', current_timestamp), " +
                        "(3, 'union-empty', 'encrypted', current_timestamp)")
                    statement.executeUpdate("insert into automation_entries(id, account_id, automation_type, singleton_type_marker, priority, enabled, created_at, updated_at) values " +
                        "(10, 1, 'UNION', 'UNION', 0, true, current_timestamp, current_timestamp), " +
                        "(11, 1, 'RAID', 'RAID', 1, true, current_timestamp, current_timestamp), " +
                        "(20, 2, 'UNION', 'UNION', 0, true, current_timestamp, current_timestamp), " +
                        "(30, 3, 'UNION', 'UNION', 0, true, current_timestamp, current_timestamp)")
                    statement.executeUpdate("insert into automation_rotation_states(automation_entry_id, current_target_key, updated_at) values " +
                        "(10, 'union:0005', current_timestamp), (11, 'raid-one', current_timestamp), " +
                        "(20, 'union:other', current_timestamp), (30, 'union:empty', current_timestamp)")
                    val cases = listOf(
                        listOf(100, 1, 10, "BATTLE_MAP", "SUCCEEDED"),
                        listOf(200, 1, 10, "BATTLE_MAP", "SUCCEEDED"),
                        listOf(300, 1, 10, "BATTLE_MAP", "FAILED"),
                        listOf(400, 1, 10, "RAID_TOWN", "SUCCEEDED"),
                        listOf(500, 1, 11, "BATTLE_MAP", "SUCCEEDED"),
                        listOf(600, 2, 20, "BATTLE_MAP", "SUCCEEDED"),
                    )
                    cases.forEach { (id, account, entry, kind, status) ->
                        statement.executeUpdate("insert into typed_automation_action_runs(id, account_id, automation_entry_id, execution_identity, action_kind, payload_json, action_fingerprint, status, lease_token, created_at, updated_at) " +
                            "values ($id, $account, $entry, 'migration-$id', '$kind', '{}', '${"a".repeat(64)}', '$status', 'fixture', current_timestamp, current_timestamp)")
                    }
                }
            }
            configuration.target("60").load().migrate()
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.schema = schema
                connection.createStatement().use { statement ->
                    fun rotations(): List<Pair<String, Long?>> = statement.executeQuery(
                        "select current_target_key, last_completed_action_id from automation_rotation_states order by automation_entry_id",
                    ).use { rows -> buildList {
                        while (rows.next()) add(rows.getString(1) to (rows.getObject(2) as Number?)?.toLong())
                    } }
                    val expected = listOf("union:0005" to 200L, "raid-one" to null, "union:other" to 600L, "union:empty" to null)
                    assertEquals(expected, rotations())
                    statement.executeUpdate("delete from typed_automation_action_runs")
                    assertEquals(expected, rotations(), "과거 행동 행의 정리로 순환 위치와 마지막 반영 순서가 사라지지 않는다.")
                }
            }
        } finally {
            configuration.load().clean()
        }
    }
}
