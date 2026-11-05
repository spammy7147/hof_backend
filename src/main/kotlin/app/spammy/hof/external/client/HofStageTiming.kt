package app.spammy.hof.external.client

import org.slf4j.Logger

/** 원문·URL·쿠키 없이 로컬 처리의 대기/실행 시간만 기록한다. */
internal fun recordHofStage(log: Logger, accountId: Long, stage: String, startedAt: Long) {
    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
    if (elapsedMs >= 250) log.info("HOF local stage accountId={} stage={} durationMs={}", accountId, stage, elapsedMs)
    else log.debug("HOF local stage accountId={} stage={} durationMs={}", accountId, stage, elapsedMs)
}

internal inline fun <T> timeHofStage(log: Logger, accountId: Long, stage: String, action: () -> T): T {
    val startedAt = System.nanoTime()
    return try { action() } finally { recordHofStage(log, accountId, stage, startedAt) }
}
