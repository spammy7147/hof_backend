package app.spammy.hof.external.model

/**
 * HOF 전투 결과 HTML에서 파싱한 한 회차 전투 결과다.
 */
data class HofBattleResult(
    val outcome: HofBattleOutcome,
    val title: String,
    val turns: Int?,
    val funds: Int?,
    val experience: Int?,
    val loots: List<HofBattleLoot>,
    val quest: String?,
    val enemySide: HofBattleSide,
    val allySide: HofBattleSide,
    val rawLogUrl: String? = null,
)

/**
 * HOF 전투 결과 HTML에서 파싱한 전리품 한 줄의 구조화 값과 원문이다.
 *
 * [name]은 끝의 수량 표기를 제거한 아이템명이고 [quantity]는 `x 숫자`에서 읽은 수량이다.
 * 수량 표기가 없으면 1을 사용하며, [rawText]는 앱 표시와 파서 회귀 검증을 위해 원문을 보존한다.
 */
data class HofBattleLoot(
    val name: String,
    val quantity: Int,
    val rawText: String,
)

/**
 * HOF 전투 결과 HTML에서 파싱한 아군/적군 한쪽의 상태값이다.
 */
data class HofBattleSide(
    val hpCurrent: Int?,
    val hpMax: Int?,
    val survivorsAlive: Int?,
    val survivorsMax: Int?,
    val totalDamage: Int?,
    val turnCurrent: Int? = null,
    val turnMax: Int? = null,
) {
    companion object {
        /**
         * 파싱에 실패했거나 해당 진영 정보가 없을 때 쓰는 빈 상태값이다.
         */
        val EMPTY = HofBattleSide(
            hpCurrent = null,
            hpMax = null,
            survivorsAlive = null,
            survivorsMax = null,
            totalDamage = null,
        )
    }
}
