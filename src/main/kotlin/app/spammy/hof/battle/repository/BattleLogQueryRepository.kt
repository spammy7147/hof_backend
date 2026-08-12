package app.spammy.hof.battle.repository

import app.spammy.hof.battle.entity.BattleLogEntity
import app.spammy.hof.battle.entity.BattleLogLootEntity
import app.spammy.hof.battle.entity.BattleLogParticipantEntity
import app.spammy.hof.battle.entity.QBattleLogEntity.battleLogEntity
import app.spammy.hof.battle.entity.QBattleLogLootEntity.battleLogLootEntity
import app.spammy.hof.battle.entity.QBattleLogParticipantEntity.battleLogParticipantEntity
import com.querydsl.core.types.dsl.CaseBuilder
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository
import java.time.Instant

/** 최근 전투 한 건의 scalar 부모와 정렬된 참가자/전리품을 함께 전달하는 read model이다. */
data class BattleLogReadModel(
    val log: BattleLogEntity,
    val participants: List<BattleLogParticipantEntity>,
    val loots: List<BattleLogLootEntity>,
)

/** 모험맵 한 곳의 패배/무승부 집계 결과다. */
data class AdventureMapOutcomeStatsProjection(
    val mapCode: String,
    val mapName: String,
    val defeats: Long,
    val draws: Long,
)

/**
 * 전투 기록의 최근 목록, 자식 행, 누적 통계를 QueryDSL로만 조회하는 repository다.
 *
 * 최근 목록은 먼저 제한된 부모 ID를 확정한 뒤 부모 scalar, 참가자, 전리품을 각각 한 번씩 조회한다.
 * collection fetch join과 pagination을 결합하지 않으므로 참가자나 전리품 수에 따라 페이지 크기가
 * 흔들리지 않고, 자식 조회의 `IN` 조건도 선택된 부모 ID 범위를 벗어나지 않는다.
 */
@Repository
class BattleLogQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    /** 계정의 최근 전투를 `created_at DESC, id DESC`로 안정적으로 제한하고 자식 행을 일괄 조립한다. */
    fun findRecent(
        accountId: Long,
        limit: Int,
        offset: Int = 0,
        outcome: String? = null,
    ): List<BattleLogReadModel> {
        if (limit <= 0) return emptyList()

        val logIds = queryFactory
            .select(battleLogEntity.id)
            .from(battleLogEntity)
            .where(
                battleLogEntity.account.id.eq(accountId),
                outcome?.let(battleLogEntity.outcome::eq),
            )
            .orderBy(battleLogEntity.createdAt.desc(), battleLogEntity.id.desc())
            .offset(offset.coerceAtLeast(0).toLong())
            .limit(limit.coerceAtMost(100).toLong())
            .fetch()
        if (logIds.isEmpty()) return emptyList()

        val logsById = queryFactory
            .selectFrom(battleLogEntity)
            .where(battleLogEntity.id.`in`(logIds))
            .orderBy(battleLogEntity.createdAt.desc(), battleLogEntity.id.desc())
            .fetch()
            .associateBy { log -> log.id }
        val participantsByLogId = findParticipants(logIds).groupBy { participant -> participant.battleLog.id }
        val lootsByLogId = findLoots(logIds).groupBy { loot -> loot.battleLog.id }

        return logIds.mapNotNull { logId ->
            logsById[logId]?.let { log ->
                BattleLogReadModel(
                    log = log,
                    participants = participantsByLogId[logId].orEmpty(),
                    loots = lootsByLogId[logId].orEmpty(),
                )
            }
        }
    }

    /** 한 전투 로그에 저장된 참가자 행 수를 QueryDSL count로 확인한다. */
    fun countParticipants(logId: Long): Long =
        queryFactory
            .select(battleLogParticipantEntity.id.count())
            .from(battleLogParticipantEntity)
            .where(battleLogParticipantEntity.battleLog.id.eq(logId))
            .fetchOne() ?: 0L

    /** 지정 시각 이후 기록된 전투 Funds를 합산한다. */
    fun sumFundsSince(accountId: Long, since: Instant): Long {
        val totalFunds = battleLogEntity.funds.longValue().sum().coalesce(0L)
        return queryFactory
            .select(totalFunds)
            .from(battleLogEntity)
            .where(
                battleLogEntity.account.id.eq(accountId),
                battleLogEntity.createdAt.goe(since),
            )
            .fetchOne() ?: 0L
    }

    /** 모험맵 중 패배 또는 무승부가 있었던 맵만 맵별로 집계한다. */
    fun findAdventureMapOutcomeStats(accountId: Long, since: Instant): List<AdventureMapOutcomeStatsProjection> {
        val defeats = outcomeCount("DEFEAT")
        val draws = outcomeCount("DRAW")
        return queryFactory
            .select(
                battleLogEntity.mapCodeSnapshot,
                battleLogEntity.mapNameSnapshot,
                defeats,
                draws,
            )
            .from(battleLogEntity)
            .where(
                battleLogEntity.account.id.eq(accountId),
                battleLogEntity.categoryIdSnapshot.eq("adventure_map"),
                battleLogEntity.outcome.`in`("DEFEAT", "DRAW"),
                battleLogEntity.createdAt.goe(since),
            )
            .groupBy(battleLogEntity.mapCodeSnapshot, battleLogEntity.mapNameSnapshot)
            .orderBy(defeats.desc(), draws.desc(), battleLogEntity.mapNameSnapshot.asc())
            .fetch()
            .map { row ->
                AdventureMapOutcomeStatsProjection(
                    mapCode = row.get(battleLogEntity.mapCodeSnapshot).orEmpty(),
                    mapName = row.get(battleLogEntity.mapNameSnapshot).orEmpty(),
                    defeats = row.get(defeats) ?: 0L,
                    draws = row.get(draws) ?: 0L,
                )
            }
    }

    private fun findParticipants(logIds: Collection<Long>): List<BattleLogParticipantEntity> =
        queryFactory
            .selectFrom(battleLogParticipantEntity)
            .where(battleLogParticipantEntity.battleLog.id.`in`(logIds))
            .orderBy(
                battleLogParticipantEntity.battleLog.id.asc(),
                battleLogParticipantEntity.slotIndex.asc(),
                battleLogParticipantEntity.id.asc(),
            )
            .fetch()

    private fun findLoots(logIds: Collection<Long>): List<BattleLogLootEntity> =
        queryFactory
            .selectFrom(battleLogLootEntity)
            .where(battleLogLootEntity.battleLog.id.`in`(logIds))
            .orderBy(
                battleLogLootEntity.battleLog.id.asc(),
                battleLogLootEntity.displayOrder.asc(),
                battleLogLootEntity.id.asc(),
            )
            .fetch()

    private fun outcomeCount(outcome: String) =
        CaseBuilder()
            .`when`(battleLogEntity.outcome.eq(outcome))
            .then(1L)
            .otherwise(0L)
            .sum()
            .coalesce(0L)
}
