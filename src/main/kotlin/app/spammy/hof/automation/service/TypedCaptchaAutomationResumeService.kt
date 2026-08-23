package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionConvergenceModule
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class TypedCaptchaAutomationResumeService(
    private val lifecycleBridge: TypedAutomationLifecycleBridge,
    private val convergenceModule: AutomationActionConvergenceModule,
    private val timeProvider: TimeProvider,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resumeAfterCaptcha(accountId: Long): Boolean {
        // 이 메서드는 캡차 답변 transaction의 afterCommit에서 호출된다. 게이트 해제도 같은
        // REQUIRES_NEW 경계에 포함해야 이미 commit된 transaction에 묻혀 유실되지 않는다.
        convergenceModule.releaseBattleGate(accountId, timeProvider.now())
        return lifecycleBridge.resumeIfStoppedForCaptcha(accountId, "CAPTCHA_ANSWERED") ||
            lifecycleBridge.wakeFreshAfterCaptcha(accountId, "CAPTCHA_ANSWERED")
    }
}
