package app.spammy.hof.automation.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class TypedCaptchaAutomationResumeService(
    private val lifecycleBridge: TypedAutomationLifecycleBridge,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resumeAfterCaptcha(accountId: Long): Boolean =
        lifecycleBridge.resumeIfStoppedForCaptcha(accountId, "CAPTCHA_ANSWERED")
}
