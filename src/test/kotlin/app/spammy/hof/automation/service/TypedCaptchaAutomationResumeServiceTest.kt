package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionConvergenceModule
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import org.mockito.Mockito

class TypedCaptchaAutomationResumeServiceTest {
    private val lifecycle = Mockito.mock(TypedAutomationLifecycleBridge::class.java)
    private val convergence = Mockito.mock(AutomationActionConvergenceModule::class.java)
    private val service = TypedCaptchaAutomationResumeService(
        lifecycle,
        convergence,
        TimeProvider { NOW },
    )

    @Test
    fun `running runtime is woken after captcha answer when stopped resume is not applicable`() {
        Mockito.`when`(lifecycle.resumeIfStoppedForCaptcha(7L, "CAPTCHA_ANSWERED")).thenReturn(false)
        Mockito.`when`(lifecycle.wakeFreshAfterCaptcha(7L, "CAPTCHA_ANSWERED")).thenReturn(true)

        assertTrue(service.resumeAfterCaptcha(7L))

        val ordered = Mockito.inOrder(convergence, lifecycle)
        ordered.verify(convergence).releaseBattleGate(7L, NOW)
        ordered.verify(lifecycle).resumeIfStoppedForCaptcha(7L, "CAPTCHA_ANSWERED")
        ordered.verify(lifecycle).wakeFreshAfterCaptcha(7L, "CAPTCHA_ANSWERED")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-23T08:00:00Z")
    }
}
