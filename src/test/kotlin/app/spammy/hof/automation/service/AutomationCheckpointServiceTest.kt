package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
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
import app.spammy.hof.automation.repository.AutomationModuleAggregate
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.service.CaptchaAutomationHook
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.push.service.PushOutboxService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper

class AutomationCheckpointServiceTest {
    private val jobQueryRepository = Mockito.mock(AutomationJobQueryRepository::class.java)
    private val actionRepository = Mockito.mock(AutomationActionRunRepository::class.java)
    private val actionQueryRepository = Mockito.mock(AutomationActionRunQueryRepository::class.java)
    private val unifiedQueryRepository = Mockito.mock(UnifiedAutomationQueryRepository::class.java)
    private val pushOutboxService = Mockito.mock(PushOutboxService::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val service = AutomationCheckpointService(
        jobQueryRepository,
        actionRepository,
        actionQueryRepository,
        unifiedQueryRepository,
        objectMapper,
        TimeProvider { NOW },
        pushOutboxService,
    )

    @Test
    fun `start stores the owned unified module on the action and job`() {
        val job = job()
        val module = module()
        val runnable = RunnableAutomationJob(job.id, ACCOUNT_ID, 0)
        val decision = decision(module.id)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(unifiedQueryRepository.findModule(ACCOUNT_ID, module.id))
            .thenReturn(AutomationModuleAggregate(module, emptyList(), emptyList()))
        Mockito.`when`(actionQueryRepository.findByRequestKey("job:${job.id}:step:0")).thenReturn(null)
        var savedAction: AutomationActionRunEntity? = null
        Mockito.`when`(actionRepository.save(anyAction())).thenAnswer {
            (it.arguments[0] as AutomationActionRunEntity).also { action -> savedAction = action }
        }

        val actionId = service.start(runnable, decision)

        Mockito.verify(actionRepository).save(anyAction())
        assertEquals(0L, actionId)
        assertEquals(module.id, savedAction?.moduleConfig?.id)
        assertEquals(module.id, job.currentModuleConfig?.id)
    }

    @Test
    fun `start ignores a module deleted after the snapshot was made`() {
        val job = job()
        val runnable = RunnableAutomationJob(job.id, ACCOUNT_ID, 0)
        val decision = decision(404L)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(unifiedQueryRepository.findModule(ACCOUNT_ID, 404L)).thenReturn(null)

        assertNull(service.start(runnable, decision))
        Mockito.verifyNoInteractions(actionRepository)
        assertNull(job.currentModuleConfig)
    }

    @Test
    fun `findRunnable restores a due retry from its persisted payload even after module deletion`() {
        val job = job()
        val original = decision(505L)
        val action = action(job, original).also {
            it.moduleConfig = null
            it.status = AutomationActionStatus.RETRY_WAIT
            it.nextAttemptAt = NOW
        }
        Mockito.`when`(jobQueryRepository.findCurrentByAccountIdAndStatuses(ACCOUNT_ID, setOf("RUNNING")))
            .thenReturn(job)
        Mockito.`when`(actionQueryRepository.findByRequestKey("job:${job.id}:step:0")).thenReturn(action)

        val runnable = assertNotNull(service.findRunnable(ACCOUNT_ID))

        assertEquals(action.id, runnable.retryAction?.actionId)
        assertEquals(original, runnable.retryAction?.decision)
    }

    @Test
    fun `captcha failure keeps current action and module identity for retry`() {
        val job = job()
        val module = module()
        val original = decision(module.id)
        val action = action(job, original).also { it.moduleConfig = module }
        job.currentModuleConfig = module
        job.currentAction = original.map?.mapName
        Mockito.`when`(actionQueryRepository.findById(action.id)).thenReturn(action)

        service.fail(action.id, ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha"))

        assertEquals(AutomationActionStatus.WAITING_CAPTCHA, action.status)
        assertEquals("WAITING_CAPTCHA", job.status)
        assertEquals(module.id, job.currentModuleConfig?.id)
        assertEquals(original.map?.mapName, job.currentAction)
    }

    @Test
    fun `resume retry locks the job and reopens only the original action`() {
        val job = job()
        val original = decision(505L)
        val action = action(job, original).also {
            it.status = AutomationActionStatus.RETRY_WAIT
            it.nextAttemptAt = NOW
        }
        val retry = RetryableAutomationAction(action.id, original)
        val runnable = RunnableAutomationJob(job.id, ACCOUNT_ID, 0, retry)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(actionQueryRepository.findById(action.id)).thenReturn(action)

        assertEquals(true, service.resumeRetry(runnable, retry))
        assertEquals(AutomationActionStatus.RUNNING, action.status)
        assertEquals(2, action.attemptCount)
        Mockito.verify(jobQueryRepository).findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)
    }

    @Test
    fun `captcha answer resumes the same action payload and module identity`() {
        val job = job()
        val module = module()
        val original = decision(module.id)
        val action = action(job, original).also { it.moduleConfig = module }
        job.currentModuleConfig = module
        job.currentAction = original.map?.mapName
        val challenge = CaptchaChallengeEntity(
            id = 77L,
            account = job.account,
            status = "PENDING",
            prompt = "인증",
            imageUrl = "captcha.png",
            sourceUrl = "police",
            answer = null,
            createdAt = NOW,
            answeredAt = null,
        )
        val wakeup = Mockito.mock(AutomationWakeupPort::class.java)
        val hook = CaptchaAutomationHook(actionQueryRepository, pushOutboxService, wakeup, TimeProvider { NOW })
        Mockito.`when`(actionQueryRepository.findById(action.id)).thenReturn(action)

        AutomationActionContext.withAction(action.id) { hook.detected(challenge) }
        hook.answered(challenge)

        assertEquals(action, challenge.automationActionRun)
        assertEquals(AutomationActionStatus.RETRY_WAIT, action.status)
        assertEquals("RUNNING", job.status)
        assertEquals(module.id, action.moduleConfig?.id)
        assertEquals(module.id, job.currentModuleConfig?.id)
        assertEquals(objectMapper.writeValueAsString(original), action.payloadJson)
        Mockito.verify(wakeup).wake(ACCOUNT_ID, "CAPTCHA_ANSWERED")
    }

    private fun decision(moduleId: Long) = AutomationDecision(
        type = AutomationDecisionType.RUN_BATTLE,
        moduleType = AutomationModuleType.TIME_BURN,
        moduleConfigId = moduleId,
        map = AutomationMapCandidate("map", "테스트 맵", 0, 301L),
    )

    private fun action(job: AutomationJobEntity, decision: AutomationDecision) = AutomationActionRunEntity(
        id = 91L,
        job = job,
        moduleConfig = null,
        moduleType = AutomationModuleType.TIME_BURN,
        actionType = decision.type.name,
        actionKey = decision.map?.mapCode,
        status = AutomationActionStatus.RUNNING,
        requestKey = "job:${job.id}:step:0",
        payloadJson = objectMapper.writeValueAsString(decision),
        attemptCount = 1,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun anyAction(): AutomationActionRunEntity =
        Mockito.any(AutomationActionRunEntity::class.java) ?: action(job(), decision(101L))

    private fun module() = AutomationModuleConfigEntity(
        id = 101L,
        profile = profile(),
        moduleType = AutomationModuleType.TIME_BURN,
        enabled = true,
        priority = 0,
        displayName = "시간 소모",
        thresholdPercent = 90,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun job() = AutomationJobEntity(
        id = 11L,
        account = account(),
        profile = profile(),
        status = "RUNNING",
        currentStepIndex = 0,
        message = null,
        createdAt = NOW,
        startedAt = NOW,
        updatedAt = NOW,
        finishedAt = null,
    )

    private fun profile() = AutomationProfileEntity(
        id = 3L,
        account = account(),
        name = "통합 자동화",
        mode = UnifiedAutomationQueryRepository.UNIFIED_MODE,
        enabled = true,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun account() = HofAccountEntity(
        id = ACCOUNT_ID,
        loginId = "checkpoint-account",
        encryptedPassword = "encrypted",
        createdAt = NOW,
    )

    private companion object {
        const val ACCOUNT_ID = 7L
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
    }
}
