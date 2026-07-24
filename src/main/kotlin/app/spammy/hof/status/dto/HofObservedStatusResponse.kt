package app.spammy.hof.status.dto

import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import java.time.Instant

/** 자동화 조회에 함께 싣는 HOF 상단 표시 전용 최신 관측값이다. */
data class HofObservedStatusResponse(
    val playerName: String,
    val funds: Long,
    val timeCurrent: Int,
    val timeMax: Int,
    val work: String,
    val auction: String,
    val observedAt: Instant,
) {
    companion object {
        fun from(entity: HofStatusSnapshotEntity): HofObservedStatusResponse = HofObservedStatusResponse(
            playerName = entity.playerName,
            funds = entity.funds,
            timeCurrent = entity.timeCurrent,
            timeMax = entity.timeMax,
            work = entity.work,
            auction = entity.auction,
            observedAt = entity.observedAt,
        )
    }
}
