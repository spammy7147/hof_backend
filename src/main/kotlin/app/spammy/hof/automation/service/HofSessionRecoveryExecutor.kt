package app.spammy.hof.automation.service

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import org.springframework.stereotype.Service

class AutomationLoginRequiredException(
    override val message: String = "HOF 로그인 정보를 확인해 주세요.",
) : RuntimeException(message)

@Service
class HofSessionRecoveryExecutor(
    private val sessionRecoveryService: HofSessionRecoveryService,
) {
    fun <T> execute(
        accountId: Long,
        action: () -> T,
    ): T = try {
        sessionRecoveryService.execute(accountId, action)
    } catch (loginError: ApiException) {
        if (loginError.errorCode == ErrorCode.HOF_LOGIN_FAILED) throw AutomationLoginRequiredException()
        throw loginError
    }
}
