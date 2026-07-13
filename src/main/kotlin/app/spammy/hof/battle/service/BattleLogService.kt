package app.spammy.hof.battle.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.battle.dto.BattleLogResponse
import app.spammy.hof.battle.dto.BattleLootResponse
import app.spammy.hof.battle.dto.BattleSideResponse
import app.spammy.hof.battle.dto.BattleStatsResponse
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.entity.BattleLogEntity
import app.spammy.hof.battle.entity.BattleLogLootEntity
import app.spammy.hof.battle.entity.BattleLogParticipantEntity
import app.spammy.hof.battle.repository.BattleLogLootCommandRepository
import app.spammy.hof.battle.repository.BattleLogParticipantCommandRepository
import app.spammy.hof.battle.repository.BattleLogQueryRepository
import app.spammy.hof.battle.repository.BattleLogReadModel
import app.spammy.hof.battle.repository.BattleLogRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofBattleResult
import app.spammy.hof.external.model.HofBattleSide
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 전투 결과 부모와 참가자/전리품 자식을 기록하고 최근 목록과 누적 통계를 제공하는 서비스다.
 *
 * 저장은 command repository만 사용하고 모든 읽기와 집계는 [BattleLogQueryRepository]에 위임한다.
 * 앱 응답의 배열 형태는 유지하되 DB에서는 슬롯과 표시 순서를 가진 자식 행으로 관리한다.
 */
