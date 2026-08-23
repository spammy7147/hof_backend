package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.service.TypedAutomationRuntimeService
import app.spammy.hof.automation.service.TypedCaptchaAutomationResumeService
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionSynchronizationManager

class CaptchaAutomationHookTest {
    private val typedRuntime = Mockito.mock(TypedAutomationRuntimeService::class.java)
    private val typedResume = Mockito.mock(TypedCaptchaAutomationResumeService::class.java)
    private val eventPublisher = Mockito.mock(ApplicationEventPublisher::class.java)
    private val hook = CaptchaAutomationHook(
        typedRuntime,
        typedResume,
        eventPublisher,
    )

    @Test
    fun `typed captcha detection publishes automatic solve event only while runtime is running`() {
        val challenge = challenge()
        Mockito.`when`(typedRuntime.isRunning(7L)).thenReturn(true)

        hook.detected(challenge)

        Mockito.verify(eventPublisher).publishEvent(AutomationCaptchaDetectedEvent(challenge.account, challenge.id))
    }

    @Test
    fun `unrelated manual captcha does not enqueue typed automation push while runtime is stopped`() {
        val challenge = challenge()
        Mockito.`when`(typedRuntime.isRunning(7L)).thenReturn(false)

        hook.detected(challenge)

        Mockito.verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `development gateway ignores captcha notification`() {
        NoOpCaptchaNotificationGateway().captchaRequired(challenge().account, 91L)
    }

    @Test
    fun `automatic solve event waits until detection transaction commits`() {
        val challenge = challenge()
        Mockito.`when`(typedRuntime.isRunning(7L)).thenReturn(true)
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.initSynchronization()
        try {
            hook.detected(challenge)

            Mockito.verifyNoInteractions(eventPublisher)
            val synchronization = TransactionSynchronizationManager.getSynchronizations().single()
            synchronization.afterCommit()

            Mockito.verify(eventPublisher).publishEvent(AutomationCaptchaDetectedEvent(challenge.account, challenge.id))
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    @Test
    fun `answered captcha conditionally resumes typed captcha stop`() {
        val challenge = challenge()

        hook.answered(challenge)

        Mockito.verify(typedResume).resumeAfterCaptcha(7L)
    }

    @Test
    fun `typed resume waits until the captcha answer transaction commits`() {
        val challenge = challenge()
        TransactionSynchronizationManager.setActualTransactionActive(true)
        TransactionSynchronizationManager.initSynchronization()
        try {
            hook.answered(challenge)

            Mockito.verifyNoInteractions(typedResume)
            val synchronization = TransactionSynchronizationManager.getSynchronizations().single()
            synchronization.afterCommit()

            Mockito.verify(typedResume).resumeAfterCaptcha(7L)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
    }

    private fun challenge(): CaptchaChallengeEntity {
        val account = HofAccountEntity(7L, "login", "encrypted", NOW)
        return CaptchaChallengeEntity(
            id = 91L,
            account = account,
            status = "PENDING",
            prompt = "captcha",
            imageUrl = null,
            sourceUrl = "https://example.test/captcha",
            answer = null,
            createdAt = NOW,
            answeredAt = null,
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-16T00:00:00Z")
    }
}
