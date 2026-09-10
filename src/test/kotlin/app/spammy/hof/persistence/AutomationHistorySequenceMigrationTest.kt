package app.spammy.hof.persistence

import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

class AutomationHistorySequenceMigrationTest {
    @Test
    fun `H2 기존 이력의 빈 순번과 빈 판단을 보존한 뒤 다음 순서를 예약한다`() = verifyUpgrade(
        "jdbc:h2:mem:history_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "",
    )

    @Test
    @EnabledIfEnvironmentVariable(named = "HOF_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
    fun `PostgreSQL 기존 이력의 빈 순번과 빈 판단을 보존한 뒤 다음 순서를 예약한다`() = verifyUpgrade(
        System.getenv("HOF_TEST_POSTGRES_URL"), "hof_test", "local-fixture-only",
    )

    private fun verifyUpgrade(url: String, user: String, password: String) {
        val schema = "history_${UUID.randomUUID().toString().replace("-", "")}"
        val configuration = Flyway.configure().dataSource(url, user, password).schemas(schema)
            .locations("filesystem:src/main/resources/db/migration").cleanDisabled(false)
        try {
            configuration.target("58").load().migrate()
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.schema = schema
                connection.createStatement().use { statement ->
                    statement.executeUpdate("insert into hof_accounts(id, login_id, encrypted_password, created_at) values (1, 'history-upgrade', 'encrypted', current_timestamp)")
                    statement.executeUpdate("insert into automation_decision_cycles(id, account_id, result, started_at, finished_at) values (1, 1, 'IDLE', current_timestamp, current_timestamp), (2, 1, 'IDLE', current_timestamp, current_timestamp)")
                    statement.executeUpdate("insert into automation_decision_events(decision_cycle_id, sequence_no, event_kind, reason_code, message, occurred_at) values (1, 0, 'ACTION_STARTED', 'STARTED', 'start', current_timestamp), (1, 3, 'ACTION_SUCCEEDED', 'APPLIED', 'done', current_timestamp)")
                }
            }
            configuration.target("59").load().migrate()
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.schema = schema
                connection.createStatement().use { statement ->
                    statement.executeQuery("select next_event_sequence from automation_decision_cycles order by id").use { rows ->
                        rows.next(); assertEquals(4, rows.getInt(1))
                        rows.next(); assertEquals(0, rows.getInt(1))
                    }
                    statement.executeQuery("select sequence_no from automation_decision_events order by sequence_no").use { rows ->
                        rows.next(); assertEquals(0, rows.getInt(1))
                        rows.next(); assertEquals(3, rows.getInt(1))
                    }
                    assertFailsWith<SQLException> { statement.executeUpdate("update automation_decision_cycles set next_event_sequence = -1 where id = 1") }
                }
            }
        } finally {
            configuration.load().clean()
        }
    }
}
