package app.spammy.hof.external.model

import app.spammy.hof.battle.model.BattleMapKeyMode

/**
 * HOF 원본 전투/모험 맵 목록 HTML에서 파싱한 맵 정보다.
 */
data class HofBattleMap(
    val categoryId: String,
    val mapCode: String?,
    val name: String,
    val groupName: String? = null,
    val groupOrder: Int = 0,
    val mapOrder: Int = 0,
    val recommendedLevel: String? = null,
    val availableCount: Int? = null,
    val attemptCount: Int? = null,
    val winCount: Int? = null,
    val cooldownRemainingSeconds: Long? = null,
    val keyMode: BattleMapKeyMode = BattleMapKeyMode.UNKNOWN,
    val keyCount: Int? = null,
    val requiredTime: Int? = null,
    /** Null means this page did not contain an authoritative execution form for this map. */
    val supportsThreeBattles: Boolean? = null,
    val enabled: Boolean = true,
    val resolved: Boolean = mapCode != null,
    val iconUrl: String? = null,
    val rawHref: String,
) {
    /** 저장하지 않은 남은 초를 기존 앱이 사용하는 한국어 표기로 변환한다. */
    val cooldownRemainingText: String?
        get() {
            val seconds = cooldownRemainingSeconds?.takeIf { it > 0L } ?: return null
            val totalMinutes = (seconds + SECONDS_PER_MINUTE - 1L) / SECONDS_PER_MINUTE
            val hours = totalMinutes / MINUTES_PER_HOUR
            val minutes = totalMinutes % MINUTES_PER_HOUR
            return if (hours > 0L) "${hours}시간 ${minutes}분" else "${minutes}분"
        }

    private companion object {
        const val SECONDS_PER_MINUTE = 60L
        const val MINUTES_PER_HOUR = 60L
    }
}
