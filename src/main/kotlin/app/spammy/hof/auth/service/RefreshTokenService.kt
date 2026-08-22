package app.spammy.hof.auth.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.auth.config.AuthProperties
import app.spammy.hof.auth.entity.RefreshTokenEntity
import app.spammy.hof.auth.repository.RefreshTokenQueryRepository
import app.spammy.hof.auth.repository.RefreshTokenRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * 불투명 refresh token을 발급하고 one-time rotation 수명 주기를 관리한다.
 *
 * 회전된 과거 토큰이 10초 이내 다시 도착하면 웹 멀티 탭 경합으로 보고 패밀리를 유지한다. 유예 시간이
 * 지난 뒤 재사용되면 탈취 가능성이 있으므로 로그인에서 파생된 패밀리 전체를 폐기한다.
 */
@Service
class RefreshTokenService(
    private val repository: RefreshTokenRepository,
    private val queryRepository: RefreshTokenQueryRepository,
    private val rateLimiter: AuthRateLimiter,
    private val properties: AuthProperties,
    private val timeProvider: TimeProvider,
) {
    private val secureRandom = SecureRandom()

    /** 새로운 로그인 패밀리를 시작하고 DB에는 원문이 아닌 SHA-256 해시만 저장한다. */
    @Transactional
    fun issue(account: HofAccountEntity, clientType: String): IssuedRefreshToken =
        issue(account, clientType, UUID.randomUUID().toString(), timeProvider.now())

    /**
     * 유효한 토큰을 한 번만 사용 처리하고 같은 패밀리의 새 토큰을 발급한다.
     *
     * 재사용 탐지 시 패밀리 폐기가 예외와 함께 rollback되지 않아야 하므로 [ApiException]은 이
     * 트랜잭션의 rollback 대상에서 제외한다.
     */
    @Transactional(noRollbackFor = [ApiException::class])
    fun rotate(rawToken: String): IssuedRefreshToken {
        val now = timeProvider.now()
        val token = queryRepository.findByTokenHashForUpdate(hash(rawToken))
            ?: throw invalidToken()

        if (token.revokedAt != null || !token.expiresAt.isAfter(now)) throw invalidToken()

        token.rotatedAt?.let { rotatedAt ->
            if (!now.isAfter(rotatedAt.plus(properties.refreshReuseGrace))) {
                throw ApiException(
                    ErrorCode.REFRESH_RETRY_REQUIRED,
                    "다른 요청에서 로그인 갱신이 진행되었습니다. 잠시 후 다시 시도해주세요.",
                )
            }
            revokeFamily(token.familyId, now)
            throw ApiException(
                ErrorCode.REFRESH_TOKEN_REUSED,
                "로그인 정보가 다시 사용되어 현재 기기의 로그인을 종료했습니다.",
            )
        }

        rateLimiter.checkRefresh(token.familyId, token.account.id)
        token.rotatedAt = now
        return issue(token.account, token.clientType, token.familyId, now)
    }

    /** 제출된 토큰이 존재하면 해당 로그인 패밀리를 모두 폐기하며, 이미 없는 토큰은 멱등 성공한다. */
    @Transactional
    fun logout(rawToken: String?) {
        if (rawToken.isNullOrBlank()) return
        val token = queryRepository.findByTokenHashForUpdate(hash(rawToken)) ?: return
        revokeFamily(token.familyId, timeProvider.now())
    }

    private fun issue(
        account: HofAccountEntity,
        clientType: String,
        familyId: String,
        now: Instant,
    ): IssuedRefreshToken {
        val rawToken = randomToken()
        val expiresAt = now.plus(properties.refreshTokenTtl)
        repository.save(
            RefreshTokenEntity(
                account = account,
                tokenHash = hash(rawToken),
                familyId = familyId,
                clientType = clientType,
                createdAt = now,
                expiresAt = expiresAt,
            ),
        )
        return IssuedRefreshToken(rawToken, expiresAt, familyId, account.id, clientType)
    }

    private fun revokeFamily(familyId: String, now: Instant) {
        queryRepository.findByFamilyId(familyId).forEach { token ->
            if (token.revokedAt == null) token.revokedAt = now
        }
    }

    private fun randomToken(): String =
        ByteArray(TOKEN_SIZE_BYTES)
            .also(secureRandom::nextBytes)
            .let { bytes -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) }

    private fun invalidToken() =
        ApiException(ErrorCode.AUTH_TOKEN_INVALID, "로그인 정보가 만료되었거나 유효하지 않습니다.")

    companion object {
        private const val TOKEN_SIZE_BYTES = 32

        /** refresh token 원문을 로그나 DB에 남기지 않고 조회 가능한 고정 길이 해시로 변환한다. */
        fun hash(rawToken: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(rawToken.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** 발급 응답 조립에 필요한 refresh token 값과 계정/클라이언트 문맥이다. */
data class IssuedRefreshToken(
    val value: String,
    val expiresAt: Instant,
    val familyId: String,
    val accountId: Long,
    val clientType: String,
)
