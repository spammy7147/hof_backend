package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.entity.AutomationProfileMapEntity
import app.spammy.hof.automation.entity.QAutomationProfileEntity.automationProfileEntity
import app.spammy.hof.automation.entity.QAutomationProfileMapEntity.automationProfileMapEntity
import app.spammy.hof.battle.entity.QBattleMapEntity.battleMapEntity
import app.spammy.hof.party.entity.QPartyPresetEntity.partyPresetEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

/**
 * 자동전투 프로필 부모와 맵 행의 모든 SELECT·COUNT를 QueryDSL로 수행한다.
 *
 * 목록 경로는 부모 한 번과 전체 부모 ID의 맵 한 번으로 제한한다. 맵과 nullable 프리셋을 fetch join해
 * 응답 조립 중 추가 SQL이 발생하지 않으며, 부모별 실행 순서와 맵 코드로 결과를 안정적으로 정렬한다.
 */
@Repository
class AutomationProfileQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    /** 계정 프로필을 최근 수정순, 같은 수정 시각에서는 큰 ID 순으로 조회한다. */
    fun findAllByAccountId(accountId: Long): List<AutomationProfileEntity> =
        queryFactory
            .selectFrom(automationProfileEntity)
            .where(automationProfileEntity.account.id.eq(accountId))
            .orderBy(
                automationProfileEntity.updatedAt.desc(),
                automationProfileEntity.id.desc(),
            )
            .fetch()

    /** PK와 계정 FK를 동시에 비교해 다른 계정 프로필도 존재하지 않는 것처럼 조회한다. */
    fun findOwnedByAccountIdAndId(
        accountId: Long,
        profileId: Long,
    ): AutomationProfileEntity? =
        queryFactory
            .selectFrom(automationProfileEntity)
            .where(
                automationProfileEntity.id.eq(profileId),
                automationProfileEntity.account.id.eq(accountId),
            )
            .fetchOne()

    /**
     * 여러 프로필의 맵 행을 한 번에 읽는다.
     *
     * 빈 ID 목록은 SQL `IN ()` 방언 차이와 불필요한 DB 왕복을 피하려고 즉시 반환한다.
     */
    fun findMapsByProfileIds(profileIds: Collection<Long>): List<AutomationProfileMapEntity> {
        if (profileIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(automationProfileMapEntity)
            .join(automationProfileMapEntity.battleMap, battleMapEntity).fetchJoin()
            .leftJoin(automationProfileMapEntity.partyPreset, partyPresetEntity).fetchJoin()
            .where(automationProfileMapEntity.profile.id.`in`(profileIds))
            .orderBy(
                automationProfileMapEntity.profile.id.asc(),
                automationProfileMapEntity.executionOrder.asc(),
                battleMapEntity.mapCode.asc(),
                automationProfileMapEntity.id.asc(),
            )
            .fetch()
    }

    /** 테스트와 무결성 확인을 위해 프로필 한 개의 맵 행 수를 DB에서 계산한다. */
    fun countMaps(profileId: Long): Long =
        queryFactory
            .select(automationProfileMapEntity.count())
            .from(automationProfileMapEntity)
            .where(automationProfileMapEntity.profile.id.eq(profileId))
            .fetchOne() ?: 0L
}
