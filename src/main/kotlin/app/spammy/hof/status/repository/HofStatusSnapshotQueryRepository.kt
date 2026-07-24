package app.spammy.hof.status.repository

import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import app.spammy.hof.status.entity.QHofStatusSnapshotEntity.hofStatusSnapshotEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

/** 계정별 최신 HOF 상태 스냅샷을 조회한다. */
@Repository
class HofStatusSnapshotQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findByAccountId(accountId: Long): HofStatusSnapshotEntity? =
        queryFactory.selectFrom(hofStatusSnapshotEntity)
            .where(hofStatusSnapshotEntity.account.id.eq(accountId))
            .fetchOne()
}
