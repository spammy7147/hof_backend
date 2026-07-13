package app.spammy.hof.battle.model

/**
 * 앱이 지원하는 전투 카테고리의 내부 도메인 모델이다.
 */
data class BattleCategory(
    val id: BattleCategoryId,
    val label: String,
    val description: String,
    val order: Int,
    val enabled: Boolean,
)
