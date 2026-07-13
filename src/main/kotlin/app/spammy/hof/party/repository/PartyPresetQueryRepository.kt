package app.spammy.hof.party.repository

import app.spammy.hof.character.entity.QCharacterEntity.characterEntity
import app.spammy.hof.character.entity.QCharacterPatternSlotEntity.characterPatternSlotEntity
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.party.entity.QPartyPresetEntity.partyPresetEntity
import app.spammy.hof.party.entity.QPartyPresetMemberEntity.partyPresetMemberEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

/**
 * 파티 프리셋 부모와 슬롯의 모든 SELECT·COUNT를 QueryDSL로 수행한다.
 *
 * 서비스의 목록 경로는 부모 한 번, 전체 부모 ID의 슬롯 한 번으로 제한된다. 슬롯 조회에서 nullable
 * 캐릭터와 패턴을 fetch join하여 응답 DTO 조립 중 lazy loading으로 추가 SQL이 발생하지 않게 한다.
 */
@Repository
class PartyPresetQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    /**
     * 계정 프리셋을 최근 수정순으로 조회하고, 수정 시각이 같으면 큰 ID를 먼저 반환한다.
     *
     * ID tie-breaker는 같은 트랜잭션에서 연속 생성된 프리셋도 매 호출마다 같은 순서를 유지하게 한다.
     */
    fun findAllByAccountId(accountId: Long): List<PartyPresetEntity> =
        queryFactory
            .selectFrom(partyPresetEntity)
            .where(partyPresetEntity.account.id.eq(accountId))
            .orderBy(
                partyPresetEntity.updatedAt.desc(),
                partyPresetEntity.id.desc(),
            )
            .fetch()

    /**
     * PK와 계정 FK를 한 조건에서 비교해 다른 계정의 프리셋도 존재하지 않는 것처럼 처리한다.
     */
    fun findOwnedByAccountIdAndId(
        accountId: Long,
        presetId: Long,
    ): PartyPresetEntity? =
        queryFactory
            .selectFrom(partyPresetEntity)
            .where(
                partyPresetEntity.id.eq(presetId),
                partyPresetEntity.account.id.eq(accountId),
            )
            .fetchOne()

    /** 계정과 요청 PK 집합이 모두 일치하는 프리셋을 ID 순서로 한 번에 조회한다. */
    fun findOwnedByAccountIdAndIds(
        accountId: Long,
        presetIds: Collection<Long>,
    ): List<PartyPresetEntity> {
        if (presetIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(partyPresetEntity)
            .where(
                partyPresetEntity.account.id.eq(accountId),
                partyPresetEntity.id.`in`(presetIds.toSet()),
            )
            .orderBy(partyPresetEntity.id.asc())
            .fetch()
    }

    /**
     * 여러 프리셋의 슬롯을 한 번에 읽고 프리셋 ID, 슬롯 번호 순으로 정렬한다.
     *
     * 빈 ID 컬렉션은 SQL `IN ()` 방언 차이를 피하고 불필요한 쿼리를 만들지 않도록 즉시 반환한다.
     */
    fun findMembersByPresetIds(presetIds: Collection<Long>): List<PartyPresetMemberEntity> {
        if (presetIds.isEmpty()) return emptyList()

        return queryFactory
            .selectFrom(partyPresetMemberEntity)
            .leftJoin(partyPresetMemberEntity.character, characterEntity).fetchJoin()
            .leftJoin(partyPresetMemberEntity.patternSlot, characterPatternSlotEntity).fetchJoin()
            .where(partyPresetMemberEntity.preset.id.`in`(presetIds))
            .orderBy(
                partyPresetMemberEntity.preset.id.asc(),
                partyPresetMemberEntity.slotIndex.asc(),
            )
            .fetch()
    }

    /**
     * 테스트와 무결성 확인에서 부모 하나에 연결된 슬롯 row 수만 DB에서 계산한다.
     */
    fun countMembers(presetId: Long): Long =
        queryFactory
            .select(partyPresetMemberEntity.count())
            .from(partyPresetMemberEntity)
            .where(partyPresetMemberEntity.preset.id.eq(presetId))
            .fetchOne() ?: 0L
}
