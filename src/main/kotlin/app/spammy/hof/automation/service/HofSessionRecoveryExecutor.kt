package app.spammy.hof.automation.service

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import org.springframework.stereotype.Service

class AutomationLoginRequiredException(
    override val message: String = "HOF 로그인 정보를 확인해 주세요.",
) : RuntimeException(message)

@Service
class HofSessionRecoveryExecutor(
    private val accountService: HofAccountService,
) {
    fun <T> execute(
        accountId: Long,
        action: () -> T,
    ): T = try {
        action()
    } catch (error: ApiException) {
        if (error.errorCode != ErrorCode.HOF_SESSION_EXPIRED) throw error
        try {
            accountService.reauthenticate(accountId)
        } catch (loginError: ApiException) {
            if (loginError.errorCode == ErrorCode.HOF_LOGIN_FAILED) throw AutomationLoginRequiredException()
            throw loginError
        }
        action()
    }
}
