package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationActionRunEntity
import app.spammy.hof.automation.entity.AutomationActionStatus
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.policy.AutomationDecision
import app.spammy.hof.automation.repository.AutomationActionRunQueryRepository
import app.spammy.hof.automation.repository.AutomationActionRunRepository
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import app.spammy.hof.push.service.PushOutboxService

@Service
class AutomationCheckpointService(
    private val jobQueryRepository: AutomationJobQueryRepository,
    private val actionRepository: AutomationActionRunRepository,
    private val actionQueryRepository: AutomationActionRunQueryRepository,
    private val objectMapper: ObjectMapper,
    private val timeProvider: TimeProvider,
    private val pushOutboxService: PushOutboxService,
) {
    @Transactional(readOnly = true)
    fun findRunnable(accountId: Long): RunnableAutomationJob? =
        jobQueryRepository.findCurrentByAccountIdAndStatuses(accountId, setOf("RUNNING"))?.let { job ->
            RunnableAutomationJob(job.id, accountId, job.currentStepIndex)
        }

    @Transactional
    fun start(
        runnable: RunnableAutomationJob,
        decision: AutomationDecision,
    ): Long {
        val job = jobQueryRepository.findOwnedByAccountIdAndId(runnable.accountId, runnable.jobId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "자동화 job을 찾지 못했습니다.")
        if (job.status != "RUNNING") throw ApiException(ErrorCode.INVALID_REQUEST, "실행 가능한 자동화 job이 아닙니다.")
        val now = timeProvider.now()
        val requestKey = "job:${job.id}:step:${job.currentStepIndex}"
        val action = actionQueryRepository.findByRequestKey(requestKey)?.apply {
            status = AutomationActionStatus.RUNNING
            attemptCount += 1
            nextAttemptAt = null
            lastError = null
            startedAt = now
            finishedAt = null
            updatedAt = now
        } ?: actionRepository.save(
            AutomationActionRunEntity(
                job = job,
                moduleType = decision.moduleType ?: AutomationModuleType.NORMAL_MAP,
                actionType = decision.type.name,
                actionKey = actionKey(decision),
                status = AutomationActionStatus.RUNNING,
                requestKey = requestKey,
                payloadJson = objectMapper.writeValueAsString(decision),
                attemptCount = 1,
                createdAt = now,
                startedAt = now,
                updatedAt = now,
            ),
        )
        job.currentModule = decision.moduleType?.name
        job.currentAction = decision.map?.mapName ?: decision.questId ?: decision.type.name
        job.lastHeartbeatAt = now
        job.nextRunAt = null
        job.updatedAt = now
        return action.id
    }

    @Transactional
    fun succeed(
        actionId: Long,
        resultJson: String,
    ) {
        val action = findAction(actionId)
        val now = timeProvider.now()
        action.status = AutomationActionStatus.SUCCEEDED
        action.payloadJson = resultJson
        action.finishedAt = now
        action.updatedAt = now
        action.job.currentStepIndex += 1
        action.job.currentAction = null
        action.job.nextRunAt = now
        action.job.lastHeartbeatAt = now
        action.job.updatedAt = now
    }

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

    @Transactional
    fun blockForConfig(
        jobId: Long,
        message: String,
    ) {
        val job = jobQueryRepository.findCurrentByAccountIdAndStatusesForJobId(jobId, setOf("RUNNING")) ?: return
        job.status = "WAITING_CONFIG"
        job.message = message
        job.currentAction = null
        job.nextRunAt = null
        job.updatedAt = timeProvider.now()
    }

    @Transactional
    fun sleep(
        jobId: Long,
        nextRunAt: Instant?,
    ) {
        val job = jobQueryRepository.findCurrentByAccountIdAndStatusesForJobId(jobId, setOf("RUNNING")) ?: return
        job.currentAction = null
        job.message = "다음 작업 대기 중"
        job.nextRunAt = nextRunAt
        job.updatedAt = timeProvider.now()
    }

    private fun findAction(id: Long): AutomationActionRunEntity = actionQueryRepository.findById(id)
        ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "자동화 action을 찾지 못했습니다.")

    private fun actionKey(decision: AutomationDecision): String? =
        decision.questId ?: decision.map?.mapCode ?: decision.unionTarget?.targetId

    private fun retrySeconds(attempt: Int): Long = minOf(300L, 5L shl minOf(6, (attempt - 1).coerceAtLeast(0)))
}
