package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationActionRunEntity
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.repository.AutomationActionRunQueryRepository
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import org.springframework.stereotype.Component

/**
 * action ID로 시작하는 모든 상태 전이를 `job -> action` 잠금 순서로 직렬화한다.
 *
 * 첫 조회는 잠금 없는 ID projection이므로 action row를 선점하지 않는다. 이후 job과 action을 차례로
 * `PESSIMISTIC_WRITE` 잠근 다음 소유 관계를 재검증한다. 조회 사이에 삭제되거나 관계가 달라진 경우에는
 * 변경하지 않고 `null`을 반환한다.
 */
@Component
class AutomationActionLockCoordinator(
    private val jobQueryRepository: AutomationJobQueryRepository,
    private val actionQueryRepository: AutomationActionRunQueryRepository,
) {
    /** 잠금 순서와 FK 재검증을 마친 job/action 쌍을 반환한다. 호출자는 같은 트랜잭션 안에서 사용해야 한다. */
    fun lock(actionId: Long): LockedAutomationAction? {
        val target = actionQueryRepository.findLockTargetById(actionId) ?: return null
        val job = jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(target.accountId, target.jobId) ?: return null
        val action = actionQueryRepository.findByIdForUpdate(actionId) ?: return null
        if (action.job.id != job.id || action.job.account.id != target.accountId) return null
        return LockedAutomationAction(job, action)
    }
}

/** 결정된 잠금 순서로 쓰기 잠금이 유지되는 자동화 job/action 쌍이다. */
data class LockedAutomationAction(
    val job: AutomationJobEntity,
    val action: AutomationActionRunEntity,
)
