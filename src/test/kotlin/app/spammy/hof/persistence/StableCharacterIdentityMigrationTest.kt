package app.spammy.hof.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway

class StableCharacterIdentityMigrationTest {
    @Test
    fun `v23 keeps the character row and preset reference while creating identity history`() {
        val databaseName = "character_identity_${UUID.randomUUID().toString().replace("-", "")}" 
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"

        migrate(url, "22")
        DriverManager.getConnection(url, "sa", "").use(::seedCharacterAndPreset)
        migrate(url, "23")

        DriverManager.getConnection(url, "sa", "").use { connection ->
            assertEquals("1683198503393759", connection.text("select current_hof_character_id from characters where id = 10"))
            assertEquals("ACTIVE", connection.text("select lifecycle from characters where id = 10"))
            assertTrue(connection.valueExists("select last_seen_at from characters where id = 10"))
            assertEquals(10L, connection.long("select character_id from party_preset_members where preset_id = 20"))

            assertEquals(1, connection.int("select count(*) from character_hof_id_history where character_id = 10"))
            assertEquals("1683198503393759", connection.text("select hof_character_id from character_hof_id_history where character_id = 10"))
            assertEquals("INITIAL_SYNC", connection.text("select link_reason from character_hof_id_history where character_id = 10"))
            assertTrue(connection.valueExists("select valid_from from character_hof_id_history where character_id = 10"))
            assertTrue(!connection.valueExists("select valid_to from character_hof_id_history where character_id = 10"))
        }
    }

    private fun migrate(url: String, target: String) {
        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")
            .target(target)
            .load()
            .migrate()
    }

    private fun seedCharacterAndPreset(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "insert into hof_accounts(id, login_id, encrypted_password, created_at) " +
                    "values (1, 'character-migration', 'encrypted', current_timestamp)",
            )
            statement.executeUpdate(
                "insert into characters(id, account_id, hof_character_id, name, job, level, " +
                    "pattern_slot_count, updated_at, detail_synced_at) values " +
                    "(10, 1, '1683198503393759', '소셜', 'Social Knight', 60, 8, current_timestamp, current_timestamp)",
            )
            statement.executeUpdate(
                "insert into party_presets(id, account_id, name, created_at, updated_at, display_order, is_primary, primary_marker) " +
                    "values (20, 1, '기본', current_timestamp, current_timestamp, 0, true, 1)",
            )
            statement.executeUpdate(
                "insert into party_preset_members(preset_id, slot_index, character_id) values (20, 0, 10)",
            )
        }
    }
}

private fun Connection.text(sql: String): String =
    createStatement().use { statement -> statement.executeQuery(sql).use { rows -> rows.next(); rows.getString(1) } }

private fun Connection.long(sql: String): Long =
    createStatement().use { statement -> statement.executeQuery(sql).use { rows -> rows.next(); rows.getLong(1) } }

private fun Connection.int(sql: String): Int =
    createStatement().use { statement -> statement.executeQuery(sql).use { rows -> rows.next(); rows.getInt(1) } }

private fun Connection.valueExists(sql: String): Boolean =
    createStatement().use { statement -> statement.executeQuery(sql).use { rows -> rows.next(); rows.getObject(1) != null } }
