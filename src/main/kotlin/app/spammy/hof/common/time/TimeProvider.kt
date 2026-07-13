package app.spammy.hof.common.time

import java.time.Instant

/**
 * 현재 시각을 주입받기 위한 인터페이스다.
 *
 * 테스트에서는 고정 시간을 넣을 수 있고, 운영에서는 SystemTimeProvider가 실제 시간을 반환한다.
 */
fun interface TimeProvider {
    /**
     * 현재 시각을 Instant로 반환한다.
     */
    fun now(): Instant
}
