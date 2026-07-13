package app.spammy.hof.battle.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero

/**
 * 앱에서 선택한 맵, 파티, 패턴 로드 목록, 반복 횟수로 전투 실행을 요청하는 DTO다.
 */
data class RunBattleRequest(
    @field:NotBlank
    val categoryId: String,

    @field:NotBlank
    val mapCode: String,

    @field:NotEmpty
    val characterIds: List<String>,

    @field:Valid
    val patternLoads: List<BattlePatternLoadRequest> = emptyList(),

    val battleCount: Int = 1,

    val multi: Boolean = false,
) {
    /**
     * 예전 multi 플래그와 현재 battleCount 값을 모두 지원하기 위해 실제 실행 횟수를 계산한다.
     */
    fun resolvedBattleCount(): Int =
        if (multi && battleCount == 1) 3 else battleCount
}

/**
 * 전투 직전에 특정 캐릭터의 저장 패턴 슬롯을 로드하기 위한 요청 DTO다.
 */
data class BattlePatternLoadRequest(
    @field:NotBlank
    val characterId: String,

    @field:PositiveOrZero
    val slot: Int,
)
