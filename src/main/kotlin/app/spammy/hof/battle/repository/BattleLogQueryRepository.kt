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

/** 최근 전투 한 건의 scalar 부모와 정렬된 참가자/전리품을 함께 전달하는 read model이다. */
data class BattleLogReadModel(
    val log: BattleLogEntity,
    val participants: List<BattleLogParticipantEntity>,
    val loots: List<BattleLogLootEntity>,
)

/** 전투 로그 부모 행에서 한 번에 계산한 결과별 횟수와 금액/경험치 합계다. */
data class BattleLogStatsProjection(
    val totalBattles: Long,
    val victories: Long,
    val defeats: Long,
    val draws: Long,
    val unknowns: Long,
    val totalFunds: Long,
    val totalExperience: Long,
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
    ): List<BattleLogReadModel> {
        if (limit <= 0) return emptyList()

        val logIds = queryFactory
            .select(battleLogEntity.id)
            .from(battleLogEntity)
            .where(battleLogEntity.account.id.eq(accountId))
            .orderBy(battleLogEntity.createdAt.desc(), battleLogEntity.id.desc())
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

    /** 계정의 결과별 전투 횟수와 Funds/경험치 합계를 단일 aggregate query로 계산한다. */
    fun findStats(accountId: Long): BattleLogStatsProjection {
        val totalBattles = battleLogEntity.id.count()
        val victories = outcomeCount("VICTORY")
        val defeats = outcomeCount("DEFEAT")
        val draws = outcomeCount("DRAW")
        val unknowns = outcomeCount("UNKNOWN")
        val totalFunds = battleLogEntity.funds.longValue().sum().coalesce(0L)
        val totalExperience = battleLogEntity.experience.longValue().sum().coalesce(0L)
        val row = queryFactory
            .select(
                totalBattles,
                victories,
                defeats,
                draws,
                unknowns,
                totalFunds,
                totalExperience,
            )
            .from(battleLogEntity)
            .where(battleLogEntity.account.id.eq(accountId))
            .fetchOne()

        return BattleLogStatsProjection(
            totalBattles = row?.get(totalBattles) ?: 0L,
            victories = row?.get(victories) ?: 0L,
            defeats = row?.get(defeats) ?: 0L,
            draws = row?.get(draws) ?: 0L,
            unknowns = row?.get(unknowns) ?: 0L,
            totalFunds = row?.get(totalFunds) ?: 0L,
            totalExperience = row?.get(totalExperience) ?: 0L,
        )
    }

    /** 계정의 모든 전리품 수량을 전리품 자식 테이블에서 직접 합산한다. */
    fun sumLootQuantity(accountId: Long): Long {
        val totalQuantity = battleLogLootEntity.quantity.longValue().sum().coalesce(0L)
        return queryFactory
            .select(totalQuantity)
            .from(battleLogLootEntity)
            .join(battleLogLootEntity.battleLog, battleLogEntity)
            .where(battleLogEntity.account.id.eq(accountId))
            .fetchOne() ?: 0L
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
