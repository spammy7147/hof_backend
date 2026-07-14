package app.spammy.hof.automation.service

import app.spammy.hof.automation.policy.AutomationDecisionPolicy
import app.spammy.hof.automation.policy.AutomationDecisionType
import app.spammy.hof.automation.port.AutomationWakeupPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 최신 스냅샷 결정 또는 저장된 retry payload 중 action 하나만 실행하는 통합 자동화 루프다. */
@Service
class UnifiedAutomationRunner(
    private val checkpointService: AutomationCheckpointService,
    private val snapshotLoader: AutomationSnapshotLoader,
    private val decisionPolicy: AutomationDecisionPolicy,
    private val actionExecutor: AutomationActionExecutor,
    private val wakeupPort: AutomationWakeupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 한 wakeup에서 최대 action 하나만 실행하고 후속 판단은 새 wakeup과 새 스냅샷에 맡긴다. */
    fun runOne(accountId: Long) {
        val runnable = checkpointService.findRunnable(accountId) ?: return
        runnable.retryAction?.let { retry ->
            if (checkpointService.resumeRetry(runnable, retry)) {
                executeOne(retry.actionId, runnable, retry.decision)
            }
            return
        }
        val decision = decisionPolicy.decide(snapshotLoader.load(accountId))
        when (decision.type) {
            AutomationDecisionType.SLEEP -> {
                checkpointService.sleep(runnable.jobId, decision.nextRunAt)
                decision.nextRunAt?.let { wakeupPort.schedule(accountId, it, "SCHEDULED_RECHECK") }
            }
            AutomationDecisionType.WAITING_CONFIG ->
                checkpointService.blockForConfig(
                    runnable.jobId,
                    decision.message ?: "전투에 사용할 파티를 선택해 주세요.",
                )
            else -> {
                val actionId = checkpointService.start(runnable, decision)
                if (actionId == null) {
                    wakeupPort.wake(accountId, "STALE_MODULE")
                } else {
                    executeOne(actionId, runnable, decision)
                }
            }
        }
    }

    /** checkpoint가 확정한 단일 decision만 실행하고 다음 판단은 별도 wakeup에 맡긴다. */
    private fun executeOne(
        actionId: Long,
        runnable: RunnableAutomationJob,
        decision: app.spammy.hof.automation.policy.AutomationDecision,
    ) {
        runCatching {
            AutomationActionContext.withAction(actionId) {
                actionExecutor.execute(runnable.accountId, decision)
            }
        }
            .onSuccess { result ->
                checkpointService.succeed(actionId, result)
                wakeupPort.wake(runnable.accountId, "ACTION_SUCCEEDED")
            }
            .onFailure { error ->
                log.warn(
                    "Automation action failed accountId={} jobId={} actionId={} type={} errorType={}",
                    runnable.accountId,
                    runnable.jobId,
                    actionId,
                    decision.type,
                    error.javaClass.name,
                )
                checkpointService.fail(actionId, error)?.let { next ->
                    wakeupPort.schedule(runnable.accountId, next, "ACTION_RETRY")
                }
            }
    }
}
