package app.spammy.hof.captcha.repository

import app.spammy.hof.captcha.entity.CaptchaPassMaintenanceEntity
import app.spammy.hof.captcha.entity.QCaptchaPassMaintenanceEntity.captchaPassMaintenanceEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.stereotype.Repository

/** 계정 전역 통행증 자동 갱신 상태의 모든 조회를 담당한다. */
@Repository
class CaptchaPassMaintenanceQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findByAccountId(accountId: Long): CaptchaPassMaintenanceEntity? =
        queryFactory.selectFrom(captchaPassMaintenanceEntity)
            .where(captchaPassMaintenanceEntity.account.id.eq(accountId))
            .fetchOne()

    fun findByAccountIdForUpdate(accountId: Long): CaptchaPassMaintenanceEntity? =
        queryFactory.selectFrom(captchaPassMaintenanceEntity)
            .where(captchaPassMaintenanceEntity.account.id.eq(accountId))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchOne()

    fun findDueAccountIds(now: Instant, limit: Long = 100): List<Long> =
        queryFactory.select(captchaPassMaintenanceEntity.account.id)
            .from(captchaPassMaintenanceEntity)
            .where(
                captchaPassMaintenanceEntity.enabled.isTrue,
                captchaPassMaintenanceEntity.authSuspended.isFalse,
                captchaPassMaintenanceEntity.nextRefreshAt.loe(now)
                    .or(
                        captchaPassMaintenanceEntity.nextRefreshAt.isNull
                            .and(captchaPassMaintenanceEntity.lastResult.isNull),
                    ),
                captchaPassMaintenanceEntity.leaseUntil.isNull
                    .or(captchaPassMaintenanceEntity.leaseUntil.loe(now)),
            )
            .orderBy(captchaPassMaintenanceEntity.nextRefreshAt.asc(), captchaPassMaintenanceEntity.account.id.asc())
            .limit(limit)
            .fetch()
}
