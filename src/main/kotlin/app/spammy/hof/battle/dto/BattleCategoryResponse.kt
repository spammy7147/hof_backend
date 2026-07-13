package app.spammy.hof.battle.dto

import app.spammy.hof.battle.model.BattleCategory

/**
 * 전투/모험 같은 최상위 전투 카테고리를 앱으로 내려주는 응답 DTO다.
 */
data class BattleCategoryResponse(
    val id: String,
    val label: String,
    val description: String,
    val order: Int,
    val enabled: Boolean,
) {
    companion object {
        /**
         * 내부 도메인 카테고리 모델을 API 응답 DTO로 변환한다.
         */
        fun from(category: BattleCategory): BattleCategoryResponse =
            BattleCategoryResponse(
                id = category.id.value,
                label = category.label,
                description = category.description,
                order = category.order,
                enabled = category.enabled,
            )
    }
}
