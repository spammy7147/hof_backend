package app.spammy.hof.auth.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.auth.repository.RefreshTokenQueryRepository
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 계정 인증 수명주기와 실제 원격 제출 시작점을 계정 잠금 하나로 직렬화한다.
 *
 * 로그아웃이 먼저 commit되면 제출을 거부하고, 제출이 먼저 잠금을 얻으면 해당 원격 호출이 끝난 뒤에만
 * 로그아웃이 완료된다. 따라서 로그아웃 응답 이후 새 자동화 POST가 시작될 수 없다.
 */
fun interface AccountExecutionSubmissionGate {
    fun executeIfAuthorized(accountId: Long, submission: Runnable): Boolean
}

@Service
class LockedAccountExecutionSubmissionGate(
    private val accounts: AccountQueryRepository,
    private val refreshTokens: RefreshTokenQueryRepository,
    private val timeProvider: TimeProvider,
) : AccountExecutionSubmissionGate {
    @Transactional
    override fun executeIfAuthorized(accountId: Long, submission: Runnable): Boolean {
        accounts.findByIdForUpdate(accountId) ?: return false
        if (refreshTokens.countActiveByAccountId(accountId, timeProvider.now()) == 0L) return false
        submission.run()
        return true
    }
}
