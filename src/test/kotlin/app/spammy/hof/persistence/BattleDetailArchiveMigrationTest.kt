package app.spammy.hof.persistence

import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway

class BattleDetailArchiveMigrationTest {
    @Test
    fun `과거 응답 HTML과 패배 기록을 보존하고 상세가 아닌 요약으로 전환한다`() {
        val url = "jdbc:h2:mem:detail-upgrade-${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val configuration = Flyway.configure().dataSource(url, "sa", "")
        configuration.target("63").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("insert into hof_accounts(id, login_id, encrypted_password, created_at) values (1, 'detail-upgrade', 'test', current_timestamp)")
                statement.executeUpdate("""insert into battle_logs(id, account_id, category_id, map_code, map_name_snapshot,
                    outcome, title, created_at, raw_log_url, archived_html) values
                    (1, 1, 'battle_map', 'old-map', '과거 맵', 'DEFEAT', '적군 승리', current_timestamp,
                    'https://hof.zerosic.com/index.php?log=123456', '<p>과거 패배 응답 요약</p>')""")
            }
        }
        configuration.target("64").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select * from battle_logs").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("DEFEAT", rows.getString("outcome"))
                    assertEquals("<p>과거 패배 응답 요약</p>", rows.getString("archived_html"))
                    assertEquals("SUMMARY", rows.getString("detail_archive_status"))
                    assertNull(rows.getString("detail_html"))
                    assertEquals(0, rows.getInt("detail_archive_attempts"))
                    assertNull(rows.getTimestamp("detail_archive_next_at"))
                    assertEquals("https://hof.zerosic.com/index.php?log=123456", rows.getString("raw_log_url"))
                    assertEquals(false, rows.next())
                }
            }
        }
    }
}
