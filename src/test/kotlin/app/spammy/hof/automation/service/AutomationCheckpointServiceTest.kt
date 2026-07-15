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
import app.spammy.hof.automation.repository.AutomationActionLockTarget
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
import kotlin.test.assertTrue
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper

class AutomationCheckpointServiceTest {
    private var currentTime = NOW
    private val jobQueryRepository = Mockito.mock(AutomationJobQueryRepository::class.java)
    private val actionRepository = Mockito.mock(AutomationActionRunRepository::class.java)
    private val actionQueryRepository = Mockito.mock(AutomationActionRunQueryRepository::class.java)
    private val actionLockCoordinator = AutomationActionLockCoordinator(jobQueryRepository, actionQueryRepository)
    private val unifiedQueryRepository = Mockito.mock(UnifiedAutomationQueryRepository::class.java)
    private val pushOutboxService = Mockito.mock(PushOutboxService::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val service = AutomationCheckpointService(
        jobQueryRepository,
        actionRepository,
        actionQueryRepository,
        actionLockCoordinator,
        unifiedQueryRepository,
        objectMapper,
        TimeProvider { currentTime },
        pushOutboxService,
    )

    @Test
    fun `start stores the owned unified module on the action and job`() {
        val job = job()
        val module = module()
        val runnable = RunnableAutomationJob(job.id, ACCOUNT_ID, 0)
        val decision = decision(module.id, module.updatedAt)
        val payload = executionPayload(decision)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(unifiedQueryRepository.findModule(ACCOUNT_ID, module.id))
            .thenReturn(AutomationModuleAggregate(module, emptyList(), emptyList()))
        Mockito.`when`(actionQueryRepository.findByRequestKeyForUpdate("job:${job.id}:step:0")).thenReturn(null)
        var savedAction: AutomationActionRunEntity? = null
        Mockito.`when`(actionRepository.save(anyAction())).thenAnswer {
            (it.arguments[0] as AutomationActionRunEntity).also { action -> savedAction = action }
        }

        val token = service.start(runnable, payload)

        Mockito.verify(actionRepository).save(anyAction())
        assertEquals(AutomationExecutionToken(0L, 1), token)
        assertEquals(module.id, savedAction?.moduleConfig?.id)
        assertEquals(module.id, job.currentModuleConfig?.id)
    }

    @Test
    fun `start ignores a module deleted after the snapshot was made`() {
        val job = job()
        val runnable = RunnableAutomationJob(job.id, ACCOUNT_ID, 0)
        val decision = decision(404L, NOW)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(unifiedQueryRepository.findModule(ACCOUNT_ID, 404L)).thenReturn(null)

        assertNull(service.start(runnable, executionPayload(decision)))
        Mockito.verifyNoInteractions(actionRepository)
        assertNull(job.currentModuleConfig)
    }

    @Test
    fun `findRunnable restores a due retry from its persisted payload even after module deletion`() {
        val job = job()
        val original = decision(505L, NOW)
        val originalPayload = executionPayload(original)
        val action = action(job, originalPayload).also {
            it.moduleConfig = null
            it.status = AutomationActionStatus.RETRY_WAIT
            it.nextAttemptAt = NOW
        }
        Mockito.`when`(jobQueryRepository.findCurrentByAccountIdAndStatuses(ACCOUNT_ID, setOf("RUNNING")))
            .thenReturn(job)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(actionQueryRepository.findByRequestKeyForUpdate("job:${job.id}:step:0")).thenReturn(action)

        val runnable = assertNotNull(service.findRunnable(ACCOUNT_ID))

        assertEquals(action.id, runnable.retryAction?.actionId)
        assertEquals(originalPayload, runnable.retryAction?.payload)
        assertEquals(false, runnable.retryAction?.requiresPreparation)
    }

    @Test
    fun `findRunnable decodes a legacy decision payload for upgrade before retry`() {
        val job = job()
        val original = decision(505L, NOW)
        val action = action(job, executionPayload(original)).also {
            it.payloadJson = objectMapper.writeValueAsString(original)
            it.status = AutomationActionStatus.RETRY_WAIT
            it.nextAttemptAt = NOW
        }
        Mockito.`when`(jobQueryRepository.findCurrentByAccountIdAndStatuses(ACCOUNT_ID, setOf("RUNNING")))
            .thenReturn(job)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(actionQueryRepository.findByRequestKeyForUpdate("job:${job.id}:step:0")).thenReturn(action)

        val retry = assertNotNull(service.findRunnable(ACCOUNT_ID)?.retryAction)

        assertEquals(original, retry.payload.decision)
        assertEquals(true, retry.requiresPreparation)
        assertNull(retry.payload.resolvedBattleRequest)
    }

    @Test
    fun `findRunnable moves malformed retry payload to a safe terminal state`() {
        val job = job()
        val action = action(job, executionPayload(decision(505L, NOW))).also {
            it.payloadJson = "{not-valid-json"
            it.status = AutomationActionStatus.RETRY_WAIT
            it.nextAttemptAt = NOW
        }
        Mockito.`when`(jobQueryRepository.findCurrentByAccountIdAndStatuses(ACCOUNT_ID, setOf("RUNNING")))
            .thenReturn(job)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(actionQueryRepository.findByRequestKeyForUpdate("job:${job.id}:step:0")).thenReturn(action)

        assertNull(service.findRunnable(ACCOUNT_ID))

        assertEquals(AutomationActionStatus.FAILED, action.status)
        assertEquals(NOW, action.finishedAt)
        assertEquals("WAITING_CONFIG", job.status)
        assertTrue(job.message.orEmpty().contains("실행 정보"))
        assertNull(job.nextRunAt)
    }

    @Test
    fun `findRunnable keeps a running action inside the lease from executing twice`() {
        val job = job()
        val payload = executionPayload(decision(505L, NOW))
        val action = action(job, payload).also {
            it.status = AutomationActionStatus.RUNNING
            it.startedAt = NOW.minusSeconds(60)
        }
        Mockito.`when`(jobQueryRepository.findCurrentByAccountIdAndStatuses(ACCOUNT_ID, setOf("RUNNING")))
            .thenReturn(job)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(actionQueryRepository.findByRequestKeyForUpdate("job:${job.id}:step:0")).thenReturn(action)

        assertNull(service.findRunnable(ACCOUNT_ID))
        assertEquals(AutomationActionStatus.RUNNING, action.status)
        assertEquals(1, action.attemptCount)
    }

    @Test
    fun `findRunnable recovers a stale running action with its original prepared payload`() {
        val job = job()
        val payload = executionPayload(decision(505L, NOW))
        val action = action(job, payload).also {
            it.status = AutomationActionStatus.RUNNING
            it.startedAt = NOW.minusSeconds(301)
        }
        Mockito.`when`(jobQueryRepository.findCurrentByAccountIdAndStatuses(ACCOUNT_ID, setOf("RUNNING")))
            .thenReturn(job)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(actionQueryRepository.findByRequestKeyForUpdate("job:${job.id}:step:0")).thenReturn(action)

        val runnable = assertNotNull(service.findRunnable(ACCOUNT_ID))

        assertEquals(action.id, runnable.retryAction?.actionId)
        assertEquals(payload, runnable.retryAction?.payload)
        assertEquals(AutomationActionStatus.RETRY_WAIT, action.status)
        assertEquals(NOW, action.nextAttemptAt)
        assertTrue(job.message.orEmpty().contains("복구"))
    }

    @Test
    fun `start rejects a disabled module after snapshot`() {
        val job = job()
        val module = module().also { it.enabled = false }
        val runnable = RunnableAutomationJob(job.id, ACCOUNT_ID, 0)
        val staleDecision = decision(module.id, module.updatedAt)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(unifiedQueryRepository.findModule(ACCOUNT_ID, module.id))
            .thenReturn(AutomationModuleAggregate(module, emptyList(), emptyList()))

        assertNull(service.start(runnable, executionPayload(staleDecision)))
        Mockito.verifyNoInteractions(actionRepository)
    }

    @Test
    fun `start rejects an enabled module changed after snapshot`() {
        val job = job()
        val module = module().also { it.updatedAt = NOW.plusSeconds(1) }
        val runnable = RunnableAutomationJob(job.id, ACCOUNT_ID, 0)
        val staleDecision = decision(module.id, NOW)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(unifiedQueryRepository.findModule(ACCOUNT_ID, module.id))
            .thenReturn(AutomationModuleAggregate(module, emptyList(), emptyList()))

        assertNull(service.start(runnable, executionPayload(staleDecision)))
        Mockito.verifyNoInteractions(actionRepository)
    }

    @Test
    fun `success preserves the prepared request payload for audit`() {
        val job = job()
        val payload = executionPayload(decision(505L, NOW))
        val action = action(job, payload)
        stubActionLock(action)

        service.succeed(AutomationExecutionToken(action.id, 1))

        assertEquals(objectMapper.writeValueAsString(payload), action.payloadJson)
        assertEquals(AutomationActionStatus.SUCCEEDED, action.status)
        verifyJobThenActionLock(action)
    }

    @Test
    fun `captcha failure keeps current action and module identity for retry`() {
        val job = job()
        val module = module()
        val original = decision(module.id, module.updatedAt)
        val action = action(job, executionPayload(original)).also { it.moduleConfig = module }
        job.currentModuleConfig = module
        job.currentAction = original.map?.mapName
        stubActionLock(action)

        service.fail(AutomationExecutionToken(action.id, 1), ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha"))

        assertEquals(AutomationActionStatus.WAITING_CAPTCHA, action.status)
        assertEquals("WAITING_CAPTCHA", job.status)
        assertEquals(module.id, job.currentModuleConfig?.id)
        assertEquals(original.map?.mapName, job.currentAction)
        verifyJobThenActionLock(action)
    }

    @Test
    fun `resume retry locks the job and reopens only the original action`() {
        val job = job()
        val original = decision(505L, NOW)
        val payload = executionPayload(original)
        val action = action(job, payload).also {
            it.status = AutomationActionStatus.RETRY_WAIT
            it.nextAttemptAt = NOW
        }
        val retry = RetryableAutomationAction(action.id, payload)
        val runnable = RunnableAutomationJob(job.id, ACCOUNT_ID, 0, retry)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(actionQueryRepository.findByIdForUpdate(action.id)).thenReturn(action)

        assertEquals(AutomationExecutionToken(action.id, 2), service.resumeRetry(runnable, retry, payload))
        assertEquals(AutomationActionStatus.RUNNING, action.status)
        assertEquals(2, action.attemptCount)
        assertEquals(objectMapper.writeValueAsString(payload), action.payloadJson)
        Mockito.verify(jobQueryRepository).findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)
    }

    @Test
    fun `late previous attempt cannot complete or fail the newer attempt`() {
        val job = job()
        val payload = executionPayload(decision(505L, NOW))
        val action = action(job, payload).also {
            it.status = AutomationActionStatus.RETRY_WAIT
            it.nextAttemptAt = NOW
        }
        val retry = RetryableAutomationAction(action.id, payload)
        val runnable = RunnableAutomationJob(job.id, ACCOUNT_ID, 0, retry)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(actionQueryRepository.findByIdForUpdate(action.id)).thenReturn(action)
        stubActionLock(action)

        val oldToken = AutomationExecutionToken(action.id, 1)
        val currentToken = assertNotNull(service.resumeRetry(runnable, retry, payload))
        assertEquals(2, currentToken.attemptCount)

        assertEquals(false, service.succeed(oldToken))
        assertNull(service.fail(oldToken, IllegalStateException("late failure")))
        assertEquals(AutomationActionStatus.RUNNING, action.status)
        assertEquals(0, job.currentStepIndex)

        assertEquals(true, service.succeed(currentToken))
        assertEquals(AutomationActionStatus.SUCCEEDED, action.status)
        assertEquals(1, job.currentStepIndex)

        assertEquals(false, service.succeed(oldToken))
        assertNull(service.fail(oldToken, IllegalStateException("later failure")))
        assertEquals(AutomationActionStatus.SUCCEEDED, action.status)
        assertEquals(1, job.currentStepIndex)
    }

    @Test
    fun `legacy payload upgraded before execution remains retryable after waiting login`() {
        val job = job()
        val original = decision(505L, NOW)
        val prepared = executionPayload(original)
        val action = action(job, prepared).also {
            it.payloadJson = objectMapper.writeValueAsString(original)
            it.status = AutomationActionStatus.RETRY_WAIT
            it.nextAttemptAt = NOW
        }
        Mockito.`when`(jobQueryRepository.findCurrentByAccountIdAndStatuses(ACCOUNT_ID, setOf("RUNNING")))
            .thenReturn(job)
        Mockito.`when`(jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(ACCOUNT_ID, job.id)).thenReturn(job)
        Mockito.`when`(actionQueryRepository.findByRequestKeyForUpdate("job:${job.id}:step:0")).thenReturn(action)
        Mockito.`when`(actionQueryRepository.findByIdForUpdate(action.id)).thenReturn(action)
        stubActionLock(action)

        val firstRunnable = assertNotNull(service.findRunnable(ACCOUNT_ID))
        val retry = assertNotNull(firstRunnable.retryAction)
        assertEquals(true, retry.requiresPreparation)
        val token = assertNotNull(service.resumeRetry(firstRunnable, retry, prepared))

        service.fail(token, AutomationLoginRequiredException("로그인이 필요합니다."))

        assertEquals("WAITING_LOGIN", job.status)
        assertEquals(AutomationActionStatus.RETRY_WAIT, action.status)
        assertEquals(objectMapper.writeValueAsString(prepared), action.payloadJson)

        job.status = "RUNNING"
        val resumed = assertNotNull(service.findRunnable(ACCOUNT_ID)?.retryAction)
        assertEquals(false, resumed.requiresPreparation)
        assertEquals(prepared, resumed.payload)
    }

    @Test
    fun `captcha answer terminates the old action and resumes with a fresh step`() {
        val job = job()
        val module = module()
        val original = decision(module.id, module.updatedAt)
        val payload = executionPayload(original)
        val action = action(job, payload).also { it.moduleConfig = module }
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
        val wakeup = Mockito.mock(AutomationAfterCommitWakeupService::class.java)
        val hook = CaptchaAutomationHook(actionLockCoordinator, pushOutboxService, wakeup, TimeProvider { NOW })
        stubActionLock(action)

        val token = AutomationExecutionToken(action.id, action.attemptCount)
        AutomationActionContext.withToken(token) { hook.detected(challenge) }
        hook.answered(challenge)

        assertEquals(action, challenge.automationActionRun)
        assertEquals(AutomationActionStatus.ABORTED, action.status)
        assertEquals(NOW, action.finishedAt)
        assertEquals("RUNNING", job.status)
        assertEquals(module.id, action.moduleConfig?.id)
        assertNull(job.currentModuleConfig)
        assertNull(job.currentAction)
        assertNull(job.currentModule)
        assertEquals(1, job.currentStepIndex)
        assertEquals(objectMapper.writeValueAsString(payload), action.payloadJson)

        val lateChallenge = CaptchaChallengeEntity(
            id = 78L,
            account = job.account,
            status = "PENDING",
            prompt = "늦은 인증",
            imageUrl = "late.png",
            sourceUrl = "police",
            answer = null,
            createdAt = NOW,
            answeredAt = null,
        )
        assertEquals(false, service.succeed(token))
        assertNull(service.fail(token, IllegalStateException("late")))
        AutomationActionContext.withToken(token) { hook.detected(lateChallenge) }

        assertNull(lateChallenge.automationActionRun)
        assertEquals(AutomationActionStatus.ABORTED, action.status)
        assertEquals("RUNNING", job.status)
        assertEquals(1, job.currentStepIndex)
        Mockito.verify(wakeup).wake(ACCOUNT_ID, "CAPTCHA_ANSWERED")
    }

    @Test
    fun `captcha detected by a previous attempt cannot pause the current attempt`() {
        val job = job()
        val action = action(job, executionPayload(decision(505L, NOW))).also {
            it.attemptCount = 2
            it.status = AutomationActionStatus.RUNNING
        }
        val challenge = CaptchaChallengeEntity(
            id = 79L,
            account = job.account,
            status = "PENDING",
            prompt = "늦은 인증",
            imageUrl = "late.png",
            sourceUrl = "police",
            answer = null,
            createdAt = NOW,
            answeredAt = null,
        )
        val wakeup = Mockito.mock(AutomationAfterCommitWakeupService::class.java)
        val hook = CaptchaAutomationHook(actionLockCoordinator, pushOutboxService, wakeup, TimeProvider { NOW })
        stubActionLock(action)

        AutomationActionContext.withToken(AutomationExecutionToken(action.id, 1)) {
            hook.detected(challenge)
        }

        assertNull(challenge.automationActionRun)
        assertEquals(AutomationActionStatus.RUNNING, action.status)
        assertEquals("RUNNING", job.status)
        Mockito.verifyNoInteractions(pushOutboxService)
        verifyJobThenActionLock(action)
    }

    private fun stubActionLock(action: AutomationActionRunEntity) {
        val target = AutomationActionLockTarget(action.job.id, action.job.account.id)
        Mockito.`when`(actionQueryRepository.findLockTargetById(action.id)).thenReturn(target)
        Mockito.`when`(
            jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(target.accountId, target.jobId),
        ).thenReturn(action.job)
        Mockito.`when`(actionQueryRepository.findByIdForUpdate(action.id)).thenReturn(action)
    }

    private fun verifyJobThenActionLock(action: AutomationActionRunEntity) {
        val order = Mockito.inOrder(actionQueryRepository, jobQueryRepository)
        order.verify(actionQueryRepository).findLockTargetById(action.id)
        order.verify(jobQueryRepository).findOwnedByAccountIdAndIdForUpdate(action.job.account.id, action.job.id)
        order.verify(actionQueryRepository).findByIdForUpdate(action.id)
    }

    private fun decision(moduleId: Long, revision: Instant) = AutomationDecision(
        type = AutomationDecisionType.RUN_BATTLE,
        moduleType = AutomationModuleType.TIME_BURN,
        moduleConfigId = moduleId,
        moduleRevision = revision,
        map = AutomationMapCandidate("map", "테스트 맵", 0, 301L),
    )

    private fun executionPayload(decision: AutomationDecision) = AutomationExecutionPayload(
        decision = decision,
        resolvedBattleRequest = app.spammy.hof.battle.dto.RunBattleRequest(
            categoryId = "battle_map",
            mapCode = "map",
            characterIds = listOf("character-1"),
            patternLoads = listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("character-1", 2)),
        ),
    )

    private fun action(job: AutomationJobEntity, payload: AutomationExecutionPayload) = AutomationActionRunEntity(
        id = 91L,
        job = job,
        moduleConfig = null,
        moduleType = AutomationModuleType.TIME_BURN,
        actionType = payload.decision.type.name,
        actionKey = payload.decision.map?.mapCode,
        status = AutomationActionStatus.RUNNING,
        requestKey = "job:${job.id}:step:0",
        payloadJson = objectMapper.writeValueAsString(payload),
        attemptCount = 1,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun anyAction(): AutomationActionRunEntity =
        Mockito.any(AutomationActionRunEntity::class.java)
            ?: action(job(), executionPayload(decision(101L, NOW)))

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
