package app.spammy.hof.auth.service

import app.spammy.hof.auth.repository.RefreshTokenQueryRepository
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** CAPTCHA 답안 POST 직전에 실제 활성 앱 refresh-token family가 남아 있는지 확인한다. */
@Service
class AccountExecutionAuthorizationReader(
    private val refreshTokens: RefreshTokenQueryRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun isExecutionAllowed(accountId: Long): Boolean =
        refreshTokens.countActiveByAccountId(accountId, timeProvider.now()) > 0
}
