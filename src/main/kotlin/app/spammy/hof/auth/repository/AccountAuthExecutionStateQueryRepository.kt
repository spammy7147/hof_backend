package app.spammy.hof.auth.repository

import app.spammy.hof.auth.entity.AccountAuthExecutionStateEntity
import app.spammy.hof.auth.entity.QAccountAuthExecutionStateEntity.accountAuthExecutionStateEntity
import app.spammy.hof.auth.entity.QRefreshTokenEntity.refreshTokenEntity
import com.querydsl.jpa.JPAExpressions
import com.querydsl.jpa.impl.JPAQueryFactory
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class AccountAuthExecutionStateQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findByAccountId(accountId: Long): AccountAuthExecutionStateEntity? =
        queryFactory.selectFrom(accountAuthExecutionStateEntity)
            .where(accountAuthExecutionStateEntity.accountId.eq(accountId))
            .fetchOne()

    /** 아직 실행 허용 상태지만 현재 유효한 refresh-token family가 하나도 없는 계정을 찾는다. */
    fun findUnsuspendedAccountIdsWithoutActiveSession(now: Instant, limit: Long = 100): List<Long> {
        val activeSession = JPAExpressions.selectOne()
            .from(refreshTokenEntity)
            .where(
                refreshTokenEntity.account.id.eq(accountAuthExecutionStateEntity.accountId),
                refreshTokenEntity.expiresAt.gt(now),
                refreshTokenEntity.rotatedAt.isNull,
                refreshTokenEntity.revokedAt.isNull,
            )
        return queryFactory.select(accountAuthExecutionStateEntity.accountId)
            .from(accountAuthExecutionStateEntity)
            .where(accountAuthExecutionStateEntity.suspended.isFalse, activeSession.notExists())
            .orderBy(accountAuthExecutionStateEntity.accountId.asc())
            .limit(limit)
            .fetch()
    }
}
