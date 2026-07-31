package app.spammy.hof.town.common.repository

import app.spammy.hof.common.persistence.CommandRepository
import app.spammy.hof.town.common.entity.TownFeatureLocationEntity
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.entity.QTownFeatureLocationEntity.townFeatureLocationEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Repository

/** 계정 공용 마을 위치의 단건 조회와 갱신 계약이다. */
interface TownFeatureLocationRepository : CommandRepository<TownFeatureLocationEntity, TownFeatureId>

/** 공용 위치 조회를 쓰기 repository와 분리하는 QueryDSL read 경계다. */
@Repository
class TownFeatureLocationQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findByFeatureIdForUpdate(featureId: TownFeatureId): TownFeatureLocationEntity? =
        queryFactory.selectFrom(townFeatureLocationEntity)
            .where(townFeatureLocationEntity.featureId.eq(featureId))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchOne()
}
