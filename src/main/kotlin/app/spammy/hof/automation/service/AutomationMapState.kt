package app.spammy.hof.automation.service

import java.time.Instant

/** 타입별 자동화가 공유하는 계정별 맵 가시성·키·쿨다운·횟수 상태다. */
data class AutomationMapState(
    val categoryId: String,
    val mapCode: String,
    val mapName: String,
    val visible: Boolean,
    val enabled: Boolean,
    val cooldownUntil: Instant?,
    val winRemaining: Int?,
    val attemptRemaining: Int?,
    val availableCount: Int?,
    val keyCount: Int?,
)
