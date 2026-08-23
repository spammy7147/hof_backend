package app.spammy.hof.automation.service

import kotlin.test.Test
import kotlin.test.assertTrue
import org.mockito.Mockito

class TypedCaptchaAutomationResumeServiceTest {
    private val lifecycle = Mockito.mock(TypedAutomationLifecycleBridge::class.java)
    private val service = TypedCaptchaAutomationResumeService(lifecycle)

    @Test
    fun `running runtime is woken after captcha answer when stopped resume is not applicable`() {
        Mockito.`when`(lifecycle.resumeIfStoppedForCaptcha(7L, "CAPTCHA_ANSWERED")).thenReturn(false)
        Mockito.`when`(lifecycle.wakeFreshAfterCaptcha(7L, "CAPTCHA_ANSWERED")).thenReturn(true)

        assertTrue(service.resumeAfterCaptcha(7L))

        val ordered = Mockito.inOrder(lifecycle)
        ordered.verify(lifecycle).resumeIfStoppedForCaptcha(7L, "CAPTCHA_ANSWERED")
        ordered.verify(lifecycle).wakeFreshAfterCaptcha(7L, "CAPTCHA_ANSWERED")
    }
}
