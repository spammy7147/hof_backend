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
    private val actionLockCoordinator: AutomationActionLockCoordinator,
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
            val decoded = try {
                readPayload(currentAction.payloadJson)
            } catch (error: Exception) {
                markMalformedPayload(currentAction, now, error)
                return null
            }
            return RunnableAutomationJob(
                jobId = job.id,
                accountId = accountId,
                currentStepIndex = job.currentStepIndex,
                retryAction = RetryableAutomationAction(
                    actionId = currentAction.id,
                    payload = decoded.payload,
                    requiresPreparation = decoded.requiresPreparation,
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
    ): AutomationExecutionToken? {
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
        val existing = actionQueryRepository.findByRequestKeyForUpdate(requestKey)
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
        return AutomationExecutionToken(action.id, action.attemptCount)
    }

    /**
     * RETRY_WAIT action을 잠그고 준비 완료 payload를 저장한 뒤 새 실행 시도 토큰을 발급한다.
     *
     * 레거시 decision payload는 이 트랜잭션에서 새 prepared payload로 교체된다. 이미 새 형식인 재시도는
     * 조회 시점의 exact payload와 동일한 값만 허용해 프리셋·패턴 변경이 현재 action에 섞이지 않게 한다.
     */
    @Transactional
    fun resumeRetry(
        runnable: RunnableAutomationJob,
        retry: RetryableAutomationAction,
        preparedPayload: AutomationExecutionPayload,
    ): AutomationExecutionToken? {
        val job = jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(runnable.accountId, runnable.jobId)
            ?: return null
        val action = actionQueryRepository.findByIdForUpdate(retry.actionId) ?: return null
        val expectedRequestKey = requestKey(runnable.jobId, runnable.currentStepIndex)
        if (job.status != "RUNNING" || action.job.id != job.id) return null
        if (action.requestKey != expectedRequestKey || action.status != AutomationActionStatus.RETRY_WAIT) return null
        if (!isPreparedPayload(preparedPayload) || preparedPayload.decision != retry.payload.decision) return null
        if (!retry.requiresPreparation && preparedPayload != retry.payload) return null
        val now = timeProvider.now()
        if (action.nextAttemptAt?.isAfter(now) == true) return null
        action.payloadJson = objectMapper.writeValueAsString(preparedPayload)
        action.status = AutomationActionStatus.RUNNING
        action.attemptCount += 1
        action.nextAttemptAt = null
        action.lastError = null
        action.startedAt = now
        action.finishedAt = null
        action.updatedAt = now
        job.currentModule = preparedPayload.decision.moduleType?.name
        job.currentModuleConfig = action.moduleConfig
        job.currentAction = preparedPayload.decision.map?.mapName
            ?: preparedPayload.decision.questId
            ?: preparedPayload.decision.type.name
        job.lastHeartbeatAt = now
        job.nextRunAt = null
        job.updatedAt = now
        return AutomationExecutionToken(action.id, action.attemptCount)
    }

    /**
     * 토큰과 일치하는 RUNNING 시도만 성공 처리한다.
     *
     * 이전 시도의 늦은 완료나 캡차 답변 뒤 ABORTED된 action은 상태와 step을 전혀 변경하지 않는다.
     */
    @Transactional
    fun succeed(token: AutomationExecutionToken): Boolean {
        val locked = findCurrentAttempt(token, "success") ?: return false
        val action = locked.action
        val job = locked.job
        val now = timeProvider.now()
        action.status = AutomationActionStatus.SUCCEEDED
        action.finishedAt = now
        action.updatedAt = now
        job.currentStepIndex += 1
        clearCurrentAction(job)
        job.nextRunAt = now
        job.lastHeartbeatAt = now
        job.updatedAt = now
        return true
    }

    /** 실패 유형에 따라 캡차·로그인·설정 대기 또는 지수 backoff 재시도로 전환한다. */
    @Transactional
    fun fail(
        token: AutomationExecutionToken,
        error: Throwable,
    ): Instant? {
        val locked = findCurrentAttempt(token, "failure") ?: return null
        val action = locked.action
        val job = locked.job
        val now = timeProvider.now()
        action.lastError = error.message?.take(2000) ?: error.javaClass.simpleName
        action.updatedAt = now
        job.updatedAt = now
        job.lastHeartbeatAt = now
        val apiError = error as? ApiException
        return when (apiError?.errorCode) {
            ErrorCode.CAPTCHA_REQUIRED -> {
                action.status = AutomationActionStatus.WAITING_CAPTCHA
                job.status = "WAITING_CAPTCHA"
                job.message = "인증이 필요합니다."
                null
            }
            else -> if (error is AutomationConfigurationException) {
                action.status = AutomationActionStatus.WAITING_CONFIG
                job.status = "WAITING_CONFIG"
                job.message = error.message
                job.nextRunAt = null
                clearCurrentAction(job)
                pushOutboxService.enqueueLoginRequired(job.account)
                null
            } else if (error is AutomationLoginRequiredException) {
                action.status = AutomationActionStatus.RETRY_WAIT
                job.status = "WAITING_LOGIN"
                job.message = error.message
                job.nextRunAt = null
                null
            } else {
                val next = now.plusSeconds(retrySeconds(action.attemptCount))
                action.status = AutomationActionStatus.RETRY_WAIT
                action.nextAttemptAt = next
                job.nextRunAt = next
                job.message = "잠시 후 자동으로 다시 시도합니다."
                next
            }
        }
    }

    /** 레거시 payload 준비가 불가능하면 외부 호출 전에 기존 action과 job을 설정 대기로 고정한다. */
    @Transactional
    fun blockRetryForConfig(
        runnable: RunnableAutomationJob,
        retry: RetryableAutomationAction,
        message: String,
    ) {
        val job = jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(runnable.accountId, runnable.jobId) ?: return
        val action = actionQueryRepository.findByIdForUpdate(retry.actionId) ?: return
        val expectedRequestKey = requestKey(runnable.jobId, runnable.currentStepIndex)
        if (job.status != "RUNNING" || action.job.id != job.id) return
        if (action.requestKey != expectedRequestKey || action.status != AutomationActionStatus.RETRY_WAIT) return
        val now = timeProvider.now()
        action.status = AutomationActionStatus.WAITING_CONFIG
        action.nextAttemptAt = null
        action.lastError = message.take(2000)
        action.updatedAt = now
        job.status = "WAITING_CONFIG"
        job.message = message
        job.nextRunAt = null
        job.lastHeartbeatAt = now
        job.updatedAt = now
        clearCurrentAction(job)
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

    /** row lock 뒤 status와 attempt가 모두 일치하는 현재 시도만 반환한다. */
    private fun findCurrentAttempt(
        token: AutomationExecutionToken,
        completionType: String,
    ): LockedAutomationAction? {
        val locked = actionLockCoordinator.lock(token.actionId)
        if (locked == null) {
            log.debug(
                "Ignoring automation {} for missing action actionId={} attempt={}",
                completionType,
                token.actionId,
                token.attemptCount,
            )
            return null
        }
        val action = locked.action
        if (action.status != AutomationActionStatus.RUNNING || action.attemptCount != token.attemptCount) {
            log.debug(
                "Ignoring stale automation {} actionId={} attempt={} currentAttempt={} currentStatus={}",
                completionType,
                token.actionId,
                token.attemptCount,
                action.attemptCount,
                action.status,
            )
            return null
        }
        return locked
    }

    private fun actionKey(decision: AutomationDecision): String? =
        decision.questId ?: decision.map?.mapCode

    private fun requestKey(jobId: Long, stepIndex: Int): String = "job:$jobId:step:$stepIndex"

    /** 새 prepared payload를 우선 읽고, 과거 최상위 decision JSON은 실행 전 준비 대상으로 승격한다. */
    private fun readPayload(payloadJson: String): DecodedPayload {
        runCatching {
            objectMapper.readValue(payloadJson, AutomationExecutionPayload::class.java)
        }.getOrNull()?.takeIf(::isPreparedPayload)?.let { payload ->
            return DecodedPayload(payload, requiresPreparation = false)
        }

        val legacyDecision = objectMapper.readValue(payloadJson, AutomationDecision::class.java)
        return DecodedPayload(
            payload = AutomationExecutionPayload(decision = legacyDecision),
            requiresPreparation = true,
        )
    }

    /** 새 형식은 action 종류에 필요한 exact 외부 요청이 실제로 포함된 경우에만 준비 완료로 인정한다. */
    private fun isPreparedPayload(payload: AutomationExecutionPayload): Boolean = when (payload.decision.type) {
        app.spammy.hof.automation.policy.AutomationDecisionType.RUN_BATTLE -> payload.resolvedBattleRequest != null
        app.spammy.hof.automation.policy.AutomationDecisionType.ACCEPT_QUEST,
        app.spammy.hof.automation.policy.AutomationDecisionType.CLAIM_QUEST,
        -> payload.resolvedActionNo != null
        app.spammy.hof.automation.policy.AutomationDecisionType.WAITING_CONFIG,
        app.spammy.hof.automation.policy.AutomationDecisionType.SLEEP,
        -> false
    }

    /** 손상 payload를 재조회 때마다 다시 역직렬화하지 않도록 action과 job을 명시적 안전 상태로 종료한다. */
    private fun markMalformedPayload(
        action: AutomationActionRunEntity,
        now: Instant,
        error: Exception,
    ) {
        log.warn(
            "Automation retry payload unreadable jobId={} actionId={} attempt={} errorType={}",
            action.job.id,
            action.id,
            action.attemptCount,
            error.javaClass.name,
        )
        action.status = AutomationActionStatus.FAILED
        action.nextAttemptAt = null
        action.lastError = "저장된 자동화 실행 정보를 읽을 수 없습니다."
        action.finishedAt = now
        action.updatedAt = now
        action.job.status = "WAITING_CONFIG"
        action.job.message = "저장된 실행 정보를 확인할 수 없습니다. 자동화 설정을 다시 저장해 주세요."
        action.job.nextRunAt = null
        action.job.lastHeartbeatAt = now
        action.job.updatedAt = now
        clearCurrentAction(action.job)
    }

    /** 다음 판단으로 넘어가는 상태에서 현재 action 표시와 모듈 FK를 함께 비운다. */
    private fun clearCurrentAction(job: app.spammy.hof.automation.entity.AutomationJobEntity) {
        job.currentAction = null
        job.currentModule = null
        job.currentModuleConfig = null
    }

    private fun retrySeconds(attempt: Int): Long = minOf(300L, 5L shl minOf(6, (attempt - 1).coerceAtLeast(0)))

    private data class DecodedPayload(
        val payload: AutomationExecutionPayload,
        val requiresPreparation: Boolean,
    )

    private companion object {
        const val RUNNING_ACTION_LEASE_SECONDS = 300L
    }
}
