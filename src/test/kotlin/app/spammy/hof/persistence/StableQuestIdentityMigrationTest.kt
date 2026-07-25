package app.spammy.hof.persistence

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class StableQuestIdentityMigrationTest {
    @Test
    fun `v10 resets only quest automation and adds display identity`() {
        val sql = Path.of("src/main/resources/db/migration/V10__stabilize_quest_identity.sql").readText()

        assertContains(sql, "where automation_type = 'QUEST'")
        assertContains(sql, "where work_type = 'QUEST'")
        assertContains(sql, "action_kind like 'QUEST%'")
        assertContains(sql, "delete from quest_automation_selections")
        assertContains(sql, "delete from quest_automation_cycles")
        assertContains(sql, "delete from quest_map_execution_counters")
        assertContains(sql, "delete from quest_automation_processed_results")
        assertContains(sql, "add column display_code varchar(100) not null")
        assertContains(sql, "add column quest_name varchar(255) not null")
        assertFalse(sql.contains("delete from battle_automation_maps"))
        assertFalse(sql.contains("delete from adventure_automation_maps"))
    }
}
