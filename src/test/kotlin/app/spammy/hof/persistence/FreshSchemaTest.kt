package app.spammy.hof.persistence

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
                "V1__initialize_schema.sql",
                "V2__seed_battle_map_catalog.sql",
                "V3__add_token_authentication.sql",
                "V4__add_unified_automation_state.sql",
                "V5__add_automation_messaging.sql",
            ),
            migrationNames,
        )
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
