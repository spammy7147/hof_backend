package app.spammy.hof.common.time

import org.springframework.stereotype.Component
import java.time.Instant

@Component
/**
 * 실제 시스템 시각을 반환하는 TimeProvider 구현체다.
 */
class SystemTimeProvider : TimeProvider {
    /**
     * 현재 시스템 시각을 반환한다.
     */
    override fun now(): Instant = Instant.now()
}
