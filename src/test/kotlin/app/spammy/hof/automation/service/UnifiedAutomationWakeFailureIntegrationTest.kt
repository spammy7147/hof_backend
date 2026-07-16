package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.dto.ReorderAutomationModulesRequest
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.automation.repository.AutomationJobRepository
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
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    AutomationJobQueryRepository::class,
    UnifiedAutomationQueryRepository::class,
    BattleMapQueryRepository::class,
    PartyPresetQueryRepository::class,
    AutomationModuleReadinessEvaluator::class,
    StoredTypedAutomationActionCodec::class,
    UnifiedAutomationService::class,
    UnifiedAutomationWakeFailureIntegrationTest.Config::class,
)
class UnifiedAutomationWakeFailureIntegrationTest {
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var profileRepository: AutomationProfileRepository
    @Autowired private lateinit var moduleConfigRepository: AutomationModuleConfigRepository
    @Autowired private lateinit var moduleQuestRepository: AutomationModuleQuestCommandRepository
    @Autowired private lateinit var jobRepository: AutomationJobRepository
    @Autowired private lateinit var jobQueryRepository: AutomationJobQueryRepository
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

    @Test
    fun moduleChangeMakesAnIdleRunningJobDueEvenWhenWakeDeliveryFails() {
        val futureRunAt = NOW.plusSeconds(3_600)
        val configured = TransactionTemplate(transactionManager).execute {
            val account = accountRepository.save(
                HofAccountEntity(loginId = "idle-wake-failure", encryptedPassword = "encrypted", createdAt = NOW),
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
            jobRepository.save(
                AutomationJobEntity(
                    account = account,
                    profile = profile,
                    status = "RUNNING",
                    currentStepIndex = 0,
                    message = "다음 실행 대기",
                    createdAt = NOW,
                    startedAt = NOW,
                    updatedAt = NOW,
                    finishedAt = null,
                    currentAction = null,
                    nextRunAt = futureRunAt,
                    lastHeartbeatAt = NOW,
                ),
            )
            ConfiguredAutomation(account.id, config.id)
        }

        val response = assertDoesNotThrow {
            service.reorderModules(
                configured.accountId,
                ReorderAutomationModulesRequest(listOf(configured.moduleId)),
            )
        }
        val committedJob = TransactionTemplate(transactionManager).execute {
            queryRepository.findCurrentJob(configured.accountId)
        }
        val recoverableIds = TransactionTemplate(transactionManager).execute {
            jobQueryRepository.findRecoverable(NOW).map { it.id }
        }.orEmpty()

        assertEquals("RUNNING", response.job?.status)
        assertEquals(NOW, assertNotNull(committedJob).nextRunAt)
        assertEquals(NOW, committedJob.updatedAt)
        assertEquals(true, committedJob.id in recoverableIds)
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

        @Bean
        fun objectMapper(): ObjectMapper = jacksonObjectMapper()
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T06:00:00Z")
    }

    private data class ConfiguredAutomation(
        val accountId: Long,
        val moduleId: Long,
    )
}
