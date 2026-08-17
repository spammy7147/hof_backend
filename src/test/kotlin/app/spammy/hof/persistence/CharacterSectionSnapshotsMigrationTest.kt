package app.spammy.hof.persistence

import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway

class CharacterSectionSnapshotsMigrationTest {
    @Test
    fun `v24 adds independent section state and complete character snapshot storage`() {
        val name = "character_sections_${UUID.randomUUID().toString().replace("-", "")}" 
        val url = "jdbc:h2:mem:$name;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        val flyway = Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")
            .target("24")
            .load()

        flyway.migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            val tables = connection.metaData.getTables(null, "public", "%", arrayOf("TABLE")).use { rows ->
                buildSet { while (rows.next()) add(rows.getString("TABLE_NAME")) }
            }
            assertTrue("character_section_sync_states" in tables)
            assertTrue("character_status_effects" in tables)
            assertTrue("character_faith" in tables)
            assertTrue("character_pattern_options" in tables)
            assertTrue("character_equipment_candidates" in tables)
            assertTrue("character_saved_pattern_rows" in tables)
            assertTrue("character_equipment_saved_slots" in tables)
            assertTrue("character_equipment_saved_items" in tables)

            val statsColumns = connection.metaData.getColumns(null, "public", "character_stats", "%").use { rows ->
                buildSet { while (rows.next()) add(rows.getString("COLUMN_NAME")) }
            }
            assertTrue(setOf("exp_current", "hp_base", "str_real", "luk_bonus").all(statsColumns::contains))
            assertEquals("24", flyway.info().applied().last().version.toString())
        }
    }
}
