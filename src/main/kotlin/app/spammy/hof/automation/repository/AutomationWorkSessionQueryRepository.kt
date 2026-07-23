package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationWorkSessionEntity
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.QAutomationWorkSessionEntity.automationWorkSessionEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class AutomationWorkSessionQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findRunning(accountId: Long): AutomationWorkSessionEntity? =
        queryFactory.selectFrom(automationWorkSessionEntity)
            .where(
                automationWorkSessionEntity.account.id.eq(accountId),
                automationWorkSessionEntity.status.eq(AutomationWorkStatus.RUNNING),
            )
            .orderBy(automationWorkSessionEntity.id.desc())
            .fetchFirst()

    fun findWaiting(accountId: Long): List<AutomationWorkSessionEntity> =
        queryFactory.selectFrom(automationWorkSessionEntity)
            .where(
                automationWorkSessionEntity.account.id.eq(accountId),
                automationWorkSessionEntity.status.`in`(
                    AutomationWorkStatus.WAITING_RESOURCE,
                    AutomationWorkStatus.WAITING_COOLDOWN,
                    AutomationWorkStatus.YIELDED_PRIORITY,
                ),
            )
            .orderBy(automationWorkSessionEntity.id.asc())
            .fetch()

    fun findDue(now: Instant): AutomationWorkSessionEntity? =
        findDue(now, 1).firstOrNull()

    fun findDue(now: Instant, limit: Int): List<AutomationWorkSessionEntity> =
        queryFactory.selectFrom(automationWorkSessionEntity)
            .where(
                automationWorkSessionEntity.status.`in`(
                    AutomationWorkStatus.WAITING_RESOURCE,
                    AutomationWorkStatus.WAITING_COOLDOWN,
                ),
                automationWorkSessionEntity.nextCheckAt.isNotNull,
                automationWorkSessionEntity.nextCheckAt.loe(now),
            )
            .orderBy(automationWorkSessionEntity.nextCheckAt.asc(), automationWorkSessionEntity.id.asc())
            .limit(limit.toLong())
            .fetch()

    fun lockOpen(accountId: Long): List<AutomationWorkSessionEntity> =
        queryFactory.selectFrom(automationWorkSessionEntity)
            .where(
                automationWorkSessionEntity.account.id.eq(accountId),
                automationWorkSessionEntity.status.`in`(OPEN_STATUSES),
            )
            .orderBy(automationWorkSessionEntity.id.asc())
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetch()

    fun lockById(accountId: Long, sessionId: Long): AutomationWorkSessionEntity? =
        queryFactory.selectFrom(automationWorkSessionEntity)
            .where(
                automationWorkSessionEntity.id.eq(sessionId),
                automationWorkSessionEntity.account.id.eq(accountId),
            )
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchOne()

    private companion object {
        val OPEN_STATUSES = listOf(
            AutomationWorkStatus.RUNNING,
            AutomationWorkStatus.WAITING_COOLDOWN,
            AutomationWorkStatus.WAITING_RESOURCE,
            AutomationWorkStatus.YIELDED_PRIORITY,
        )
    }
}
