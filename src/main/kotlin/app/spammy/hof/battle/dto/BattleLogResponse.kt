package app.spammy.hof.battle.dto

/**
 * 저장된 전투 기록 한 건을 앱의 데이터 탭에 내려주는 응답 DTO다.
 */
data class BattleLogResponse(
    val id: Long,
    val accountId: Long,
    val categoryId: String,
    val mapCode: String,
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
 * 전투 로그를 집계한 누적 통계를 앱에 내려주는 응답 DTO다.
 */
data class BattleStatsResponse(
    val accountId: Long,
    val totalBattles: Long,
    val victories: Long,
    val defeats: Long,
    val draws: Long,
    val unknowns: Long,
    val winRate: Double,
    val totalFunds: Long,
    val totalExperience: Long,
    val totalLootCount: Long,
)
