package app.spammy.hof.external.model

/**
 * HOF 상단 상태바 HTML에서 파싱한 계정 상태값이다.
 */
data class HofMainStatus(
    val playerName: String,
    val funds: Long?,
    val timeCurrent: Int?,
    val timeMax: Int?,
    val work: String,
    val auction: String,
)
