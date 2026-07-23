package app.spammy.hof.automation.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkSessionEntity
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.common.persistence.QueryDslConfig
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, AutomationWorkSessionQueryRepository::class)
class AutomationWorkSessionPersistenceTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entries: AutomationEntryCommandRepository
    @Autowired private lateinit var sessions: AutomationWorkSessionCommandRepository
    @Autowired private lateinit var queries: AutomationWorkSessionQueryRepository
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `one running session coexists with resource and cooldown waits`() {
        val now = Instant.parse("2026-07-23T00:00:00Z")
        val account = accounts.save(HofAccountEntity(loginId = "session-persistence", encryptedPassword = "encrypted", createdAt = now))
        val questEntry = entries.save(AutomationEntryEntity(account = account, type = AutomationType.QUEST, priority = 0, enabled = true, createdAt = now, updatedAt = now))
        val battleEntry = entries.save(AutomationEntryEntity(account = account, type = AutomationType.BATTLE_MAP, priority = 1, enabled = true, createdAt = now, updatedAt = now))
        val adventureEntry = entries.save(AutomationEntryEntity(account = account, type = AutomationType.ADVENTURE_MAP, priority = 2, enabled = true, createdAt = now, updatedAt = now))
        val running = sessions.save(
            AutomationWorkSessionEntity(
                account = account,
                entry = battleEntry,
                workType = AutomationWorkType.BATTLE_MAP,
                targetKey = "battle_map/map-1",
                status = AutomationWorkStatus.RUNNING,
                configVersion = "config-v1",
                targetCount = 20,
                confirmedCount = 12,
                createdAt = now,
                updatedAt = now,
            ),
        )
        sessions.save(
            AutomationWorkSessionEntity(
                account = account,
                entry = questEntry,
                workType = AutomationWorkType.QUEST,
                targetKey = "quest-1",
                status = AutomationWorkStatus.WAITING_RESOURCE,
                configVersion = "config-v1",
                materialName = "steel ingot",
                materialMissing = 2,
                nextCheckAt = now.plusSeconds(1_800),
                createdAt = now,
                updatedAt = now,
            ),
        )
        sessions.save(
            AutomationWorkSessionEntity(
                account = account,
                entry = adventureEntry,
                workType = AutomationWorkType.ADVENTURE_MAP,
                targetKey = "adventure_map/map-2",
                status = AutomationWorkStatus.WAITING_COOLDOWN,
                configVersion = "config-v1",
                nextCheckAt = now.plusSeconds(600),
                createdAt = now,
                updatedAt = now,
            ),
        )
        entityManager.flush()
        entityManager.clear()

        val persistedRunning = queries.findRunning(account.id)

        assertEquals(running.id, persistedRunning?.id)
        assertEquals(12, persistedRunning?.confirmedCount)
        assertEquals(
            listOf(AutomationWorkStatus.WAITING_RESOURCE, AutomationWorkStatus.WAITING_COOLDOWN),
            queries.findWaiting(account.id).map { it.status },
        )
        assertNull(queries.findDue(now))
        assertEquals("adventure_map/map-2", queries.findDue(now.plusSeconds(600))?.targetKey)
    }
}
