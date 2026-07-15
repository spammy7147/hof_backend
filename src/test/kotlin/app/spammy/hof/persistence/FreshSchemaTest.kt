package app.spammy.hof.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** 빈 PostgreSQL 호환 H2 DB에 Flyway 기준 스키마를 만들고 JPA 검증 결과를 직접 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
class FreshSchemaTest {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var flyway: Flyway

    @Test
    fun createsOnlyTheNormalizedApplicationTablesAndColumns() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select setting_value from information_schema.settings where setting_name = 'MODE'",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("PostgreSQL", rows.getString(1))
                }
            }

            FreshSchemaContract.assertColumns(connection.metaData)
        }
    }

    @Test
    fun createsExactKeysIndexesAndChecksWithLifecycleDeleteActions() {
        dataSource.connection.use(FreshSchemaContract::assertRelationalContracts)
    }

    @Test
    fun appliesOnlyCurrentSchemaAndStaticSeedMigrations() {
        assertEquals(
            listOf(
                "1" to "initialize schema",
                "2" to "seed battle map catalog",
                "3" to "add token authentication",
                "4" to "add unified automation state",
                "5" to "add automation messaging",
                "6" to "add push and captcha resume",
                "7" to "encrypt hof cookie storage",
                "8" to "customize unified automation modules",
                "9" to "replace automation with typed handlers",
                "10" to "add adventure daily preflight state",
                "11" to "lease adventure daily preflight refresh",
                "12" to "add quest automation cycles",
                "13" to "deduplicate quest automation results",
                "14" to "add battle map automation progress ledger",
                "15" to "add typed automation runtime",
                "16" to "harden typed automation runtime",
            ),
            flyway.info().applied().map { migration -> migration.version.toString() to migration.description },
        )

        val migrationNames =
            MIGRATION_DIRECTORY.toFile().listFiles()
                .orEmpty()
                .filter { file -> file.isFile && file.extension == "sql" }
                .map { file -> file.name }
                .sorted()
        assertEquals(
            listOf(
                "V10__add_adventure_daily_preflight_state.sql",
                "V11__lease_adventure_daily_preflight_refresh.sql",
                "V12__add_quest_automation_cycles.sql",
                "V13__deduplicate_quest_automation_results.sql",
                "V14__add_battle_map_automation_progress_ledger.sql",
                "V15__add_typed_automation_runtime.sql",
                "V16__harden_typed_automation_runtime.sql",
                "V1__initialize_schema.sql",
                "V2__seed_battle_map_catalog.sql",
                "V3__add_token_authentication.sql",
                "V4__add_unified_automation_state.sql",
                "V5__add_automation_messaging.sql",
                "V6__add_push_and_captcha_resume.sql",
                "V7__encrypt_hof_cookie_storage.sql",
                "V8__customize_unified_automation_modules.sql",
                "V9__replace_automation_with_typed_handlers.sql",
            ),
            migrationNames,
        )
    }

    @Test
    fun v9ResetsLegacyAutomationData() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val upgradeDatabase = "v9_upgrade_$suffix"
        val upgradeUrl =
            "jdbc:h2:mem:$upgradeDatabase;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(upgradeUrl, "sa", "").target("7").load().migrate()

        DriverManager.getConnection(upgradeUrl, "sa", "").use { connection ->
            seedV7AutomationData(connection)
        }

        Flyway.configure().dataSource(upgradeUrl, "sa", "").load().migrate()

        DriverManager.getConnection(upgradeUrl, "sa", "").use { connection ->
            assertEquals(2, connection.count("select count(*) from hof_accounts"))
            assertEquals(0, connection.count("select count(*) from automation_profiles"))
            assertEquals(1, connection.count("select count(*) from characters"))
            assertEquals(1, connection.count("select count(*) from party_presets"))
            assertEquals(1, connection.count("select count(*) from battle_logs"))

            assertEquals(0, connection.automationCount("automation_jobs", "UNIFIED"))
            assertEquals(0, connection.automationCount("automation_profile_maps", "UNIFIED"))
            assertEquals(0, connection.automationCount("automation_module_configs", "UNIFIED"))
            assertEquals(0, connection.actionRunCount("UNIFIED"))

            assertEquals(0, connection.automationCount("automation_jobs", "TIME_BURN"))
            assertEquals(0, connection.automationCount("automation_profile_maps", "TIME_BURN"))
            assertEquals(0, connection.automationCount("automation_module_configs", "TIME_BURN"))
            assertEquals(0, connection.actionRunCount("TIME_BURN"))
            assertEquals(0, connection.count("select count(*) from automation_consumed_events"))
            assertEquals(0, connection.count("select count(*) from account_automation_leases"))
        }
    }

    companion object {
        private val databaseName = "fresh_schema_${UUID.randomUUID().toString().replace("-", "")}"
        private val MIGRATION_DIRECTORY = java.nio.file.Path.of("src/main/resources/db/migration")

        @JvmStatic
        @DynamicPropertySource
        fun freshDatabase(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                    "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
            }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
        }
    }
}

