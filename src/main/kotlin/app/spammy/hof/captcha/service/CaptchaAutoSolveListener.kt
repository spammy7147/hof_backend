package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

data class AutomationCaptchaDetectedEvent(
    val account: HofAccountEntity,
    val challengeId: Long,
)

@Component
class CaptchaAutoSolveListener(
    private val coordinator: CaptchaAutoSolveCoordinator,
    private val notifications: CaptchaNotificationGateway,
    @Qualifier("captchaAutoSolveTaskExecutor") private val taskExecutor: TaskExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val activeChallenges = ConcurrentHashMap.newKeySet<Long>()

    @EventListener
    fun onDetected(event: AutomationCaptchaDetectedEvent) {
        try {
            taskExecutor.execute { solveOrRequestManualInput(event) }
        } catch (error: RuntimeException) {
            log.warn("CAPTCHA automatic solve queue rejected challengeId={}", event.challengeId, error)
            notifications.captchaRequired(event.account, event.challengeId)
        }
    }

    private fun solveOrRequestManualInput(event: AutomationCaptchaDetectedEvent) {
        if (!activeChallenges.add(event.challengeId)) return
        try {
            if (coordinator.solve(event.account.id, event.challengeId) ==
                CaptchaAutoSolveOutcome.MANUAL_INPUT_REQUIRED
            ) {
                notifications.captchaRequired(event.account, event.challengeId)
            }
        } finally {
            activeChallenges.remove(event.challengeId)
        }
    }
}
