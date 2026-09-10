package app.spammy.hof.automation.service

import app.spammy.hof.automation.history.AutomationActionTrace
import app.spammy.hof.automation.history.AutomationDecisionJournal
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

private val log = LoggerFactory.getLogger("app.spammy.hof.automation.service.AutomationDirectResultCommit")

internal data class DirectResultHistory(val journal: AutomationDecisionJournal, val cycleId: Long, val trace: AutomationActionTrace)

/** 직접 사실과 작성할 이력을 함께 commit하고 실제 이력 INSERT는 별도로 재시도한다. */
internal fun <T> commitDirectResult(
    executionIdentity: String,
    persistResult: () -> Unit,
    history: DirectResultHistory?,
    commit: (() -> Unit) -> T,
): T {
    var accepted = false
    var deferredHistoryId: Long? = null
    fun commitAttempt(attempt: Int): T {
        accepted = false
        var callbackFailure: RuntimeException? = null
        var rollbackConfirmed = false
        try {
            return commit {
                var callbackSucceeded = false
                val synchronized = TransactionSynchronizationManager.isSynchronizationActive()
                if (synchronized) {
                    TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                        override fun afterCompletion(status: Int) {
                            rollbackConfirmed = status == TransactionSynchronization.STATUS_ROLLED_BACK
                            if (callbackSucceeded && status == TransactionSynchronization.STATUS_COMMITTED) accepted = true
                        }
                    })
                }
                try {
                    persistResult()
                    deferredHistoryId = history?.journal?.deferActionResult(history.cycleId, executionIdentity, history.trace)
                    callbackSucceeded = true
                    if (!synchronized) accepted = true
                } catch (error: RuntimeException) {
                    callbackFailure = error
                    throw error
                }
            }
        } catch (error: Exception) {
            if (attempt == 0 && callbackFailure === error && rollbackConfirmed) {
                log.warn("Retrying direct result DB write after confirmed rollback executionIdentity={}", executionIdentity)
                return commitAttempt(1)
            }
            throw DirectResultPersistenceFailure(error)
        }
    }
    val result = commitAttempt(0)
    // projection=false라도 원래 행동의 늦은 직접 사실은 수용될 수 있다.
    if (accepted) try {
        deferredHistoryId?.let { history?.journal?.publishDeferredResult(it) }
    } catch (error: RuntimeException) {
        log.warn("Direct result history unavailable after commit executionIdentity={} errorType={}",
            executionIdentity, error.javaClass.name)
    }
    return result
}

internal class DirectResultPersistenceFailure(cause: Exception) : RuntimeException("Known direct result persistence failed.", cause)
