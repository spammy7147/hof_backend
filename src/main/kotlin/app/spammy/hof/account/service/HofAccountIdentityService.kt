package app.spammy.hof.account.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.common.time.TimeProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 원격 로그인 transaction과 무관하게 HOF 계정 ID와 최신 암호화 credential을 먼저 확정한다. */
@Service
class HofAccountIdentityService(
    private val accountRepository: HofAccountRepository,
    private val accountQueryRepository: AccountQueryRepository,
    private val credentialCipher: CredentialCipher,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(HofAccountIdentityService::class.java)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persist(loginId: String, password: String): HofAccountEntity {
        val account = accountQueryRepository.findByLoginId(loginId)
            ?.apply {
                log.info("HOF account found loginId={} accountId={}", loginId, id)
                encryptedPassword = credentialCipher.encrypt(password)
            }
            ?: HofAccountEntity(
                loginId = loginId,
                encryptedPassword = credentialCipher.encrypt(password),
                createdAt = timeProvider.now(),
            )
        return accountRepository.save(account).also { accountRepository.flush() }
    }
}
