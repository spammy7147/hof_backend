package app.spammy.hof.auth.repository

import app.spammy.hof.auth.entity.QRefreshTokenEntity.refreshTokenEntity
import app.spammy.hof.auth.entity.RefreshTokenEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * refresh token에 필요한 SELECT와 COUNT를 QueryDSL로 수행한다.
 *
 * 토큰 회전은 같은 원문이 동시에 제출될 수 있으므로 해시 조회 시 비관적 쓰기 잠금을 사용한다.
 * 패밀리 조회 결과는 생성 순서로 고정해 폐기 및 보안 감사 동작을 결정적으로 유지한다.
 */
@Repository
class RefreshTokenQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    /** 제출된 토큰 해시에 해당하는 row를 잠그고 회전 여부 판단이 끝날 때까지 동시 갱신을 직렬화한다. */
    fun findByTokenHashForUpdate(tokenHash: String): RefreshTokenEntity? =
        queryFactory
            .selectFrom(refreshTokenEntity)
            .where(refreshTokenEntity.tokenHash.eq(tokenHash))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchOne()

    /** 토큰 entity를 미리 적재하지 않고 계정 잠금을 잡는 데 필요한 ID만 읽는다. */
    fun findAccountIdByTokenHash(tokenHash: String): Long? =
        queryFactory
            .select(refreshTokenEntity.account.id)
            .from(refreshTokenEntity)
            .where(refreshTokenEntity.tokenHash.eq(tokenHash))
            .fetchOne()

    /** 보안 사고나 로그아웃 시 같은 로그인에서 파생된 토큰 전체를 폐기하기 위해 패밀리를 조회한다. */
    fun findByFamilyId(familyId: String): List<RefreshTokenEntity> =
        queryFactory
            .selectFrom(refreshTokenEntity)
            .where(refreshTokenEntity.familyId.eq(familyId))
            .orderBy(refreshTokenEntity.createdAt.asc(), refreshTokenEntity.id.asc())
            .fetch()

    /** 테스트와 운영 무결성 확인을 위해 패밀리의 전체 row 수를 DB에서 계산한다. */
    fun countByFamilyId(familyId: String): Long =
        queryFactory
            .select(refreshTokenEntity.count())
            .from(refreshTokenEntity)
            .where(refreshTokenEntity.familyId.eq(familyId))
            .fetchOne() ?: 0L

    /** 현재 시각 기준으로 만료·회전·폐기되지 않은 계정 토큰 수를 계산한다. */
    fun countActiveByAccountId(accountId: Long, now: Instant): Long =
        queryFactory
            .select(refreshTokenEntity.count())
            .from(refreshTokenEntity)
            .where(
                refreshTokenEntity.account.id.eq(accountId),
                refreshTokenEntity.expiresAt.gt(now),
                refreshTokenEntity.rotatedAt.isNull,
                refreshTokenEntity.revokedAt.isNull,
            )
            .fetchOne() ?: 0L
}
