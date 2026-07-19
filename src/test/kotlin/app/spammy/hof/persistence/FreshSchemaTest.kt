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
                "17" to "retain typed actions when entry deleted",
                "18" to "bind typed automation stop action",
                "19" to "retire legacy automation jobs",
                "20" to "add battle map key mode",
                "21" to "normalize permanent key map names",
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
                "V17__retain_typed_actions_when_entry_deleted.sql",
                "V18__bind_typed_automation_stop_action.sql",
                "V19__retire_legacy_automation_jobs.sql",
                "V1__initialize_schema.sql",
                "V20__add_battle_map_key_mode.sql",
                "V21__normalize_permanent_key_map_names.sql",
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

    @Test
    fun v18AddsNullableStopActionContextToAnExistingTypedRuntime() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val upgradeDatabase = "v18_upgrade_$suffix"
        val upgradeUrl =
            "jdbc:h2:mem:$upgradeDatabase;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(upgradeUrl, "sa", "").target("17").load().migrate()

        DriverManager.getConnection(upgradeUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    insert into hof_accounts (login_id, encrypted_password, created_at)
                    values ('v18-upgrade', 'encrypted', current_timestamp)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    insert into automation_entries
                        (account_id, automation_type, priority, enabled, created_at, updated_at)
                    select id, 'BATTLE_MAP', 0, true, current_timestamp, current_timestamp
                    from hof_accounts where login_id = 'v18-upgrade'
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    insert into typed_automation_action_runs
                        (account_id, automation_entry_id, execution_identity, action_kind, schema_version,
                         payload_json, action_fingerprint, status, retry_attempt, lease_token,
                         created_at, finished_at, updated_at)
                    select a.id, e.id, 'v18-failed-action', 'BATTLE_MAP', 1,
                           '{}', '${"a".repeat(64)}', 'FAILED', 0, 'old-token',
                           current_timestamp, current_timestamp, current_timestamp
                    from hof_accounts a
                    join automation_entries e on e.account_id = a.id
                    where a.login_id = 'v18-upgrade'
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    insert into typed_automation_runtime_states
                        (account_id, lifecycle_status, stop_reason, retry_attempt, created_at, updated_at, version)
                    select id, 'STOPPED', 'NETWORK', 0, current_timestamp, current_timestamp, 0
                    from hof_accounts where login_id = 'v18-upgrade'
                    """.trimIndent(),
                )
            }
        }

        Flyway.configure().dataSource(upgradeUrl, "sa", "").load().migrate()

        DriverManager.getConnection(upgradeUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    select stop_action_id
                    from typed_automation_runtime_states r
                    join hof_accounts a on a.id = r.account_id
                    where a.login_id = 'v18-upgrade'
                    """.trimIndent(),
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(null, rows.getObject(1))
                }
                statement.executeUpdate(
                    """
                    update typed_automation_runtime_states
                    set stop_action_id = (
                        select ar.id
                        from typed_automation_action_runs ar
                        where ar.execution_identity = 'v18-failed-action'
                    )
                    where account_id = (
                        select id from hof_accounts where login_id = 'v18-upgrade'
                    )
                    """.trimIndent(),
                )
                statement.executeQuery(
                    """
                    select count(*)
                    from typed_automation_runtime_states
                    where stop_action_id is not null
                    """.trimIndent(),
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1L, rows.getLong(1))
                }
            }
        }
    }

    @Test
    fun v19CancelsActiveLegacyJobsAndAbortsTheirUnfinishedActionsWithoutDeletingHistory() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val upgradeDatabase = "v19_upgrade_$suffix"
        val upgradeUrl =
            "jdbc:h2:mem:$upgradeDatabase;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(upgradeUrl, "sa", "").target("18").load().migrate()

        DriverManager.getConnection(upgradeUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    insert into hof_accounts (login_id, encrypted_password, created_at)
                    values ('v19-upgrade', 'encrypted', current_timestamp)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    insert into automation_profiles
                        (account_id, name, mode, enabled, created_at, updated_at)
                    select id, 'v19-profile', 'TIME_BURN', true, current_timestamp, current_timestamp
                    from hof_accounts where login_id = 'v19-upgrade'
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    insert into automation_module_configs
                        (profile_id, module_type, enabled, priority, display_name, created_at, updated_at)
                    select id, 'TIME_BURN', true, 0, 'Time Burn', current_timestamp, current_timestamp
                    from automation_profiles where name = 'v19-profile'
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    insert into automation_jobs
                        (account_id, profile_id, status, current_step_index, message,
                         created_at, started_at, updated_at, finished_at,
                         current_module, current_action, next_run_at, last_heartbeat_at, version)
                    select account_id, id, 'RUNNING', 1, 'active',
                           current_timestamp, current_timestamp, current_timestamp, null,
                           'TIME_BURN', 'battle', current_timestamp, current_timestamp, 0
                    from automation_profiles where name = 'v19-profile'
                    union all
                    select account_id, id, 'COMPLETED', 2, 'history',
                           current_timestamp, current_timestamp, current_timestamp, current_timestamp,
                           'TIME_BURN', 'stale-history-action', current_timestamp, current_timestamp, 0
                    from automation_profiles where name = 'v19-profile'
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    update automation_jobs
                    set current_module_config_id = (
                        select id from automation_module_configs where module_type = 'TIME_BURN'
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    insert into automation_action_runs
                        (job_id, module_type, action_type, status, request_key, payload_json,
                         attempt_count, next_attempt_at, created_at, started_at, updated_at)
                    select id, 'TIME_BURN', 'BATTLE', 'RETRY_WAIT', 'v19-active-action', '{}',
                           1, current_timestamp, current_timestamp, current_timestamp, current_timestamp
                    from automation_jobs where message = 'active'
                    """.trimIndent(),
                )
            }
        }

        Flyway.configure().dataSource(upgradeUrl, "sa", "").load().migrate()

        DriverManager.getConnection(upgradeUrl, "sa", "").use { connection ->
            assertEquals(
                "CANCELLED",
                connection.stringValue("select status from automation_jobs where message like '레거시%'"),
            )
            assertEquals(
                1,
                connection.count(
                    """
                    select count(*) from automation_jobs
                    where message like '레거시%'
                      and finished_at is not null
                      and current_module is null
                      and current_module_config_id is null
                      and current_action is null
                      and next_run_at is null
                      and last_heartbeat_at is null
                    """.trimIndent(),
                ),
            )
            assertEquals(
                "COMPLETED",
                connection.stringValue("select status from automation_jobs where message = 'history'"),
            )
            assertEquals(
                1,
                connection.count(
                    """
                    select count(*) from automation_jobs
                    where message = 'history'
                      and current_module is null
                      and current_module_config_id is null
                      and current_action is null
                      and next_run_at is null
                      and last_heartbeat_at is null
                    """.trimIndent(),
                ),
            )
            assertEquals(
                "ABORTED",
                connection.stringValue("select status from automation_action_runs where request_key = 'v19-active-action'"),
            )
            assertEquals(
                1,
                connection.count(
                    """
                    select count(*) from automation_action_runs
                    where request_key = 'v19-active-action'
                      and finished_at is not null
                      and next_attempt_at is null
                    """.trimIndent(),
                ),
            )
        }
    }

    @Test
    fun v20BackfillsBattleMapKeyModesWithoutChangingCounts() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val upgradeDatabase = "v20_upgrade_$suffix"
        val upgradeUrl =
            "jdbc:h2:mem:$upgradeDatabase;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway.configure().dataSource(upgradeUrl, "sa", "").target("19").load().migrate()

        DriverManager.getConnection(upgradeUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    insert into hof_accounts (login_id, encrypted_password, created_at)
                    values ('v20-upgrade', 'encrypted', current_timestamp)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    insert into battle_maps
                        (category_id, map_code, name, normalized_name, display_order,
                         enabled, created_at, updated_at)
                    values
                        ('adventure_map', 'v20-null', 'V20 Null', 'v20 null', 0,
                         true, current_timestamp, current_timestamp),
                        ('adventure_map', 'v20-limited', 'V20 Limited', 'v20 limited', 1,
                         true, current_timestamp, current_timestamp)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    insert into account_battle_map_states
                        (account_id, battle_map_id, key_count, raw_href, visible, last_seen_at,
                         supports_three_battles)
                    select a.id, m.id,
                           case m.map_code when 'v20-limited' then 3 else null end,
                           'index.php?sp_common=' || m.map_code, true, current_timestamp, false
                    from hof_accounts a
                    cross join battle_maps m
                    where a.login_id = 'v20-upgrade'
                      and m.map_code in ('v20-null', 'v20-limited')
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    insert into unresolved_battle_maps
                        (account_id, category_id, group_normalized_name, group_display_order,
                         map_display_order, observed_name, normalized_name, key_count,
                         raw_href, visible, last_seen_at)
                    select id, 'adventure_map', 'v20 group', 0, 0,
                           'V20 Null', 'v20 unresolved null', null,
                           'index.php?sp_hunt#null', true, current_timestamp
                    from hof_accounts where login_id = 'v20-upgrade'
                    union all
                    select id, 'adventure_map', 'v20 group', 0, 1,
                           'V20 Limited', 'v20 unresolved limited', 4,
                           'index.php?sp_hunt#limited', true, current_timestamp
                    from hof_accounts where login_id = 'v20-upgrade'
                    """.trimIndent(),
                )
            }
        }

        Flyway.configure().dataSource(upgradeUrl, "sa", "").load().migrate()

        DriverManager.getConnection(upgradeUrl, "sa", "").use { connection ->
            assertEquals(
                listOf("v20-limited" to ("LIMITED" to 3), "v20-null" to ("UNKNOWN" to null)),
                connection.keyModes(
                    """
                    select m.map_code, s.key_mode, s.key_count
                    from account_battle_map_states s
                    join battle_maps m on m.id = s.battle_map_id
                    where m.map_code like 'v20-%'
                    order by m.map_code
                    """.trimIndent(),
                ),
            )
            assertEquals(
                listOf(
                    "v20 unresolved limited" to ("LIMITED" to 4),
                    "v20 unresolved null" to ("UNKNOWN" to null),
                ),
                connection.keyModes(
                    """
                    select normalized_name, key_mode, key_count
                    from unresolved_battle_maps
                    where normalized_name like 'v20 unresolved%'
                    order by normalized_name
                    """.trimIndent(),
                ),
            )
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

private fun Connection.keyModes(sql: String): List<Pair<String, Pair<String, Int?>>> =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            buildList {
                while (rows.next()) {
                    val keyCount = rows.getInt(3).let { value -> if (rows.wasNull()) null else value }
                    add(rows.getString(1) to (rows.getString(2) to keyCount))
                }
            }
        }
    }
