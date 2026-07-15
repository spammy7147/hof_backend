package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationActionRunEntity
import app.spammy.hof.automation.entity.AutomationActionStatus
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.policy.AutomationDecision
import app.spammy.hof.automation.policy.AutomationDecisionType
import app.spammy.hof.automation.policy.AutomationMapCandidate
import app.spammy.hof.automation.repository.AutomationActionRunQueryRepository
import app.spammy.hof.automation.repository.AutomationActionRunRepository
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.automation.repository.AutomationJobRepository
import app.spammy.hof.automation.repository.AutomationModuleConfigRepository
import app.spammy.hof.automation.repository.AutomationProfileRepository
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.push.service.PushOutboxService
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    AutomationJobQueryRepository::class,
    AutomationActionRunQueryRepository::class,
    UnifiedAutomationQueryRepository::class,
    AutomationActionLockCoordinator::class,
    AutomationCheckpointService::class,
    AutomationCheckpointLockOrderIntegrationTest.Config::class,
)
class AutomationCheckpointLockOrderIntegrationTest {
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var profileRepository: AutomationProfileRepository
    @Autowired private lateinit var moduleRepository: AutomationModuleConfigRepository
    @Autowired private lateinit var jobRepository: AutomationJobRepository
    @Autowired private lateinit var actionRepository: AutomationActionRunRepository
    @Autowired private lateinit var jobQueryRepository: AutomationJobQueryRepository
    @MockitoSpyBean private lateinit var actionQueryRepository: AutomationActionRunQueryRepository
    @Autowired private lateinit var checkpointService: AutomationCheckpointService
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun staleRecoveryAndLateCompletionFinishWithoutDeadlockAndKeepTheOldAttemptFenced() {
        val fixture = createStaleRunningAction()
        val recoveryWaitingBeforeActionLock = CountDownLatch(1)
        val lateCompletionResolvedTarget = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        Mockito.doAnswer { invocation ->
            recoveryWaitingBeforeActionLock.countDown()
            check(lateCompletionResolvedTarget.await(10, TimeUnit.SECONDS))
            invocation.callRealMethod()
        }.`when`(actionQueryRepository).findByRequestKeyForUpdate("job:${fixture.jobId}:step:0")
        Mockito.doAnswer { invocation ->
            invocation.callRealMethod().also { lateCompletionResolvedTarget.countDown() }
        }.`when`(actionQueryRepository).findLockTargetById(fixture.actionId)

        try {
            val recovery = executor.submit<RunnableAutomationJob?> {
                TransactionTemplate(transactionManager).execute {
                    checkpointService.findRunnable(fixture.accountId)
                }
            }
            check(recoveryWaitingBeforeActionLock.await(10, TimeUnit.SECONDS))
            val lateCompletion = executor.submit<Boolean> {
                TransactionTemplate(transactionManager).execute {
                    checkpointService.succeed(AutomationExecutionToken(fixture.actionId, 1))
                }
            }

            val recovered = assertNotNull(recovery.get(10, TimeUnit.SECONDS))
            assertEquals(fixture.actionId, recovered.retryAction?.actionId)
            assertFalse(lateCompletion.get(10, TimeUnit.SECONDS))

            TransactionTemplate(transactionManager).executeWithoutResult {
                val job = assertNotNull(
                    jobQueryRepository.findOwnedByAccountIdAndId(fixture.accountId, fixture.jobId),
                )
                val action = assertNotNull(actionQueryRepository.findById(fixture.actionId))
                assertEquals(0, job.currentStepIndex)
                assertEquals(AutomationActionStatus.RETRY_WAIT, action.status)
                assertEquals(1, action.attemptCount)
            }
        } finally {
            lateCompletionResolvedTarget.countDown()
            executor.shutdownNow()
        }
    }

    private fun createStaleRunningAction(): Fixture = TransactionTemplate(transactionManager).execute {
        val account = accountRepository.save(
            HofAccountEntity(
                loginId = "checkpoint-lock-order",
                encryptedPassword = "encrypted",
                createdAt = NOW,
            ),
        )
        val profile = profileRepository.save(
            AutomationProfileEntity(
                account = account,
                name = "통합 자동화",
                mode = "UNIFIED",
                enabled = true,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val module = moduleRepository.save(
            AutomationModuleConfigEntity(
                profile = profile,
                moduleType = AutomationModuleType.TIME_BURN,
                enabled = true,
                priority = 0,
                displayName = "시간 소모",
                thresholdPercent = 90,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val job = jobRepository.save(
            AutomationJobEntity(
                account = account,
                profile = profile,
                status = "RUNNING",
                currentStepIndex = 0,
                message = null,
                createdAt = NOW,
                startedAt = NOW,
                updatedAt = NOW,
                finishedAt = null,
                currentModule = module.moduleType.name,
                currentModuleConfig = module,
                currentAction = "테스트 맵",
            ),
        )
        val decision = AutomationDecision(
            type = AutomationDecisionType.RUN_BATTLE,
            moduleType = module.moduleType,
            moduleConfigId = module.id,
            moduleRevision = module.updatedAt,
            map = AutomationMapCandidate("lock-map", "테스트 맵", 0, 301L),
        )
        val payload = AutomationExecutionPayload(
            decision = decision,
            resolvedBattleRequest = RunBattleRequest(
                categoryId = "battle_map",
                mapCode = "lock-map",
                characterIds = listOf("character-1"),
                patternLoads = listOf(BattlePatternLoadRequest("character-1", 1)),
            ),
        )
        val action = actionRepository.save(
            AutomationActionRunEntity(
                job = job,
                moduleConfig = module,
                moduleType = module.moduleType,
                actionType = decision.type.name,
                actionKey = decision.map?.mapCode,
                status = AutomationActionStatus.RUNNING,
                requestKey = "job:${job.id}:step:0",
                payloadJson = objectMapper.writeValueAsString(payload),
                attemptCount = 1,
                createdAt = NOW.minusSeconds(301),
                startedAt = NOW.minusSeconds(301),
                updatedAt = NOW.minusSeconds(301),
            ),
        )
        actionRepository.flush()
        Fixture(account.id, job.id, action.id)
    }

    private data class Fixture(
        val accountId: Long,
        val jobId: Long,
        val actionId: Long,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean
        fun objectMapper(): ObjectMapper = jacksonObjectMapper()

        @Bean
        fun timeProvider(): TimeProvider = TimeProvider { NOW }

        @Bean
        fun pushOutboxService(): PushOutboxService = Mockito.mock(PushOutboxService::class.java)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T06:00:00Z")
    }
}
