package app.spammy.hof.external.client

import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import java.time.Instant
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** 계정 범위 HOF 요청과 원래 시각을 보존하고 응답 관측의 실행 시점을 관리한다. */
@Component
class AccountHofGateway(
    private val gateway: HofGateway,
    private val observations: AccountHofResponseObserver,
    private val timeProvider: TimeProvider,
) {
    companion object {
        private data class CookieChain(val accountId: Long, val updates: MutableMap<String, String> = linkedMapOf())
        private val cookieChain = ThreadLocal<CookieChain>()

        /** 같은 동기 관측/명령 안의 중첩 요청도 앞선 응답의 쿠키 갱신을 이어받는다. 범위 종료 시 폐기한다. */
        internal fun <T> withCookieChain(accountId: Long, operation: (Map<String, String>) -> T): T {
            val previous = cookieChain.get()
            val current = previous?.takeIf { it.accountId == accountId } ?: CookieChain(accountId)
            cookieChain.set(current)
            return try {
                operation(current.updates)
            } finally {
                if (previous == null) cookieChain.remove() else cookieChain.set(previous)
            }
        }
    }

    fun execute(
        accountId: Long,
        request: HofRequest,
        cookies: Map<String, String> = emptyMap(),
    ): HofHttpResponse = executeObserved(
        accountId,
        request,
        cookies,
        requestStartedAt = timeProvider.now(),
        observeCharacterRoster = true,
    ).response

    /** 명령 모듈이 같은 응답을 원자적으로 반영할 때 roster 공통 관측만 유예한다. */
    fun executeWithoutCharacterRosterObservation(
        accountId: Long,
        request: HofRequest,
        cookies: Map<String, String> = emptyMap(),
    ): DeferredCharacterRosterHofResponse {
        val requestStartedAt = timeProvider.now()
        return try {
            executeObserved(accountId, request, cookies, requestStartedAt, observeCharacterRoster = false)
        } catch (error: Exception) {
            throw DeferredCharacterRosterRequestException(requestStartedAt, timeProvider.now(), error)
        }
    }

    private fun executeObserved(
        accountId: Long,
        request: HofRequest,
        cookies: Map<String, String>,
        requestStartedAt: Instant,
        observeCharacterRoster: Boolean,
    ): DeferredCharacterRosterHofResponse {
        val chain = cookieChain.get()?.takeIf { it.accountId == accountId }
        val response = gateway.execute(accountId, request, cookies + chain?.updates.orEmpty())
        chain?.updates?.putAll(response.setCookies)
        val responseObservedAt = timeProvider.now()
        observe(accountId, response, requestStartedAt, responseObservedAt, observeCharacterRoster)
        return DeferredCharacterRosterHofResponse(response, requestStartedAt, responseObservedAt)
    }

    /** 이미 받은 원격 응답의 시각도 callback 등록 전에 확정해야 한다. */
    fun observe(accountId: Long, response: HofHttpResponse, requestStartedAt: Instant, responseObservedAt: Instant) =
        observe(accountId, response, requestStartedAt, responseObservedAt, observeCharacterRoster = true)

    private fun observe(
        accountId: Long,
        response: HofHttpResponse,
        requestStartedAt: Instant,
        responseObservedAt: Instant,
        observeCharacterRoster: Boolean,
    ) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        observations.observe(accountId, response, requestStartedAt, responseObservedAt, observeCharacterRoster)
                    }
                },
            )
        } else {
            observations.observe(accountId, response, requestStartedAt, responseObservedAt, observeCharacterRoster)
        }
    }
}

data class DeferredCharacterRosterHofResponse(
    val response: HofHttpResponse,
    val requestStartedAt: Instant,
    val responseObservedAt: Instant = requestStartedAt,
)

/** 요청이 원격에 적용됐는지 알 수 없을 때도 보수적 projection에 쓸 요청 시작 시각을 보존한다. */
class DeferredCharacterRosterRequestException(
    val requestStartedAt: Instant,
    val failureObservedAt: Instant,
    cause: Throwable,
) : RuntimeException("HOF 요청 결과를 확인하지 못했습니다.", cause)
