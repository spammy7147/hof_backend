package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.AutomationActionConvergenceModule
import app.spammy.hof.captcha.repository.CaptchaQueryRepository
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class TypedCaptchaAutomationResumeService(
    private val lifecycleBridge: TypedAutomationLifecycleBridge,
    private val convergenceModule: AutomationActionConvergenceModule,
    private val timeProvider: TimeProvider,
    private val captchaQueries: CaptchaQueryRepository,
) {
    @Transactional(readOnly = true)
    fun findPendingAccountIds(): List<Long> = captchaQueries.findPendingAutomationResumeAccountIds()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resumeAfterCaptcha(accountId: Long): Boolean {
        captchaQueries.findAccountByIdForUpdate(accountId) ?: return false
        val pending = captchaQueries.findPendingAutomationResumes(accountId)
        if (pending.isEmpty()) return false
        // 같은 계정의 새 challenge가 생겼으면 과거 답안으로 현재 관문을 열지 않는다.
        if (captchaQueries.findLatestActiveByAccountId(accountId) != null) {
            pending.forEach { it.automationResumePending = false }
            return false
        }
        // afterCommit 전달 실패나 프로세스 중단에도 답안은 유지한다. 관문·runtime·outbox와
        // 재개 의도 소비를 같은 새 transaction에 넣어 실패하면 다음 복구에서 다시 시도한다.
        convergenceModule.releaseBattleGate(accountId, timeProvider.now())
        val resumed = lifecycleBridge.resumeIfStoppedForCaptcha(accountId, "CAPTCHA_ANSWERED") ||
            lifecycleBridge.wakeFreshAfterCaptcha(accountId, "CAPTCHA_ANSWERED")
        pending.forEach { it.automationResumePending = false }
        return resumed
    }
}
