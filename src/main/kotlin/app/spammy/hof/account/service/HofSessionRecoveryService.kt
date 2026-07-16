package app.spammy.hof.account.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import org.springframework.stereotype.Service

/**
 * 저장된 HOF 로그인 정보로 만료된 원본 세션을 복구하고 요청을 한 번 다시 실행한다.
 */
@Service
class HofSessionRecoveryService(
    private val accountService: HofAccountService,
) {
    fun <T> execute(
        accountId: Long,
        action: () -> T,
    ): T = try {
        action()
    } catch (error: Throwable) {
        val sessionError = generateSequence(error) { it.cause }
            .filterIsInstance<ApiException>()
            .firstOrNull { it.errorCode == ErrorCode.HOF_SESSION_EXPIRED }
            ?: throw error
        accountService.reauthenticate(accountId)
        action()
    }
}
