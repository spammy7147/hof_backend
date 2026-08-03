package app.spammy.hof.battle.dto

/**
 * 저장된 전투 기록 한 건을 앱의 데이터 탭에 내려주는 응답 DTO다.
 */
data class BattleLogResponse(
    val id: Long,
    val accountId: Long,
    val categoryId: String,
    val mapCode: String,
    val mapName: String,
    val characterIds: List<String>,
    val characterNames: List<String>,
    val outcome: String,
    val title: String,
    val turns: Int?,
    val funds: Int?,
    val experience: Int?,
    val loots: List<BattleLootResponse>,
    val quest: String?,
    val enemy: BattleSideResponse,
    val ally: BattleSideResponse,
    val rawLogUrl: String?,
    val createdAt: String,
)

/**
 * 기간별 Funds와 파티 조정용 모험맵 결과 집계를 앱에 내려주는 응답 DTO다.
 */
data class BattleStatsResponse(
    val accountId: Long,
    val dailyFunds: Long,
    val weeklyFunds: Long,
    val monthlyFunds: Long,
    val adventureMapOutcomes: List<AdventureMapOutcomeStatsResponse>,
)

/** 파티 조정에 활용할 수 있도록 모험맵별 패배와 무승부 횟수를 전달한다. */
data class AdventureMapOutcomeStatsResponse(
    val mapCode: String,
    val mapName: String,
    val defeats: Long,
    val draws: Long,
)
