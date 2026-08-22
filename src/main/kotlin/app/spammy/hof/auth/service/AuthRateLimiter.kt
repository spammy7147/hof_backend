package app.spammy.hof.auth.service

import app.spammy.hof.auth.config.AuthProperties
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * 공개 인증 API가 HOF 로그인 대입이나 과도한 token 갱신에 악용되지 않도록 고정 시간창 제한을 적용한다.
 *
 * 단일 서버용 인메모리 구현이므로 재시작 시 횟수가 초기화된다. 여러 백엔드 인스턴스를 운영할 때는 같은
 * 인터페이스를 Redis 기반 분산 limiter로 교체해야 모든 인스턴스가 제한 상태를 공유할 수 있다.
 */
@Service
class AuthRateLimiter(
    private val properties: AuthProperties,
    private val timeProvider: TimeProvider,
) {
    private val lock = Any()
    private val windows = mutableMapOf<String, RequestWindow>()
    private var lastCleanupAt: Instant = Instant.EPOCH

    init {
        require(!properties.rateLimitWindow.isZero && !properties.rateLimitWindow.isNegative)
        require(properties.loginRateLimitPerId > 0)
        require(properties.refreshRateLimitPerFamily > 0)
        require(properties.refreshRateLimitPerAccount > 0)
    }

    /** Ingress의 출발지 제한과 별개로 HOF 로그인 대상 ID를 제한한다. */
    fun checkLogin(loginId: String) {
        consume(
            listOf(
                LimitKey("login:id:${hashLoginId(loginId)}", properties.loginRateLimitPerId),
            ),
        )
    }

    /** 검증된 내부 식별자만 사용해 로그인 패밀리와 계정 전체의 갱신을 원자적으로 제한한다. */
    fun checkRefresh(familyId: String, accountId: Long) {
        consume(
            listOf(
                LimitKey("refresh:family:$familyId", properties.refreshRateLimitPerFamily),
                LimitKey("refresh:account:$accountId", properties.refreshRateLimitPerAccount),
            ),
        )
    }

    private fun consume(keys: List<LimitKey>) = synchronized(lock) {
        val now = timeProvider.now()
        cleanupExpiredWindows(now)
        val active = keys.map { key -> key to activeWindow(key.value, now) }
        active.firstOrNull { (key, window) -> window != null && window.count >= key.limit }
            ?.let { (_, window) -> throw rateLimited(now, requireNotNull(window).resetsAt) }

        active.forEach { (key, window) ->
            if (window == null) {
                windows[key.value] = RequestWindow(count = 1, resetsAt = now.plus(properties.rateLimitWindow))
            } else {
                window.count += 1
            }
        }
    }

    private fun activeWindow(key: String, now: Instant): RequestWindow? =
        windows[key]?.takeIf { window -> window.resetsAt.isAfter(now) }

    private fun cleanupExpiredWindows(now: Instant) {
        if (now.isBefore(lastCleanupAt.plus(properties.rateLimitWindow))) return
        windows.entries.removeIf { (_, window) -> !window.resetsAt.isAfter(now) }
        lastCleanupAt = now
    }

    private fun rateLimited(now: Instant, resetsAt: Instant): ApiException {
        val remainingMillis = Duration.between(now, resetsAt).toMillis().coerceAtLeast(1)
        val retryAfter = ((remainingMillis + 999) / 1_000).coerceAtLeast(1)
        return ApiException(
            errorCode = ErrorCode.RATE_LIMITED,
            message = "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
            retryAfterSeconds = retryAfter,
        )
    }

    private fun hashLoginId(loginId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(loginId.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private data class LimitKey(val value: String, val limit: Int)
    private data class RequestWindow(var count: Int, val resetsAt: Instant)
}
