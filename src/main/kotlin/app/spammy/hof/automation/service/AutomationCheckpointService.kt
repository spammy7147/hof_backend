package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationActionRunEntity
import app.spammy.hof.automation.entity.AutomationActionStatus
import app.spammy.hof.automation.policy.AutomationDecision
import app.spammy.hof.automation.repository.AutomationActionRunQueryRepository
import app.spammy.hof.automation.repository.AutomationActionRunRepository
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.push.service.PushOutboxService
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class AutomationCheckpointService(
    private val jobQueryRepository: AutomationJobQueryRepository,
    private val actionRepository: AutomationActionRunRepository,
    private val actionQueryRepository: AutomationActionRunQueryRepository,
    private val unifiedQueryRepository: UnifiedAutomationQueryRepository,
    private val objectMapper: ObjectMapper,
    private val timeProvider: TimeProvider,
    private val pushOutboxService: PushOutboxService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 실행 중 job과 현재 step의 재시도 action을 job 쓰기 잠금 안에서 함께 조회한다.
     *
     * RUNNING action은 5분 lease 안에서는 중복 실행하지 않는다. lease가 만료된 action은 서버 종료로
     * 결과를 알 수 없는 실행으로 보고 저장된 prepared payload를 RETRY_WAIT로 전환해 복구한다. HOF가
     * idempotency key를 지원하지 않으므로 이 경계는 exactly-once가 아니라 at-least-once이며, 오래 걸린
     * 원 요청과 복구 요청이 모두 반영될 가능성이 남는다.
     */
    @Transactional
    fun findRunnable(accountId: Long): RunnableAutomationJob? {
        val candidate = jobQueryRepository.findCurrentByAccountIdAndStatuses(accountId, setOf("RUNNING")) ?: return null
        val job = jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(accountId, candidate.id) ?: return null
        if (job.status != "RUNNING") return null
        val requestKey = requestKey(job.id, job.currentStepIndex)
        val currentAction = actionQueryRepository.findByRequestKeyForUpdate(requestKey)
        val now = timeProvider.now()
        if (currentAction?.status == AutomationActionStatus.RUNNING) {
            val leaseStartedAt = currentAction.startedAt ?: currentAction.updatedAt
            if (leaseStartedAt.isAfter(now.minusSeconds(RUNNING_ACTION_LEASE_SECONDS))) return null
            log.warn(
                "Recovering stale automation action accountId={} jobId={} actionId={} requestKey={}",
                accountId,
                job.id,
                currentAction.id,
                currentAction.requestKey,
            )
            currentAction.status = AutomationActionStatus.RETRY_WAIT
            currentAction.nextAttemptAt = now
            currentAction.lastError = "실행 결과를 확인할 수 없어 저장된 요청으로 복구합니다."
            currentAction.updatedAt = now
            job.message = "중단된 작업을 복구하고 있습니다."
            job.nextRunAt = now
            job.lastHeartbeatAt = now
            job.updatedAt = now
        }
        if (currentAction?.status == AutomationActionStatus.RETRY_WAIT) {
            if (currentAction.nextAttemptAt?.isAfter(now) == true) return null
            return RunnableAutomationJob(
                jobId = job.id,
                accountId = accountId,
                currentStepIndex = job.currentStepIndex,
                retryAction = RetryableAutomationAction(
                    actionId = currentAction.id,
                    payload = readPayload(currentAction.payloadJson),
                ),
            )
        }
        return RunnableAutomationJob(job.id, accountId, job.currentStepIndex)
    }

    /** prepared payload의 모듈 revision을 다시 확인한 뒤 action과 job에 같은 모듈 FK를 기록한다. */
    @Transactional
    fun start(
        runnable: RunnableAutomationJob,
        payload: AutomationExecutionPayload,
    ): Long? {
        val job = jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(runnable.accountId, runnable.jobId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "자동화 job을 찾지 못했습니다.")
        if (job.status != "RUNNING") throw ApiException(ErrorCode.INVALID_REQUEST, "실행 가능한 자동화 job이 아닙니다.")
        val decision = payload.decision
        val moduleId = decision.moduleConfigId
            ?: throw ApiException(ErrorCode.INVALID_REQUEST, "실행 결정에 자동화 모듈 ID가 없습니다.")
        val moduleRevision = decision.moduleRevision ?: return null
        val module = unifiedQueryRepository.findModule(runnable.accountId, moduleId)?.config
            ?.takeIf {
                it.enabled &&
                    it.profile.id == job.profile.id &&
                    it.moduleType == decision.moduleType &&
                    it.updatedAt == moduleRevision
            }
            ?: return null
        val now = timeProvider.now()
        val requestKey = requestKey(job.id, job.currentStepIndex)
        val existing = actionQueryRepository.findByRequestKey(requestKey)
        if (existing?.status in setOf(AutomationActionStatus.RUNNING, AutomationActionStatus.RETRY_WAIT)) return null
        val action = existing?.apply {
            moduleConfig = module
            moduleType = module.moduleType
            actionType = decision.type.name
            actionKey = actionKey(decision)
            status = AutomationActionStatus.RUNNING
            attemptCount += 1
            nextAttemptAt = null
            lastError = null
            payloadJson = objectMapper.writeValueAsString(payload)
            startedAt = now
            finishedAt = null
            updatedAt = now
        } ?: actionRepository.save(
            AutomationActionRunEntity(
                job = job,
                moduleConfig = module,
                moduleType = module.moduleType,
                actionType = decision.type.name,
                actionKey = actionKey(decision),
                status = AutomationActionStatus.RUNNING,
                requestKey = requestKey,
                payloadJson = objectMapper.writeValueAsString(payload),
                attemptCount = 1,
                createdAt = now,
                startedAt = now,
                updatedAt = now,
            ),
        )
        job.currentModule = decision.moduleType?.name
        job.currentModuleConfig = module
        job.currentAction = decision.map?.mapName ?: decision.questId ?: decision.type.name
        job.lastHeartbeatAt = now
        job.nextRunAt = null
        job.updatedAt = now
        return action.id
    }

    /** 저장된 RETRY_WAIT action을 동일 request key와 payload로 다시 RUNNING 상태로 전환한다. */
    @Transactional
    fun resumeRetry(
        runnable: RunnableAutomationJob,
        retry: RetryableAutomationAction,
    ): Boolean {
        val job = jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(runnable.accountId, runnable.jobId)
            ?: return false
        val action = actionQueryRepository.findById(retry.actionId) ?: return false
        val expectedRequestKey = requestKey(runnable.jobId, runnable.currentStepIndex)
        if (job.status != "RUNNING" || action.job.id != job.id) return false
        if (action.requestKey != expectedRequestKey || action.status != AutomationActionStatus.RETRY_WAIT) return false
        val now = timeProvider.now()
        if (action.nextAttemptAt?.isAfter(now) == true) return false
        action.status = AutomationActionStatus.RUNNING
        action.attemptCount += 1
        action.nextAttemptAt = null
        action.lastError = null
        action.startedAt = now
        action.finishedAt = null
        action.updatedAt = now
        job.currentModule = retry.payload.decision.moduleType?.name
        job.currentModuleConfig = action.moduleConfig
        job.currentAction = retry.payload.decision.map?.mapName
            ?: retry.payload.decision.questId
            ?: retry.payload.decision.type.name
        job.lastHeartbeatAt = now
        job.nextRunAt = null
        job.updatedAt = now
        return true
    }

    /** 성공한 action의 결과를 보존하고 step을 증가시킨 뒤 현재 실행 표시를 비운다. */
    @Transactional
    fun succeed(actionId: Long) {
        val action = findAction(actionId)
        val now = timeProvider.now()
        action.status = AutomationActionStatus.SUCCEEDED
        action.finishedAt = now
        action.updatedAt = now
        action.job.currentStepIndex += 1
        clearCurrentAction(action.job)
        action.job.nextRunAt = now
        action.job.lastHeartbeatAt = now
        action.job.updatedAt = now
    }

    /** 실패 유형에 따라 캡차·로그인·설정 대기 또는 지수 backoff 재시도로 전환한다. */
    @Transactional
    fun fail(
        actionId: Long,
        error: Throwable,
    ): Instant? {
        val action = findAction(actionId)
        val now = timeProvider.now()
        action.lastError = error.message?.take(2000) ?: error.javaClass.simpleName
        action.updatedAt = now
        action.job.updatedAt = now
        action.job.lastHeartbeatAt = now
        val apiError = error as? ApiException
        return when (apiError?.errorCode) {
            ErrorCode.CAPTCHA_REQUIRED -> {
                action.status = AutomationActionStatus.WAITING_CAPTCHA
                action.job.status = "WAITING_CAPTCHA"
                action.job.message = "인증이 필요합니다."
                null
            }
            else -> if (error is AutomationConfigurationException) {
                action.status = AutomationActionStatus.WAITING_CONFIG
                action.job.status = "WAITING_CONFIG"
                action.job.message = error.message
                action.job.nextRunAt = null
                clearCurrentAction(action.job)
                pushOutboxService.enqueueLoginRequired(action.job.account)
                null
            } else if (error is AutomationLoginRequiredException) {
                action.status = AutomationActionStatus.RETRY_WAIT
                action.job.status = "WAITING_LOGIN"
                action.job.message = error.message
                action.job.nextRunAt = null
                null
            } else {
                val next = now.plusSeconds(retrySeconds(action.attemptCount))
                action.status = AutomationActionStatus.RETRY_WAIT
                action.nextAttemptAt = next
                action.job.nextRunAt = next
                action.job.message = "잠시 후 자동으로 다시 시도합니다."
                next
            }
        }
    }

    /** 실행할 수 없는 설정만 남았을 때 job을 설정 대기로 전환하고 현재 모듈 표시를 비운다. */
    @Transactional
    fun blockForConfig(
        jobId: Long,
        message: String,
    ) {
        val job = jobQueryRepository.findCurrentByAccountIdAndStatusesForJobId(jobId, setOf("RUNNING")) ?: return
        job.status = "WAITING_CONFIG"
        job.message = message
        clearCurrentAction(job)
        job.nextRunAt = null
        job.updatedAt = timeProvider.now()
    }

    /** 실행 가능한 action이 없을 때 다음 재확인 시각을 저장하고 현재 모듈 표시를 비운다. */
    @Transactional
    fun sleep(
        jobId: Long,
        nextRunAt: Instant?,
    ) {
        val job = jobQueryRepository.findCurrentByAccountIdAndStatusesForJobId(jobId, setOf("RUNNING")) ?: return
        clearCurrentAction(job)
        job.message = "다음 작업 대기 중"
        job.nextRunAt = nextRunAt
        job.updatedAt = timeProvider.now()
    }

    private fun findAction(id: Long): AutomationActionRunEntity = actionQueryRepository.findById(id)
        ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "자동화 action을 찾지 못했습니다.")

    private fun actionKey(decision: AutomationDecision): String? =
        decision.questId ?: decision.map?.mapCode

    private fun requestKey(jobId: Long, stepIndex: Int): String = "job:$jobId:step:$stepIndex"

    private fun readPayload(payloadJson: String): AutomationExecutionPayload =
        objectMapper.readValue(payloadJson, AutomationExecutionPayload::class.java)

    /** 다음 판단으로 넘어가는 상태에서 현재 action 표시와 모듈 FK를 함께 비운다. */
    private fun clearCurrentAction(job: app.spammy.hof.automation.entity.AutomationJobEntity) {
        job.currentAction = null
        job.currentModule = null
        job.currentModuleConfig = null
    }

    private fun retrySeconds(attempt: Int): Long = minOf(300L, 5L shl minOf(6, (attempt - 1).coerceAtLeast(0)))

    private companion object {
        const val RUNNING_ACTION_LEASE_SECONDS = 300L
    }
}