@Service
class BattleLogService(
    private val battleLogRepository: BattleLogRepository,
    private val participantRepository: BattleLogParticipantCommandRepository,
    private val lootRepository: BattleLogLootCommandRepository,
    private val battleLogQueryRepository: BattleLogQueryRepository,
    private val battleMapQueryRepository: BattleMapQueryRepository,
    private val timeProvider: TimeProvider,
) {
    /**
     * 전투 실행 결과 한 회차의 부모, 참가자, 전리품을 하나의 별도 트랜잭션으로 저장한다.
     *
     * 맵 카탈로그가 있으면 nullable FK를 연결하고 이름을 가져오며, 조회되지 않아도 요청의 카테고리와
     * 맵 코드는 스냅샷으로 보존한다. 참가자는 전달된 순서를 slot index로 사용하므로 파티 순서가
     * 바뀌지 않고, 전리품은 파서가 전달한 순서를 display order로 저장한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        account: HofAccountEntity,
        request: RunBattleRequest,
        characters: List<CharacterEntity>,
        result: HofBattleResult,
    ): BattleLogResponse {
        val battleMap = battleMapQueryRepository.findMapByCategoryIdAndMapCode(
            categoryId = request.categoryId,
            mapCode = request.mapCode,
        )
        val log = battleLogRepository.save(
            BattleLogEntity(
                account = account,
                battleMap = battleMap,
                categoryIdSnapshot = request.categoryId,
                mapCodeSnapshot = request.mapCode,
                mapNameSnapshot = battleMap?.name ?: request.mapCode,
                outcome = result.outcome.name,
                title = result.title,
                turns = result.turns,
                funds = result.funds,
                experience = result.experience,
                quest = result.quest,
                enemyHpCurrent = result.enemySide.hpCurrent,
                enemyHpMax = result.enemySide.hpMax,
                enemySurvivorsAlive = result.enemySide.survivorsAlive,
                enemySurvivorsMax = result.enemySide.survivorsMax,
                enemyTotalDamage = result.enemySide.totalDamage,
                enemyTurnCurrent = result.enemySide.turnCurrent,
                enemyTurnMax = result.enemySide.turnMax,
                allyHpCurrent = result.allySide.hpCurrent,
                allyHpMax = result.allySide.hpMax,
                allySurvivorsAlive = result.allySide.survivorsAlive,
                allySurvivorsMax = result.allySide.survivorsMax,
                allyTotalDamage = result.allySide.totalDamage,
                allyTurnCurrent = result.allySide.turnCurrent,
                allyTurnMax = result.allySide.turnMax,
                rawLogUrl = result.rawLogUrl,
                createdAt = timeProvider.now(),
            ),
        )
        val participants = characters.mapIndexed { slotIndex, character ->
            BattleLogParticipantEntity(
                battleLog = log,
                slotIndex = slotIndex,
                character = character,
                hofCharacterIdSnapshot = character.hofCharacterId,
                characterNameSnapshot = character.name,
            )
        }
        val loots = result.loots.mapIndexed { displayOrder, loot ->
            BattleLogLootEntity(
                battleLog = log,
                displayOrder = displayOrder,
                name = loot.name,
                quantity = loot.quantity,
                rawText = loot.rawText,
            )
        }
        if (participants.isNotEmpty()) participantRepository.saveAll(participants)
        if (loots.isNotEmpty()) lootRepository.saveAll(loots)

        return log.toResponse(participants = participants, loots = loots)
    }

    /** 최근 전투를 1~100건으로 제한하여 부모 ID, scalar, 참가자, 전리품 query 결과를 앱 응답으로 조립한다. */
    @Transactional(readOnly = true)
    fun findRecent(
        accountId: Long,
        limit: Int = 20,
    ): List<BattleLogResponse> =
        battleLogQueryRepository
            .findRecent(accountId = accountId, limit = limit.coerceIn(1, 100))
            .map { readModel -> readModel.toResponse() }

    /**
     * 부모 aggregate 한 번과 전리품 수량 aggregate 한 번으로 누적 통계를 계산한다.
     *
     * 서비스 메모리에서 로그나 JSON 배열을 순회하지 않으므로 기록 수가 늘어도 전송량이 증가하지 않는다.
     */
    @Transactional(readOnly = true)
    fun summarize(accountId: Long): BattleStatsResponse {
        val stats = battleLogQueryRepository.findStats(accountId)
        return BattleStatsResponse(
            accountId = accountId,
            totalBattles = stats.totalBattles,
            victories = stats.victories,
            defeats = stats.defeats,
            draws = stats.draws,
            unknowns = stats.unknowns,
            winRate = if (stats.totalBattles == 0L) 0.0 else stats.victories.toDouble() / stats.totalBattles.toDouble(),
            totalFunds = stats.totalFunds,
            totalExperience = stats.totalExperience,
            totalLootCount = battleLogQueryRepository.sumLootQuantity(accountId),
        )
    }

    private fun BattleLogReadModel.toResponse(): BattleLogResponse =
        log.toResponse(participants = participants, loots = loots)

    /** 정규화된 부모와 자식 행을 기존 앱 배열 응답 형태로 변환한다. */
    private fun BattleLogEntity.toResponse(
        participants: List<BattleLogParticipantEntity>,
        loots: List<BattleLogLootEntity>,
    ): BattleLogResponse =
        BattleLogResponse(
            id = id,
            accountId = account.id,
            categoryId = categoryIdSnapshot,
            mapCode = mapCodeSnapshot,
            characterIds = participants.map { participant -> participant.hofCharacterIdSnapshot },
            characterNames = participants.map { participant -> participant.characterNameSnapshot },
            outcome = outcome,
            title = title,
            turns = turns,
            funds = funds,
            experience = experience,
            loots = loots.map { loot -> BattleLootResponse(name = loot.rawText) },
            quest = quest,
            enemy = BattleSideResponse.from(
                HofBattleSide(
                    hpCurrent = enemyHpCurrent,
                    hpMax = enemyHpMax,
                    survivorsAlive = enemySurvivorsAlive,
                    survivorsMax = enemySurvivorsMax,
                    totalDamage = enemyTotalDamage,
                    turnCurrent = enemyTurnCurrent,
                    turnMax = enemyTurnMax,
                ),
            ),
            ally = BattleSideResponse.from(
                HofBattleSide(
                    hpCurrent = allyHpCurrent,
                    hpMax = allyHpMax,
                    survivorsAlive = allySurvivorsAlive,
                    survivorsMax = allySurvivorsMax,
                    totalDamage = allyTotalDamage,
                    turnCurrent = allyTurnCurrent,
                    turnMax = allyTurnMax,
                ),
            ),
            rawLogUrl = rawLogUrl,
            createdAt = createdAt.toString(),
        )
}
