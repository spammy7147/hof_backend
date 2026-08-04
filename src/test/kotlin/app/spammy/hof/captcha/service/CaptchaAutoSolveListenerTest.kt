package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito
import org.springframework.core.task.TaskExecutor

class CaptchaAutoSolveListenerTest {
    private val coordinator = Mockito.mock(CaptchaAutoSolveCoordinator::class.java)
    private val notifications = Mockito.mock(CaptchaNotificationGateway::class.java)
    private val directExecutor = TaskExecutor { task -> task.run() }
    private val listener = CaptchaAutoSolveListener(coordinator, notifications, directExecutor)
    private val event = AutomationCaptchaDetectedEvent(
        account = HofAccountEntity(7L, "login", "encrypted", Instant.parse("2026-08-04T00:00:00Z")),
        challengeId = 91L,
    )

    @Test
    fun `notifies the user only when automatic solving requires manual input`() {
        Mockito.`when`(coordinator.solve(7L, 91L)).thenReturn(CaptchaAutoSolveOutcome.MANUAL_INPUT_REQUIRED)

        listener.onDetected(event)

        Mockito.verify(notifications).captchaRequired(event.account, 91L)
    }

    @Test
    fun `does not notify after automatic solving succeeds`() {
        Mockito.`when`(coordinator.solve(7L, 91L)).thenReturn(CaptchaAutoSolveOutcome.SOLVED)

        listener.onDetected(event)

        Mockito.verifyNoInteractions(notifications)
    }

    @Test
    fun `does not notify for a stale detection event`() {
        Mockito.`when`(coordinator.solve(7L, 91L)).thenReturn(CaptchaAutoSolveOutcome.NO_PENDING_CHALLENGE)

        listener.onDetected(event)

        Mockito.verifyNoInteractions(notifications)
    }
}
