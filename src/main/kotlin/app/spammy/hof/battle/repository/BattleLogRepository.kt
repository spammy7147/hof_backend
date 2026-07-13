package app.spammy.hof.battle.repository

import app.spammy.hof.battle.entity.BattleLogEntity
import app.spammy.hof.battle.entity.BattleLogLootEntity
import app.spammy.hof.battle.entity.BattleLogParticipantEntity
import app.spammy.hof.common.persistence.CommandRepository

/**
 * 전투 로그 부모 행의 저장, 삭제, flush만 노출하는 command repository다.
 *
 * 최근 기록과 통계 등 모든 읽기는 [BattleLogQueryRepository]가 QueryDSL로 수행한다.
 */
interface BattleLogRepository : CommandRepository<BattleLogEntity, Long>

/** 참가자 스냅샷 행의 일괄 저장과 삭제만 담당하는 command repository다. */
interface BattleLogParticipantCommandRepository : CommandRepository<BattleLogParticipantEntity, Long>

/** 전리품 구조화 행의 일괄 저장과 삭제만 담당하는 command repository다. */
interface BattleLogLootCommandRepository : CommandRepository<BattleLogLootEntity, Long>
