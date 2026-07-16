package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.service.AutomationActionLockCoordinator
import app.spammy.hof.automation.service.AutomationAfterCommitWakeupService
import app.spammy.hof.automation.service.TypedAutomationRuntimeService
import app.spammy.hof.automation.service.TypedCaptchaAutomationResumeService
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.push.service.PushOutboxService
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito
import org.springframework.transaction.support.TransactionSynchronizationManager

class CaptchaAutomationHookTest {
    private val actions = Mockito.mock(AutomationActionLockCoordinator::class.java)
    private val pushes = Mockito.mock(PushOutboxService::class.java)
    private val legacyWake = Mockito.mock(AutomationAfterCommitWakeupService::class.java)
    private val typedRuntime = Mockito.mock(TypedAutomationRuntimeService::class.java)
    private val typedResume = Mockito.mock(TypedCaptchaAutomationResumeService::class.java)
    private val hook = CaptchaAutomationHook(
        actions,
        pushes,
        legacyWake,
        typedRuntime,
        typedResume,
        TimeProvider { NOW },
    )

    @Test
    fun `typed captcha detection enqueues account scoped push only while runtime is running`() {
        val challenge = challenge()
        Mockito.`when`(typedRuntime.isRunning(7L)).thenReturn(true)

        hook.detected(challenge)

        Mockito.verify(pushes).enqueueCaptchaRequired(challenge.account, challenge.id)
        Mockito.verifyNoInteractions(actions)
    }

    @Test
    fun `unrelated manual captcha does not enqueue typed automation push while runtime is stopped`() {
        val challenge = challenge()
        Mockito.`when`(typedRuntime.isRunning(7L)).thenReturn(false)

        hook.detected(challenge)

        Mockito.verifyNoInteractions(pushes, actions)
    }

    @Test
    fun `answered unlinked captcha conditionally resumes typed captcha stop instead of legacy wake`() {
        val challenge = challenge()

        hook.answered(challenge)

        Mockito.verify(typedResume).resumeAfterCaptcha(7L)
        Mockito.verifyNoInteractions(actions, legacyWake)
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
