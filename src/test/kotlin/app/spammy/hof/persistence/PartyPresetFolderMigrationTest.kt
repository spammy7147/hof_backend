package app.spammy.hof.persistence

import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.flywaydb.core.Flyway

class PartyPresetFolderMigrationTest {
    @Test
    fun `v12 preserves existing presets and members as unassigned`() {
        val databaseName = "party_preset_folders_${UUID.randomUUID().toString().replace("-", "")}"
        val url = "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
            "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")
            .target("11")
            .load()
            .migrate()

        val presetBefore = DriverManager.getConnection(url, "sa", "").use { connection ->
            seedPresetWithMembers(connection)
            connection.presetSnapshot()
        }
        val membersBefore = DriverManager.getConnection(url, "sa", "").use(Connection::memberSnapshots)

        Flyway.configure()
            .dataSource(url, "sa", "")
            .locations("filesystem:src/main/resources/db/migration")
            .target("12")
            .load()
            .migrate()

        DriverManager.getConnection(url, "sa", "").use { connection ->
            assertEquals(presetBefore, connection.presetSnapshot())
            assertEquals(membersBefore, connection.memberSnapshots())
            assertNull(connection.presetFolderId())
        }
    }

    private fun seedPresetWithMembers(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                "insert into hof_accounts(id, login_id, encrypted_password, created_at) values " +
                    "(101, 'preset-migration', 'encrypted', timestamp with time zone '2026-07-01 00:00:00+00:00')",
            )
            statement.executeUpdate(
                "insert into characters(id, account_id, hof_character_id, name, job, pattern_slot_count, updated_at) " +
                    "values (201, 101, 'character-201', 'Migration Hero', 'Knight', 1, " +
                    "timestamp with time zone '2026-07-01 00:30:00+00:00')",
            )
            statement.executeUpdate(
                "insert into character_pattern_slots(id, character_id, slot_code, label, can_load) " +
                    "values (301, 201, 'slot-a', 'Migration Slot', true)",
            )
            statement.executeUpdate(
                "insert into party_presets(" +
                    "id, account_id, name, created_at, updated_at, is_primary, primary_marker, display_order" +
                    ") values (401, 101, 'Preserved Preset', " +
                    "timestamp with time zone '2026-07-01 01:02:03+00:00', " +
                    "timestamp with time zone '2026-07-02 04:05:06+00:00', true, 1, 7)",
            )
            statement.executeUpdate(
                "insert into party_preset_members(preset_id, slot_index, character_id, pattern_slot_id) values " +
                    "(401, 0, 201, 301), (401, 1, null, null)",
            )
        }
    }
}

private data class PresetMigrationSnapshot(
    val id: Long,
    val accountId: Long,
    val name: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val displayOrder: Int,
    val primary: Boolean,
    val primaryMarker: Int?,
)

private data class PresetMemberMigrationSnapshot(
    val presetId: Long,
    val slotIndex: Int,
    val characterId: Long?,
    val patternSlotId: Long?,
)

private fun Connection.presetSnapshot(): PresetMigrationSnapshot =
    createStatement().use { statement ->
        statement.executeQuery(
            "select id, account_id, name, created_at, updated_at, display_order, is_primary, primary_marker " +
                "from party_presets where id = 401",
        ).use { rows ->
            check(rows.next())
            PresetMigrationSnapshot(
                id = rows.getLong("id"),
                accountId = rows.getLong("account_id"),
                name = rows.getString("name"),
                createdAt = rows.getObject("created_at", OffsetDateTime::class.java),
                updatedAt = rows.getObject("updated_at", OffsetDateTime::class.java),
                displayOrder = rows.getInt("display_order"),
                primary = rows.getBoolean("is_primary"),
                primaryMarker = rows.nullableLongValue("primary_marker")?.toInt(),
            )
        }
    }

private fun Connection.memberSnapshots(): List<PresetMemberMigrationSnapshot> =
    createStatement().use { statement ->
        statement.executeQuery(
            "select preset_id, slot_index, character_id, pattern_slot_id " +
                "from party_preset_members where preset_id = 401 order by slot_index",
        ).use { rows ->
            buildList {
                while (rows.next()) {
                    add(
                        PresetMemberMigrationSnapshot(
                            presetId = rows.getLong("preset_id"),
                            slotIndex = rows.getInt("slot_index"),
                            characterId = rows.nullableLongValue("character_id"),
                            patternSlotId = rows.nullableLongValue("pattern_slot_id"),
                        ),
                    )
                }
            }
        }
    }

private fun Connection.presetFolderId(): Long? =
    createStatement().use { statement ->
        statement.executeQuery("select folder_id from party_presets where id = 401").use { rows ->
            check(rows.next())
            rows.nullableLongValue("folder_id")
        }
    }

private fun java.sql.ResultSet.nullableLongValue(column: String): Long? =
    getLong(column).takeUnless { wasNull() }
