package app.spammy.hof.captcha.service

import app.spammy.hof.account.repository.AccountQueryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 사용자 조치 terminal 상태와 해당 push outbox를 하나의 commit으로 보존한다. */
@Service
class CaptchaPassTerminalService(
    private val accounts: AccountQueryRepository,
    private val maintenance: CaptchaPassMaintenanceService,
    private val notifications: CaptchaNotificationGateway,
) {
    @Transactional
    fun finishManualRequired(accountId: Long, token: String, challengeId: Long) {
        val account = accounts.findById(accountId) ?: return
        if (maintenance.finishManualRequired(accountId, token, challengeId)) {
            notifications.captchaRequired(account, challengeId, "pass-manual-$token")
        }
    }

    @Transactional
    fun finishLoginRequired(accountId: Long, token: String) {
        val account = accounts.findById(accountId) ?: return
        if (maintenance.finishLoginRequired(accountId, token)) {
            notifications.loginRequired(account, "pass-login-$token")
        }
    }
}
