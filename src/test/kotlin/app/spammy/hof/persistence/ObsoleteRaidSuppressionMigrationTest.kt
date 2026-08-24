package app.spammy.hof.persistence

import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.flywaydb.core.Flyway

class ObsoleteRaidSuppressionMigrationTest {
    @Test
    fun `v41 releases only raid holds disproved by a later successful raid action`() {
        val databaseName = "obsolete_raid_suppression_${UUID.randomUUID().toString().replace("-", "")}"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        val configuration = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")

        configuration.target("40").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "insert into hof_accounts(id,login_id,encrypted_password,created_at) " +
                        "values (1,'raid-suppression-migration','encrypted',current_timestamp)",
                )
                statement.executeUpdate(
                    "insert into automation_entries(id,account_id,automation_type,priority,enabled,created_at,updated_at) " +
                        "values (10,1,'RAID',0,true,current_timestamp,current_timestamp)," +
                        "(11,1,'QUEST',1,true,current_timestamp,current_timestamp)",
                )
                statement.executeUpdate(
                    "insert into automation_action_attempts(" +
                        "id,account_id,automation_entry_id,execution_identity,action_kind,scope_kind,scope_key," +
                        "policy_version,baseline_fingerprint,observation_only,created_at,submitted_at" +
                        ") values " +
                        "(100,1,10,'raid-obsolete','RAID_REGISTER','RAID_ENTRY','Raid001','v1','baseline-a',false," +
                        "timestamp with time zone '2026-08-23 09:59:00+00',timestamp with time zone '2026-08-23 09:59:01+00')," +
                        "(101,1,10,'raid-still-current','RAID_REGISTER','RAID_ENTRY','Raid001','v1','baseline-b',false," +
                        "timestamp with time zone '2026-08-23 10:19:00+00',timestamp with time zone '2026-08-23 10:19:01+00')," +
                        "(102,1,11,'quest-held','QUEST_ACCEPT','QUEST_TARGET','quest-1','v1','baseline-c',false," +
                        "timestamp with time zone '2026-08-23 09:59:00+00',timestamp with time zone '2026-08-23 09:59:01+00')",
                )
                statement.executeUpdate(
                    "insert into automation_action_convergences(" +
                        "id,attempt_id,account_id,scope_kind,scope_key,result,successful_observation_count," +
                        "reason_code,finished_at,updated_at" +
                        ") values " +
                        "(100,100,1,'RAID_ENTRY','Raid001','HELD',5,'PENDING_BUDGET_EXHAUSTED'," +
                        "timestamp with time zone '2026-08-23 10:00:00+00',timestamp with time zone '2026-08-23 10:00:00+00')," +
                        "(101,101,1,'RAID_ENTRY','Raid001','HELD',5,'PENDING_BUDGET_EXHAUSTED'," +
                        "timestamp with time zone '2026-08-23 10:20:00+00',timestamp with time zone '2026-08-23 10:20:00+00')," +
                        "(102,102,1,'QUEST_TARGET','quest-1','HELD',5,'PENDING_BUDGET_EXHAUSTED'," +
                        "timestamp with time zone '2026-08-23 10:00:00+00',timestamp with time zone '2026-08-23 10:00:00+00')",
                )
                statement.executeUpdate(
                    "insert into typed_automation_action_runs(" +
                        "id,account_id,automation_entry_id,execution_identity,action_kind,payload_json," +
                        "action_fingerprint,status,retry_attempt,lease_token,created_at,submitted_at,finished_at,updated_at" +
                        ") values " +
                        "(200,1,10,'later-raid-success','RAID_TOWN','{\"targetRaidId\":\"Raid001\",\"raidId\":\"Raid001\"}'," +
                        "'${"1".repeat(64)}','SUCCEEDED',0,'r'," +
                        "timestamp with time zone '2026-08-23 10:09:00+00'," +
                        "timestamp with time zone '2026-08-23 10:09:01+00'," +
                        "timestamp with time zone '2026-08-23 10:10:00+00'," +
                        "timestamp with time zone '2026-08-23 10:10:00+00')," +
                        "(202,1,10,'other-raid-success','RAID_TOWN','{\"targetRaidId\":\"Raid002\",\"raidId\":\"Raid002\"}'," +
                        "'${"3".repeat(64)}','SUCCEEDED',0,'o'," +
                        "timestamp with time zone '2026-08-23 10:29:00+00'," +
                        "timestamp with time zone '2026-08-23 10:29:01+00'," +
                        "timestamp with time zone '2026-08-23 10:30:00+00'," +
                        "timestamp with time zone '2026-08-23 10:30:00+00')," +
                        "(201,1,11,'later-quest-success','QUEST_ACCEPT','{}','${"2".repeat(64)}','SUCCEEDED',0,'q'," +
                        "timestamp with time zone '2026-08-23 10:09:00+00'," +
                        "timestamp with time zone '2026-08-23 10:09:01+00'," +
                        "timestamp with time zone '2026-08-23 10:10:00+00'," +
                        "timestamp with time zone '2026-08-23 10:10:00+00')",
                )
            }
        }

        configuration.target("41").load().migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select id,suppression_released_at from automation_action_convergences order by id",
                ).use { rows ->
                    rows.next()
                    assertEquals(100, rows.getLong("id"))
                    assertEquals(
                        Instant.parse("2026-08-23T10:10:00Z"),
                        rows.getTimestamp("suppression_released_at").toInstant(),
                    )
                    rows.next()
                    assertEquals(101, rows.getLong("id"))
                    assertNull(rows.getTimestamp("suppression_released_at"))
                    rows.next()
                    assertEquals(102, rows.getLong("id"))
                    assertNull(rows.getTimestamp("suppression_released_at"))
                }
            }
        }
    }
}
