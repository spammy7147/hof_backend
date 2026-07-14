package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationActionRunEntity
import app.spammy.hof.automation.entity.QAutomationActionRunEntity.automationActionRunEntity
import app.spammy.hof.automation.entity.QAutomationJobEntity.automationJobEntity
import app.spammy.hof.automation.entity.QAutomationModuleConfigEntity.automationModuleConfigEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Repository

@Repository
class AutomationActionRunQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findById(id: Long): AutomationActionRunEntity? = baseQuery()
        .where(automationActionRunEntity.id.eq(id))
        .fetchOne()

    /**
     * action 쓰기 잠금 전에 잠글 job의 식별자만 조회한다.
     *
     * 이 조회는 잠금을 획득하지 않는다. 호출자는 반환된 job을 먼저 쓰기 잠근 뒤 action을 잠그고 FK를
     * 다시 검증해야 하며, 그 순서를 통해 lease 복구와 늦은 완료 사이의 잠금 역전을 방지한다.
     */
    fun findLockTargetById(id: Long): AutomationActionLockTarget? = queryFactory
        .select(
            automationActionRunEntity.job.id,
            automationActionRunEntity.job.account.id,
        )
        .from(automationActionRunEntity)
        .where(automationActionRunEntity.id.eq(id))
        .fetchOne()
        ?.let { row ->
            AutomationActionLockTarget(
                jobId = requireNotNull(row.get(automationActionRunEntity.job.id)),
                accountId = requireNotNull(row.get(automationActionRunEntity.job.account.id)),
            )
        }

    /** 성공·실패·캡차 전환 전 status와 attempt를 원자적으로 검증하도록 action row를 잠근다. */
    fun findByIdForUpdate(id: Long): AutomationActionRunEntity? = baseQuery()
        .where(automationActionRunEntity.id.eq(id))
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .fetchOne()

    fun findByRequestKey(requestKey: String): AutomationActionRunEntity? = baseQuery()
        .where(automationActionRunEntity.requestKey.eq(requestKey))
        .fetchOne()

    /** job 행을 먼저 잠근 checkpoint 트랜잭션에서 현재 action도 쓰기 잠금으로 고정한다. */
    fun findByRequestKeyForUpdate(requestKey: String): AutomationActionRunEntity? =
        queryFactory.selectFrom(automationActionRunEntity)
            .join(automationActionRunEntity.job, automationJobEntity).fetchJoin()
            .where(automationActionRunEntity.requestKey.eq(requestKey))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchOne()

    private fun baseQuery() = queryFactory.selectFrom(automationActionRunEntity)
        .join(automationActionRunEntity.job, automationJobEntity).fetchJoin()
        .leftJoin(automationActionRunEntity.moduleConfig, automationModuleConfigEntity).fetchJoin()
}

/** action이 속한 job을 먼저 잠그기 위한 최소 무잠금 조회 결과다. */
data class AutomationActionLockTarget(
    val jobId: Long,
    val accountId: Long,
)
