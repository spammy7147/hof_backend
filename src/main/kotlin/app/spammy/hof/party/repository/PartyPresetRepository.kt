package app.spammy.hof.party.repository

import app.spammy.hof.common.persistence.CommandRepository
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.party.entity.PartyPresetMemberId

/**
 * 파티 프리셋 부모 행의 저장·삭제·flush만 노출하는 command repository다.
 *
 * 프리셋 조회와 소유권 검사는 [PartyPresetQueryRepository]가 전담한다.
 */
interface PartyPresetRepository : CommandRepository<PartyPresetEntity, Long>

/**
 * 프리셋 슬롯 행의 일괄 저장·삭제·flush만 노출하는 command repository다.
 *
 * 교체 작업은 QueryDSL로 기존 managed entity를 읽은 뒤 이 repository로 삭제하고 flush하여,
 * 같은 복합 키 0~4를 다시 삽입할 때 영속성 컨텍스트와 DB의 키가 충돌하지 않게 한다.
 */
interface PartyPresetMemberCommandRepository : CommandRepository<PartyPresetMemberEntity, PartyPresetMemberId>
