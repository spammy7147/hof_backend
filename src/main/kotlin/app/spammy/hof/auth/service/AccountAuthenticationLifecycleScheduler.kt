package app.spammy.hof.auth.service

import app.spammy.hof.common.time.TimeProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 명시적 로그아웃 없이 마지막 refresh token이 자연 만료된 계정도 같은 인증 일시중단으로 수렴시킨다. */
@Component
@Profile("dev | prod")
class AccountAuthenticationLifecycleScheduler(
    private val lifecycle: AccountAuthenticationLifecycleService,
    private val timeProvider: TimeProvider,
) {
    /** 자동화 복구 스케줄러보다 먼저 만료 계정을 중단해 재기동 직후의 실행 공백을 막는다. */
    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun reconcileOnStartup() {
        while (true) {
            val expired = lifecycle.findNaturallyExpiredAccountIds(timeProvider.now())
            if (expired.isEmpty()) return
            expired.forEach(lifecycle::suspendIfNoActiveSessions)
        }
    }

    @Scheduled(fixedDelayString = "\${hof.auth.execution-reconciliation-delay-ms:5000}")
    fun reconcileExpiredSessions() {
        lifecycle.findNaturallyExpiredAccountIds(timeProvider.now()).forEach(lifecycle::suspendIfNoActiveSessions)
    }
}
