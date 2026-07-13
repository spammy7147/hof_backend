package app.spammy.hof.account.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.account.repository.HofCookieRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.LoginStateParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
/**
 * HOF 계정 저장과 원본 서버 로그인 흐름을 담당한다.
 *
 * 로그인 성공 시 HOF 원본 서버가 내려준 쿠키를 DB에 저장하고, 이후 상태/전투/캐릭터 API가 그 쿠키를 재사용한다.
 */
class HofAccountService(
    private val accountRepository: HofAccountRepository,
    private val cookieRepository: HofCookieRepository,
    private val accountQueryRepository: AccountQueryRepository,
    private val cookieQueryRepository: CookieQueryRepository,
    private val credentialCipher: CredentialCipher,
    private val requestFactory: HofRequestFactory,
    private val gateway: HofGateway,
    private val loginStateParser: LoginStateParser,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(HofAccountService::class.java)

    /**
     * 사용자가 입력한 ID/PW로 HOF 원본 서버에 로그인한다.
     *
     * 같은 ID가 이미 저장되어 있으면 비밀번호를 갱신하고, 없으면 새 계정을 만든다.
     */
    @Transactional
    fun authenticate(loginId: String, password: String): HofAccountEntity {
        val trimmedLoginId = loginId.trim()
        if (trimmedLoginId.isBlank() || password.isBlank()) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "HOF ID와 비밀번호를 입력하세요.")
        }

        log.info("HOF login requested loginId={}", trimmedLoginId)
        val account = accountQueryRepository.findByLoginId(trimmedLoginId)
            ?.apply {
                log.info("HOF account found loginId={} accountId={}", trimmedLoginId, id)
                encryptedPassword = credentialCipher.encrypt(password)
            }
            ?: HofAccountEntity(
                loginId = trimmedLoginId,
                encryptedPassword = credentialCipher.encrypt(password),
                createdAt = timeProvider.now(),
            )

        return loginAccount(accountRepository.save(account))
    }

    /**
     * HOF 홈 요청 후 로그인 요청을 보내고, 성공하면 쿠키를 DB에 저장한다.
     */
    private fun loginAccount(account: HofAccountEntity): HofAccountEntity {
        log.info("HOF login start accountId={} loginId={}", account.id, account.loginId)
        val initialResponse = gateway.execute(requestFactory.home())
        log.info(
            "HOF login home fetched accountId={} status={} cookies={}",
            account.id,
            initialResponse.statusCode,
            initialResponse.setCookies.keys.sorted(),
        )
        val loginResponse = gateway.execute(
            request = requestFactory.login(
                id = account.loginId,
                password = credentialCipher.decrypt(account.encryptedPassword),
            ),
            cookies = initialResponse.setCookies,
        )
        val loginState = loginStateParser.parse(loginResponse.body)
        log.info(
            "HOF login response accountId={} status={} loggedIn={} hasLoginForm={} hasCharacterLinks={} cookies={}",
            account.id,
            loginResponse.statusCode,
            loginState.isLoggedIn,
            loginState.hasLoginForm,
            loginState.hasCharacterLinks,
            loginResponse.setCookies.keys.sorted(),
        )

        if (!loginState.isLoggedIn) {
            log.warn("HOF login failed accountId={} loginId={}", account.id, account.loginId)
            throw ApiException(ErrorCode.HOF_LOGIN_FAILED, "HOF 로그인에 실패했습니다.")
        }

        val now = timeProvider.now()
        val cookies = initialResponse.setCookies + loginResponse.setCookies
        val existingCookies = cookieQueryRepository.findByAccountId(account.id)
        cookieRepository.deleteAll(existingCookies)
        cookieRepository.flush()
        log.info("HOF cookies cleared accountId={}", account.id)
        cookies.forEach { (name, value) ->
            cookieRepository.save(
                HofCookieEntity(
                    account = account,
                    name = name,
                    value = value,
                    domain = "sic.zerosic.com",
                    path = "/ZeroHOF",
                    updatedAt = now,
                ),
            )
        }
        log.info("HOF cookies stored accountId={} cookieNames={}", account.id, cookies.keys.sorted())
        account.lastLoginAt = now
        accountRepository.save(account)
        log.info("HOF login success accountId={} cookieCount={}", account.id, cookies.size)

        return account
    }
}
