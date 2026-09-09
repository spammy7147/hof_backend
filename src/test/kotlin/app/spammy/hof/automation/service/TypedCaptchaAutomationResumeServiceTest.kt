package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionConvergenceModule
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.repository.CaptchaQueryRepository
import kotlin.test.assertFalse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import org.mockito.Mockito

class TypedCaptchaAutomationResumeServiceTest {
    private val lifecycle = Mockito.mock(TypedAutomationLifecycleBridge::class.java)
    private val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
    private val captchaQueries = Mockito.mock(CaptchaQueryRepository::class.java)
    private val service = TypedCaptchaAutomationResumeService(
        lifecycle,
        convergence,
        TimeProvider { NOW },
        captchaQueries,
    )

    @Test
    fun `running runtime is woken after captcha answer when stopped resume is not applicable`() {
        val account = HofAccountEntity(7L, "fixture", "encrypted", NOW)
        val challenge = CaptchaChallengeEntity(account = account, status = "ANSWERED", prompt = "captcha",
            imageUrl = null, sourceUrl = "https://example.test/captcha", answer = "answer", createdAt = NOW,
            answeredAt = NOW, automationResumePending = true)
        Mockito.`when`(captchaQueries.findAccountByIdForUpdate(7L)).thenReturn(account)
        Mockito.`when`(captchaQueries.findPendingAutomationResumes(7L)).thenReturn(listOf(challenge))
        Mockito.`when`(lifecycle.resumeIfStoppedForCaptcha(7L, "CAPTCHA_ANSWERED")).thenReturn(false)
        Mockito.`when`(lifecycle.wakeFreshAfterCaptcha(7L, "CAPTCHA_ANSWERED")).thenReturn(true)

        assertTrue(service.resumeAfterCaptcha(7L))
        assertFalse(challenge.automationResumePending)

        val ordered = Mockito.inOrder(convergence, lifecycle)
        ordered.verify(convergence).releaseBattleGate(7L, NOW)
        ordered.verify(lifecycle).resumeIfStoppedForCaptcha(7L, "CAPTCHA_ANSWERED")
        ordered.verify(lifecycle).wakeFreshAfterCaptcha(7L, "CAPTCHA_ANSWERED")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-23T08:00:00Z")
    }
}
