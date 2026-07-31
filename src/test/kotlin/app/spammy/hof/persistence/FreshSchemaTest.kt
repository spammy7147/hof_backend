package app.spammy.hof.persistence

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** 빈 PostgreSQL 호환 H2 DB에 단일 Flyway 기준 스키마를 만들고 JPA 검증 결과를 직접 확인한다. */
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
    fun appliesBaselineAndOrderedPresetUpgrade() {
        assertEquals(
            listOf(
                "1" to "initialize schema",
                "2" to "order party presets",
                "3" to "add automation work sessions",
                "4" to "prepare captcha on demand",
                "5" to "track character detail sync",
                "6" to "learn shared battle cooldowns",
                "7" to "add automation wait reason",
                "8" to "observe latest hof status",
                "9" to "reconcile ambiguous automation actions",
                "10" to "stabilize quest identity",
                "11" to "remove typed action schema version",
                "12" to "add party preset folders",
                "13" to "add town location cache",
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
                "V10__stabilize_quest_identity.sql",
                "V11__remove_typed_action_schema_version.sql",
                "V12__add_party_preset_folders.sql",
                "V13__add_town_location_cache.sql",
                "V1__initialize_schema.sql",
                "V2__order_party_presets.sql",
                "V3__add_automation_work_sessions.sql",
                "V4__prepare_captcha_on_demand.sql",
                "V5__track_character_detail_sync.sql",
                "V6__learn_shared_battle_cooldowns.sql",
                "V7__add_automation_wait_reason.sql",
                "V8__observe_latest_hof_status.sql",
                "V9__reconcile_ambiguous_automation_actions.sql",
            ),
            migrationNames,
        )

        dataSource.connection.use { connection ->
            LEGACY_AUTOMATION_TABLES.forEach { table ->
                assertFalse(connection.tableExists(table), "legacy table still exists: $table")
            }
            assertFalse(connection.columnExists("captcha_challenges", "automation_action_run_id"))
            assertTrue(connection.columnExists("party_presets", "display_order"))
            assertTrue(connection.tableExists("party_preset_folders"))
            assertTrue(connection.columnExists("party_presets", "folder_id"))
            assertTrue(connection.columnExists("captcha_challenges", "preparation_version"))
            assertTrue(connection.columnExists("characters", "detail_synced_at"))
            assertTrue(connection.columnExists("battle_maps", "shares_minute_cooldown"))
            assertTrue(connection.columnExists("quest_automation_selections", "display_code"))
            assertTrue(connection.columnExists("quest_automation_selections", "quest_name"))
            assertTrue(connection.tableExists("town_feature_locations"))
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from town_feature_locations").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(33L, rows.getLong(1))
                }
            }
        }
    }

    companion object {
        private val databaseName = "fresh_schema_${UUID.randomUUID().toString().replace("-", "")}"
        private val MIGRATION_DIRECTORY = java.nio.file.Path.of("src/main/resources/db/migration")
        private val LEGACY_AUTOMATION_TABLES = listOf(
            "automation_profiles",
            "automation_profile_maps",
            "automation_jobs",
            "automation_module_configs",
            "automation_module_legacy_settings",
            "automation_module_maps",
            "automation_module_quests",
            "automation_module_quest_maps",
            "automation_action_runs",
        )

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

private fun Connection.tableExists(table: String): Boolean =
    metaData.getTables(null, null, table, arrayOf("TABLE")).use { rows -> rows.next() }

private fun Connection.columnExists(
    table: String,
    column: String,
): Boolean = metaData.getColumns(null, null, table, column).use { rows -> rows.next() }
