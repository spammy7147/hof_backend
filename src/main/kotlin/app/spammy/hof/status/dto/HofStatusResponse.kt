package app.spammy.hof.status.dto

import java.time.Instant

/**
 * HOF 상단 상태바의 플레이어명, Funds, Time, Work, Auction 값을 앱으로 내려주는 응답 DTO다.
 */
data class HofStatusResponse(
    val accountId: Long,
    val playerName: String,
    val funds: Long?,
    val timeCurrent: Int?,
    val timeMax: Int?,
    val work: String,
    val auction: String,
    val observedAt: Instant,
)
