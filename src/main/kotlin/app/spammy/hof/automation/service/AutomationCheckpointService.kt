package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationActionStatus
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.push.service.PushOutboxService
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 이전 프로필 job/action 행의 완료·실패 checkpoint를 안전하게 마무리하는 호환 서비스다.
 *
 * 새 자동화 실행은 타입별 runtime checkpoint를 사용하며 이 서비스는 범용 모듈 결정이나 새 action
 * 생성을 수행하지 않는다.
 */
@Service
class AutomationCheckpointService(
    private val jobQueryRepository: AutomationJobQueryRepository,
    private val actionLockCoordinator: AutomationActionLockCoordinator,
    private val timeProvider: TimeProvider,
    private val pushOutboxService: PushOutboxService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 토큰과 일치하는 RUNNING 시도만 성공 처리한다. */
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
    fun fail(token: AutomationExecutionToken, error: Throwable): Instant? {
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
            else -> when (error) {
                is AutomationConfigurationException -> {
                    action.status = AutomationActionStatus.WAITING_CONFIG
                    job.status = "WAITING_CONFIG"
                    job.message = error.message
                    job.nextRunAt = null
                    clearCurrentAction(job)
                    pushOutboxService.enqueueLoginRequired(job.account)
                    null
                }
                is AutomationLoginRequiredException -> {
                    action.status = AutomationActionStatus.RETRY_WAIT
                    job.status = "WAITING_LOGIN"
                    job.message = error.message
                    job.nextRunAt = null
                    null
                }
                else -> {
                    val next = now.plusSeconds(retrySeconds(action.attemptCount))
                    action.status = AutomationActionStatus.RETRY_WAIT
                    action.nextAttemptAt = next
                    job.nextRunAt = next
                    job.message = "잠시 후 자동으로 다시 시도합니다."
                    next
                }
            }
        }
    }

    /** 실행할 수 없는 설정만 남은 호환 job을 설정 대기로 전환한다. */
    @Transactional
    fun blockForConfig(jobId: Long, message: String) {
        val job = jobQueryRepository.findCurrentByAccountIdAndStatusesForJobId(jobId, setOf("RUNNING")) ?: return
        job.status = "WAITING_CONFIG"
        job.message = message
        clearCurrentAction(job)
        job.nextRunAt = null
        job.updatedAt = timeProvider.now()
    }

    /** 실행 가능한 action이 없는 호환 job의 다음 재확인 시각을 저장한다. */
    @Transactional
    fun sleep(jobId: Long, nextRunAt: Instant?) {
        val job = jobQueryRepository.findCurrentByAccountIdAndStatusesForJobId(jobId, setOf("RUNNING")) ?: return
        clearCurrentAction(job)
        job.message = "다음 작업 대기 중"
        job.nextRunAt = nextRunAt
        job.updatedAt = timeProvider.now()
    }

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

    private fun clearCurrentAction(job: app.spammy.hof.automation.entity.AutomationJobEntity) {
        job.currentAction = null
        job.currentModule = null
        job.currentModuleConfigId = null
    }

    private fun retrySeconds(attempt: Int): Long =
        minOf(300L, 5L shl minOf(6, (attempt - 1).coerceAtLeast(0)))
}
