package app.spammy.hof.automation.service

import app.spammy.hof.automation.policy.AutomationDecisionPolicy
import app.spammy.hof.automation.policy.AutomationDecisionType
import app.spammy.hof.automation.port.AutomationWakeupPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class UnifiedAutomationRunner(
    private val checkpointService: AutomationCheckpointService,
    private val snapshotLoader: AutomationSnapshotLoader,
    private val decisionPolicy: AutomationDecisionPolicy,
    private val actionExecutor: AutomationActionExecutor,
    private val wakeupPort: AutomationWakeupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runOne(accountId: Long) {
        val runnable = checkpointService.findRunnable(accountId) ?: return
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
            else -> executeOne(runnable, decision)
        }
    }

    private fun executeOne(
        runnable: RunnableAutomationJob,
        decision: app.spammy.hof.automation.policy.AutomationDecision,
    ) {
        val actionId = checkpointService.start(runnable, decision)
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
                    "Automation action failed accountId={} jobId={} actionId={} type={} error={}",
                    runnable.accountId,
                    runnable.jobId,
                    actionId,
                    decision.type,
                    error.message,
                )
                checkpointService.fail(actionId, error)?.let { next ->
                    wakeupPort.schedule(runnable.accountId, next, "ACTION_RETRY")
                }
            }
    }
}
