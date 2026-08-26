package app.spammy.hof.automation.service

import java.time.Instant

sealed interface AmbiguousActionResolution {
    data class Applied(
        val execution: TypedAutomationExecution = TypedAutomationExecution.Completed,
    ) : AmbiguousActionResolution

    data object Resubmit : AmbiguousActionResolution

    data class Superseded(
        val reason: String,
    ) : AmbiguousActionResolution

    /** 이전 제출의 결과는 귀속하지 않고, 완전한 최신 목표 상태에서 새 행동을 고른다. */
    data class FreshDecision(
        val reason: String,
    ) : AmbiguousActionResolution

    data class VerifyLater(
        val retryAt: Instant,
        val reason: String,
    ) : AmbiguousActionResolution

    /** 저장 POST는 닫고 해당 자동화 범위만 별도 관측/사용자 해제까지 보류한다. */
    data class Held(
        val reason: String,
    ) : AmbiguousActionResolution

    data class HandedOff(
        val retryAt: Instant,
        val reason: String,
    ) : AmbiguousActionResolution
}
