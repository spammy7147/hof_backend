package app.spammy.hof.automation.service

import app.spammy.hof.auth.service.AccountExecutionSubmissionGate
import app.spammy.hof.automation.convergence.AutomationActionEvidence
import java.time.Instant
import org.slf4j.LoggerFactory
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.town.common.service.AccountHofObservationInvalidatedException
import app.spammy.hof.town.common.service.ObservedTownActionPreconditionChangedException

internal fun Throwable.findHofAutomationDeferral(): HofAutomationDeferredException? =
    generateSequence(this) { it.cause }.filterIsInstance<HofAutomationDeferredException>().firstOrNull()

internal fun Throwable.findActionPreconditionChanged(): AutomationActionPreconditionChangedException? =
    generateSequence(this) { it.cause }
        .filterIsInstance<AutomationActionPreconditionChangedException>()
        .firstOrNull()
        ?: generateSequence(this) { it.cause }
            .filterIsInstance<AccountHofObservationInvalidatedException>()
            .firstOrNull()
            ?.let { invalidated ->
                AutomationActionPreconditionChangedException(
                    invalidated.message ?: "관측 뒤 계정 상태가 변경되었습니다.",
                    invalidated,
                )
            }
        ?: generateSequence(this) { it.cause }
            .filterIsInstance<ObservedTownActionPreconditionChangedException>()
            .firstOrNull()
            ?.let { changed ->
                AutomationActionPreconditionChangedException(
                    changed.message ?: "관측한 작업 양식이 변경되었습니다.",
                    changed,
                )
            }


private val log = LoggerFactory.getLogger("app.spammy.hof.automation.service.AutomationSubmission")
private val SUBMISSION_NOT_EXECUTED = Any()
private const val AUTHORIZATION_ENDED_REASON = "AUTHORIZATION_ENDED_BEFORE_SUBMISSION"
internal data class AuthorizedExecution<T>(val authorized: Boolean, val value: T?)

internal fun <T> AccountExecutionSubmissionGate.executeAuthorized(accountId: Long, submission: () -> T): AuthorizedExecution<T> {
    var result: Any? = SUBMISSION_NOT_EXECUTED
    val authorized = executeIfAuthorized(accountId, Runnable { result = submission() })
    if (!authorized) return AuthorizedExecution(false, null)
    check(result !== SUBMISSION_NOT_EXECUTED) { "Authorized submission did not execute." }
    @Suppress("UNCHECKED_CAST")
    return AuthorizedExecution(true, result as T)
}

internal fun TypedAutomationRuntimeService.discardUnauthorizedSubmission(
    accountId: Long,
    execution: TypedRuntimeExecutionRight,
    convergenceAttemptId: Long?,
    results: AutomationResultCoordinator,
    observedAt: Instant,
) {
    convergenceAttemptId?.let { attemptId ->
        results.record(
            attemptId,
            AutomationActionEvidence.DirectRejected(observedAt, AUTHORIZATION_ENDED_REASON),
        )
    }
    complete(
        execution,
        TypedRuntimeOutcome.SubmissionDeferred(observedAt, "로그아웃되어 제출하지 않은 자동화 행동을 폐기했습니다."),
    )
    log.info("Discarded unsubmitted typed action after authentication ended accountId={}", accountId)
}
