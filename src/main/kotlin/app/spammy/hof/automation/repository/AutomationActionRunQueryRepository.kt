package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationActionRunEntity
import app.spammy.hof.automation.entity.QAutomationActionRunEntity.automationActionRunEntity
import app.spammy.hof.automation.entity.QAutomationJobEntity.automationJobEntity
import com.querydsl.jpa.impl.JPAQueryFactory
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

    private fun baseQuery() = queryFactory.selectFrom(automationActionRunEntity)
        .join(automationActionRunEntity.job, automationJobEntity).fetchJoin()
}
