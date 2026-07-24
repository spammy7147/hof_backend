package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationWorkSessionEntity
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.QAutomationEntryEntity.automationEntryEntity
import app.spammy.hof.automation.entity.QAutomationWorkSessionEntity.automationWorkSessionEntity
import com.querydsl.core.Tuple
import com.querydsl.jpa.impl.JPAQuery
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.stereotype.Repository

data class AutomationWorkSessionView(
    val id: Long,
    val accountId: Long,
    val entryId: Long,
    val entryPriority: Int,
    val workType: AutomationWorkType,
    val targetKey: String,
    val status: AutomationWorkStatus,
    val missionKey: String?,
    val missionType: String?,
    val observedCurrent: Int?,
    val observedRequired: Int?,
    val materialName: String?,
    val nextCheckAt: Instant?,
)

@Repository
class AutomationWorkSessionQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findRunning(accountId: Long): AutomationWorkSessionView? =
        selectViews()
            .where(
                automationWorkSessionEntity.account.id.eq(accountId),
                automationWorkSessionEntity.status.eq(AutomationWorkStatus.RUNNING),
            )
            .orderBy(automationWorkSessionEntity.id.desc())
            .fetchFirst()
            ?.toView()

    fun findWaiting(accountId: Long): List<AutomationWorkSessionView> =
        selectViews()
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
            .map { it.toView() }

    fun findDue(now: Instant): AutomationWorkSessionView? =
        findDue(now, 1).firstOrNull()

    fun findDue(now: Instant, limit: Int): List<AutomationWorkSessionView> =
        selectViews()
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
            .map { it.toView() }

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

    private fun selectViews(): JPAQuery<Tuple> = queryFactory.select(
        automationWorkSessionEntity.id,
        automationWorkSessionEntity.account.id,
        automationEntryEntity.id,
        automationEntryEntity.priority,
        automationWorkSessionEntity.workType,
        automationWorkSessionEntity.targetKey,
        automationWorkSessionEntity.status,
        automationWorkSessionEntity.missionKey,
        automationWorkSessionEntity.missionType,
        automationWorkSessionEntity.observedCurrent,
        automationWorkSessionEntity.observedRequired,
        automationWorkSessionEntity.materialName,
        automationWorkSessionEntity.nextCheckAt,
    ).from(automationWorkSessionEntity)
        .join(automationWorkSessionEntity.entry, automationEntryEntity)

    private fun Tuple.toView() = AutomationWorkSessionView(
        id = requireNotNull(get(automationWorkSessionEntity.id)),
        accountId = requireNotNull(get(automationWorkSessionEntity.account.id)),
        entryId = requireNotNull(get(automationEntryEntity.id)),
        entryPriority = requireNotNull(get(automationEntryEntity.priority)),
        workType = requireNotNull(get(automationWorkSessionEntity.workType)),
        targetKey = requireNotNull(get(automationWorkSessionEntity.targetKey)),
        status = requireNotNull(get(automationWorkSessionEntity.status)),
        missionKey = get(automationWorkSessionEntity.missionKey),
        missionType = get(automationWorkSessionEntity.missionType),
        observedCurrent = get(automationWorkSessionEntity.observedCurrent),
        observedRequired = get(automationWorkSessionEntity.observedRequired),
        materialName = get(automationWorkSessionEntity.materialName),
        nextCheckAt = get(automationWorkSessionEntity.nextCheckAt),
    )

    private companion object {
        val OPEN_STATUSES = listOf(
            AutomationWorkStatus.RUNNING,
            AutomationWorkStatus.WAITING_COOLDOWN,
            AutomationWorkStatus.WAITING_RESOURCE,
            AutomationWorkStatus.YIELDED_PRIORITY,
        )
    }
}
