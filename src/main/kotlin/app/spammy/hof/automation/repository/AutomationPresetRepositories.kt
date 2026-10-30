package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationPresetEntity
import app.spammy.hof.automation.entity.QAutomationPresetEntity.automationPresetEntity
import app.spammy.hof.automation.lease.QAccountAutomationLeaseEntity.accountAutomationLeaseEntity
import app.spammy.hof.common.persistence.CommandRepository
import com.querydsl.jpa.impl.JPAQueryFactory
import java.time.Instant
import org.springframework.stereotype.Repository

interface AutomationPresetCommandRepository : CommandRepository<AutomationPresetEntity, Long>

@Repository
class AutomationPresetQueryRepository(private val query: JPAQueryFactory) {
    fun findAll(accountId: Long): List<AutomationPresetEntity> = query.selectFrom(automationPresetEntity)
        .where(automationPresetEntity.account.id.eq(accountId))
        .orderBy(automationPresetEntity.id.asc()).fetch()

    fun findOwned(accountId: Long, presetId: Long): AutomationPresetEntity? = query.selectFrom(automationPresetEntity)
        .where(automationPresetEntity.account.id.eq(accountId), automationPresetEntity.id.eq(presetId)).fetchOne()

    fun hasExecutionLease(accountId: Long, now: Instant): Boolean = query.selectOne().from(accountAutomationLeaseEntity)
        .where(accountAutomationLeaseEntity.accountId.eq(accountId), accountAutomationLeaseEntity.leaseUntil.gt(now))
        .fetchFirst() != null
}
