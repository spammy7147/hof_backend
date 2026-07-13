package app.spammy.hof.account.service

import app.spammy.hof.account.repository.CookieQueryRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 쿠키 암호화 도입 전에 저장된 평문 HOF 세션 쿠키를 애플리케이션 시작 시 암호화한다.
 *
 * 새 로그인과 Set-Cookie 갱신은 저장 시점에 이미 암호화된다. 이 작업은 기존 데이터만 한 번
 * 보정하며, 암호화 접두사가 있는 행은 건너뛰므로 서버가 재시작되어도 값을 이중 암호화하지 않는다.
 */
@Service
class HofCookieEncryptionMigrationService(
    private val cookieQueryRepository: CookieQueryRepository,
    private val cookieCipher: HofCookieCipher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun encryptLegacyCookies() {
        val legacyCookies = cookieQueryRepository.findAll()
            .filterNot { cookie -> cookieCipher.isEncrypted(cookie.value) }

        if (legacyCookies.isEmpty()) return

        legacyCookies.forEach { cookie -> cookie.value = cookieCipher.encrypt(cookie.value) }
        log.info("Encrypted legacy HOF cookies count={}", legacyCookies.size)
    }
}
