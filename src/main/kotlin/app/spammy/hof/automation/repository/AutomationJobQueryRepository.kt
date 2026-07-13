package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.QAutomationJobEntity.automationJobEntity
import app.spammy.hof.automation.entity.QAutomationProfileEntity.automationProfileEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository
import java.time.Instant

/** 자동화 job의 PK·소유권·현재 활성 상태 조회를 모두 QueryDSL로 수행한다. */
@Repository
class AutomationJobQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    /** 계정 FK와 job PK를 한 조건으로 조회하고 연결된 프로필도 함께 로딩한다. */
    fun findOwnedByAccountIdAndId(
        accountId: Long,
        jobId: Long,
    ): AutomationJobEntity? =
        baseQuery()
            .where(
                automationJobEntity.id.eq(jobId),
                automationJobEntity.account.id.eq(accountId),
            )
            .fetchOne()

    fun findCurrentByAccountIdAndStatusesForJobId(
        jobId: Long,
        statuses: Collection<String>,
    ): AutomationJobEntity? {
        if (statuses.isEmpty()) return null
        return baseQuery()
            .where(
                automationJobEntity.id.eq(jobId),
                automationJobEntity.status.`in`(statuses),
            )
            .fetchOne()
    }

    /**
     * 지정한 활성 상태 중 가장 최근에 변경된 job을 반환한다.
     *
     * 같은 수정 시각의 연속 생성 job은 큰 ID를 우선해 현재 job 선택이 호출마다 달라지지 않게 한다.
     */
    fun findCurrentByAccountIdAndStatuses(
        accountId: Long,
        statuses: Collection<String>,
    ): AutomationJobEntity? {
        if (statuses.isEmpty()) return null

        return baseQuery()
            .where(
                automationJobEntity.account.id.eq(accountId),
                automationJobEntity.status.`in`(statuses),
            )
            .orderBy(
                automationJobEntity.updatedAt.desc(),
                automationJobEntity.id.desc(),
            )
            .fetchFirst()
    }

    /** 프로필 삭제 전에 하나라도 참조하는 job이 있는지 QueryDSL EXISTS 형태로 확인한다. */
    fun existsByProfileId(profileId: Long): Boolean =
        queryFactory
            .selectOne()
            .from(automationJobEntity)
            .where(automationJobEntity.profile.id.eq(profileId))
            .fetchFirst() != null

    /** 계정의 지정 상태 job 수를 계산한다. 동시 생성 직렬화 테스트와 무결성 확인에 사용한다. */
    fun countByAccountIdAndStatuses(
        accountId: Long,
        statuses: Collection<String>,
    ): Long {
        if (statuses.isEmpty()) return 0L

        return queryFactory
            .select(automationJobEntity.count())
            .from(automationJobEntity)
            .where(
                automationJobEntity.account.id.eq(accountId),
                automationJobEntity.status.`in`(statuses),
            )
            .fetchOne() ?: 0L
    }

    fun findRecoverable(now: Instant): List<AutomationJobEntity> {
        val runnable = baseQuery()
            .where(
                automationJobEntity.status.`in`("PENDING", "RUNNING"),
                automationJobEntity.nextRunAt.isNull.or(automationJobEntity.nextRunAt.loe(now)),
            )
            .orderBy(automationJobEntity.id.asc())
            .fetch()
        val dueConfig = baseQuery()
            .where(
                automationJobEntity.status.eq("WAITING_CONFIG"),
                automationJobEntity.nextRunAt.isNotNull,
                automationJobEntity.nextRunAt.loe(now),
            )
            .orderBy(automationJobEntity.id.asc())
            .fetch()
        return (runnable + dueConfig).distinctBy { it.id }
    }

    private fun baseQuery() =
        queryFactory
            .selectFrom(automationJobEntity)
            .join(automationJobEntity.profile, automationProfileEntity).fetchJoin()
}
