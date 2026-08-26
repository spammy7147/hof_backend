package app.spammy.hof.captcha.service

import app.spammy.hof.common.time.TimeProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 앱 프로세스와 무관하게 만료된 계정 통행증 작업을 찾아 제한된 CAPTCHA 실행 풀에 전달한다. */
@Component
@Profile("dev | prod")
class CaptchaPassMaintenanceScheduler(
    private val maintenance: CaptchaPassMaintenanceService,
    private val coordinator: CaptchaPassRenewalCoordinator,
    private val timeProvider: TimeProvider,
    @Qualifier("captchaAutoSolveTaskExecutor") private val taskExecutor: TaskExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun recoverOnStartup() = enqueueDue()

    @Scheduled(fixedDelayString = "\${hof.captcha-pass-maintenance.delay-ms:5000}")
    fun runDue() = enqueueDue()

    private fun enqueueDue() {
        maintenance.findDueAccountIds(timeProvider.now()).forEach { accountId ->
            runCatching { taskExecutor.execute { coordinator.runDue(accountId) } }
                .onFailure { error -> log.warn("Pass maintenance queue rejected accountId={}", accountId, error) }
        }
    }
}
