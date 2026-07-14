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
