package app.spammy.hof.party.repository

import app.spammy.hof.party.entity.PartyPresetFolderEntity
import app.spammy.hof.party.entity.QPartyPresetFolderEntity.partyPresetFolderEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

/** 계정별 파티 프리셋 폴더 카탈로그를 조회한다. */
@Repository
class PartyPresetFolderQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    /** 루트 폴더를 먼저 두고 자식은 부모 ID, 표시 순서, ID 순으로 안정적으로 반환한다. */
    fun findAllByAccountId(accountId: Long): List<PartyPresetFolderEntity> =
        queryFactory
            .selectFrom(partyPresetFolderEntity)
            .where(partyPresetFolderEntity.account.id.eq(accountId))
            .orderBy(
                partyPresetFolderEntity.parent.id.asc().nullsFirst(),
                partyPresetFolderEntity.displayOrder.asc(),
                partyPresetFolderEntity.id.asc(),
            )
            .fetch()
}
