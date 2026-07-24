package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkSessionEntity
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, AutomationWorkSessionQueryRepository::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutomationExecutionSignalPersistenceTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entries: AutomationEntryCommandRepository
    @Autowired private lateinit var sessions: AutomationWorkSessionCommandRepository
    @Autowired private lateinit var queries: AutomationWorkSessionQueryRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `battle completion compares priorities after the query transaction has closed`() {
        val now = Instant.parse("2026-07-23T00:00:00Z")
        val fixture = requireNotNull(TransactionTemplate(transactionManager).execute {
            val account = accounts.save(
                HofAccountEntity(
                    loginId = "detached-execution-signal",
                    encryptedPassword = "encrypted",
                    createdAt = now,
                ),
            )
            val questEntry = entries.save(
                AutomationEntryEntity(
                    account = account,
                    type = AutomationType.QUEST,
                    priority = 0,
                    enabled = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val battleEntry = entries.save(
                AutomationEntryEntity(
                    account = account,
                    type = AutomationType.BATTLE_MAP,
                    priority = 1,
                    enabled = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val running = sessions.save(
                AutomationWorkSessionEntity(
                    account = account,
                    entry = battleEntry,
                    workType = AutomationWorkType.BATTLE_MAP,
                    targetKey = "battle_map/map-1",
                    status = AutomationWorkStatus.RUNNING,
                    configVersion = "config-v1",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val waiting = sessions.save(
                AutomationWorkSessionEntity(
                    account = account,
                    entry = questEntry,
                    workType = AutomationWorkType.QUEST,
                    targetKey = "quest-1",
                    status = AutomationWorkStatus.WAITING_RESOURCE,
                    configVersion = "config-v1",
                    materialName = "steel ingot",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            Fixture(account.id, running.id, waiting.id)
        })
        val lifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
        Mockito.`when`(lifecycle.yieldForPriority(fixture.accountId, fixture.runningSessionId)).thenReturn(true)
        val service = AutomationExecutionSignalService(
            queries,
            lifecycle,
            AutomationLootSignalService(),
            TimeProvider { now },
        )

        val yielded = service.afterBattle(
            accountId = fixture.accountId,
            source = BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
            outcomes = listOf(BattleAutomationRoundOutcome.VICTORY),
            lootNames = listOf("Steel Ingot"),
            questTexts = emptyList(),
        )

        assertTrue(yielded)
        Mockito.verify(lifecycle).triggerCheck(fixture.accountId, fixture.waitingSessionId)
        Mockito.verify(lifecycle).yieldForPriority(fixture.accountId, fixture.runningSessionId)
    }

    private data class Fixture(
        val accountId: Long,
        val runningSessionId: Long,
        val waitingSessionId: Long,
    )
}
