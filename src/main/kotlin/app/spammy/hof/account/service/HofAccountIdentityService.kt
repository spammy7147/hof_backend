package app.spammy.hof.account.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.common.time.TimeProvider
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 기존 identity를 조회하고, 동시 최초 생성 경쟁에서는 commit된 승자 identity를 반환한다. */
@Service
class HofAccountIdentityService(
    private val accountQueryRepository: AccountQueryRepository,
    private val accountIdentityCreator: HofAccountIdentityCreator,
) {
    fun resolve(loginId: String, initialPassword: String): HofAccountEntity {
        accountQueryRepository.findByLoginId(loginId)?.let { return it }
        return try {
            accountIdentityCreator.create(loginId, initialPassword)
        } catch (_: DataIntegrityViolationException) {
            accountQueryRepository.findByLoginId(loginId) ?: throw IllegalStateException(
                "Concurrent HOF account identity creation committed no winner for loginId=$loginId",
            )
        }
    }
}

/** 원격 로그인 transaction과 무관하게 신규 HOF 계정 ID를 먼저 확정한다. */
@Service
class HofAccountIdentityCreator(
    private val accountRepository: HofAccountRepository,
    private val credentialCipher: CredentialCipher,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(HofAccountIdentityCreator::class.java)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun create(loginId: String, initialPassword: String): HofAccountEntity {
        val account = HofAccountEntity(
            loginId = loginId,
            encryptedPassword = credentialCipher.encrypt(initialPassword),
            createdAt = timeProvider.now(),
        )
        return accountRepository.save(account).also { accountRepository.flush() }
            .also { created -> log.info("HOF account created loginId={} accountId={}", loginId, created.id) }
    }
}
