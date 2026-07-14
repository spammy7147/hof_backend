package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.repository.AutomationModuleConfigRepository
import app.spammy.hof.automation.repository.AutomationModuleQuestCommandRepository
import app.spammy.hof.automation.repository.AutomationProfileRepository
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    UnifiedAutomationQueryRepository::class,
    BattleMapQueryRepository::class,
    PartyPresetQueryRepository::class,
    AutomationModuleReadinessEvaluator::class,
    UnifiedAutomationService::class,
    UnifiedAutomationWakeFailureIntegrationTest.Config::class,
)
class UnifiedAutomationWakeFailureIntegrationTest {
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var profileRepository: AutomationProfileRepository
    @Autowired private lateinit var moduleConfigRepository: AutomationModuleConfigRepository
    @Autowired private lateinit var moduleQuestRepository: AutomationModuleQuestCommandRepository
    @Autowired private lateinit var queryRepository: UnifiedAutomationQueryRepository
    @Autowired private lateinit var service: UnifiedAutomationService
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun wakeFailureAfterCommitDoesNotFailStartAndKeepsTheRunningJobRecoverable() {
        val accountId = TransactionTemplate(transactionManager).execute {
            val account = accountRepository.save(
                HofAccountEntity(loginId = "wake-failure", encryptedPassword = "encrypted", createdAt = NOW),
            )
            val profile = profileRepository.save(
                AutomationProfileEntity(
                    account = account,
                    name = "통합 자동화",
                    mode = UnifiedAutomationQueryRepository.UNIFIED_MODE,
                    enabled = true,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            val config = moduleConfigRepository.save(
                AutomationModuleConfigEntity(
                    profile = profile,
                    moduleType = AutomationModuleType.OTHER_QUEST,
                    enabled = true,
                    priority = 0,
                    displayName = "일반 퀘스트",
                    thresholdPercent = null,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            moduleQuestRepository.save(
                AutomationModuleQuestEntity(moduleConfig = config, questCode = "1001", executionOrder = 0),
            )
            account.id
        }

        val response = assertDoesNotThrow { service.start(accountId) }
        val committedJob = TransactionTemplate(transactionManager).execute {
            queryRepository.findCurrentJob(accountId)
        }

        assertEquals("RUNNING", response.job?.status)
        assertEquals("RUNNING", assertNotNull(committedJob).status)
        assertEquals(NOW, committedJob.nextRunAt)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean
        fun timeProvider(): TimeProvider = TimeProvider { NOW }

        @Bean
        fun afterCommitWakeupService(): AutomationAfterCommitWakeupService =
            Mockito.mock(AutomationAfterCommitWakeupService::class.java).also { service ->
                Mockito.doThrow(IllegalStateException("simulated delivery failure"))
                    .`when`(service).wake(Mockito.anyLong(), Mockito.anyString())
            }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T06:00:00Z")
    }
}
