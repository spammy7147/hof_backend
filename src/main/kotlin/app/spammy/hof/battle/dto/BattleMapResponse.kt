package app.spammy.hof.battle.dto

import app.spammy.hof.external.model.HofBattleMap

/**
 * HOF 원본 페이지에서 파싱한 전투 맵 정보를 앱 목록용으로 내려주는 응답 DTO다.
 */
data class BattleMapResponse(
    val categoryId: String,
    val mapCode: String?,
    val name: String,
    val groupName: String?,
    val groupOrder: Int,
    val mapOrder: Int,
    val recommendedLevel: String?,
    val availableCount: Int?,
    val attemptCount: Int?,
    val winCount: Int?,
    val cooldownRemainingText: String?,
    val cooldownRemainingSeconds: Long?,
    val keyCount: Int?,
    val requiredTime: Int?,
    val supportsThreeBattles: Boolean = false,
    val enabled: Boolean,
    val resolved: Boolean,
    val iconUrl: String?,
    val rawHref: String,
) {
    companion object {
        /**
         * 원본 파싱 모델을 API 응답 DTO로 변환한다.
         */
        fun from(map: HofBattleMap): BattleMapResponse =
            BattleMapResponse(
                categoryId = map.categoryId,
                mapCode = map.mapCode,
                name = map.name,
                groupName = map.groupName,
                groupOrder = map.groupOrder,
                mapOrder = map.mapOrder,
                recommendedLevel = map.recommendedLevel,
                availableCount = map.availableCount,
                attemptCount = map.attemptCount,
                winCount = map.winCount,
                cooldownRemainingText = map.cooldownRemainingText,
                cooldownRemainingSeconds = map.cooldownRemainingSeconds,
                keyCount = map.keyCount,
                requiredTime = map.requiredTime,
                supportsThreeBattles = map.supportsThreeBattles ?: false,
                enabled = map.enabled,
                resolved = map.resolved,
                iconUrl = map.iconUrl,
                rawHref = map.rawHref,
            )
    }
}