private fun seedV7AutomationData(connection: Connection) {
    val statements = listOf(
        """
        insert into hof_accounts (login_id, encrypted_password, created_at)
        values
            ('upgrade-unified', 'encrypted', current_timestamp),
            ('upgrade-time-burn', 'encrypted', current_timestamp)
        """.trimIndent(),
        """
        insert into automation_profiles (account_id, name, mode, enabled, created_at, updated_at)
        select id, 'upgrade-unified-profile', 'UNIFIED', true, current_timestamp, current_timestamp
        from hof_accounts where login_id = 'upgrade-unified'
        union all
        select id, 'upgrade-time-profile', 'TIME_BURN', true, current_timestamp, current_timestamp
        from hof_accounts where login_id = 'upgrade-time-burn'
        """.trimIndent(),
        """
        insert into automation_module_configs
            (profile_id, module_type, enabled, priority, settings_json, created_at, updated_at)
        select id, 'TIME_BURN', true, 0,
               case mode
                   when 'UNIFIED' then '{"discard":"unified-only"}'
                   else '{"legacyKey":"keep-me"}'
               end,
               current_timestamp, current_timestamp
        from automation_profiles
        """.trimIndent(),
        """
        insert into automation_jobs
            (account_id, profile_id, status, current_step_index, created_at, updated_at)
        select account_id, id, 'RUNNING', 0, current_timestamp, current_timestamp
        from automation_profiles
        """.trimIndent(),
        """
        insert into automation_action_runs
            (job_id, module_type, action_type, status, request_key, payload_json, created_at, updated_at)
        select id, 'TIME_BURN', 'BATTLE', 'PENDING', 'upgrade-action-' || id, '{}',
               current_timestamp, current_timestamp
        from automation_jobs
        """.trimIndent(),
        """
        insert into automation_consumed_events (event_id, consumed_at)
        values ('upgrade-consumed-event', current_timestamp)
        """.trimIndent(),
        """
        insert into account_automation_leases (account_id, owner_id, lease_until, updated_at)
        select id, 'upgrade-owner', current_timestamp, current_timestamp
        from hof_accounts where login_id = 'upgrade-unified'
        """.trimIndent(),
        """
        insert into automation_profile_maps
            (profile_id, battle_map_id, execution_order, module_type, purpose)
        select profiles.id, maps.id, 0, 'TIME_BURN', 'PRIMARY'
        from automation_profiles profiles
        cross join (select min(id) as id from battle_maps) maps
        """.trimIndent(),
        """
        insert into characters
            (account_id, hof_character_id, name, job, pattern_slot_count, updated_at)
        select id, 'upgrade-character', 'Upgrade Character', 'TEST', 0, current_timestamp
        from hof_accounts where login_id = 'upgrade-unified'
        """.trimIndent(),
        """
        insert into party_presets (account_id, name, created_at, updated_at)
        select id, 'Upgrade Preset', current_timestamp, current_timestamp
        from hof_accounts where login_id = 'upgrade-unified'
        """.trimIndent(),
        """
        insert into battle_logs
            (account_id, category_id, map_code, map_name_snapshot, outcome, title, created_at)
        select id, 'UPGRADE', 'upgrade-map', 'Upgrade Map', 'WIN', 'Upgrade Log', current_timestamp
        from hof_accounts where login_id = 'upgrade-unified'
        """.trimIndent(),
    )
    connection.createStatement().use { statement ->
        statements.forEach(statement::executeUpdate)
    }
}

private fun Connection.automationCount(
    table: String,
    mode: String,
): Int = count(
    "select count(*) from $table rows " +
        "join automation_profiles profiles on profiles.id = rows.profile_id " +
        "where profiles.mode = '$mode'",
)

private fun Connection.actionRunCount(mode: String): Int = count(
    "select count(*) from automation_action_runs actions " +
        "join automation_jobs jobs on jobs.id = actions.job_id " +
        "join automation_profiles profiles on profiles.id = jobs.profile_id " +
        "where profiles.mode = '$mode'",
)

private fun Connection.count(sql: String): Int =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            check(rows.next())
            rows.getInt(1)
        }
    }

private fun Connection.stringValue(sql: String): String =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            check(rows.next())
            rows.getString(1)
        }
    }
