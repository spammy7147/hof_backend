package app.spammy.hof.external.client

import app.spammy.hof.character.service.CharacterRosterObservationService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.status.service.HofStatusSnapshotService
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** 계정 범위 HOF 요청을 실행하고 응답의 완전한 상단 상태를 보조 스냅샷으로 관측한다. */
@Component
class AccountHofGateway(
    private val gateway: HofGateway,
    private val snapshots: HofStatusSnapshotService,
    private val characterRosters: CharacterRosterObservationService,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(AccountHofGateway::class.java)

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
        val response = gateway.execute(accountId, request, cookies)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        observe(accountId, response, requestStartedAt, observeCharacterRoster)
                    }
                },
            )
        } else {
            observe(accountId, response, requestStartedAt, observeCharacterRoster)
        }
        return DeferredCharacterRosterHofResponse(response, requestStartedAt, timeProvider.now())
    }

    fun observe(accountId: Long, response: HofHttpResponse, requestStartedAt: Instant) =
        observe(accountId, response, requestStartedAt, observeCharacterRoster = true)

    private fun observe(
        accountId: Long,
        response: HofHttpResponse,
        requestStartedAt: Instant,
        observeCharacterRoster: Boolean,
    ) {
        runCatching { snapshots.observe(accountId, response.body, requestStartedAt) }
            .onFailure { error ->
                log.warn("HOF status observation failed accountId={}", accountId, error)
            }
        if (!observeCharacterRoster) return
        runCatching { characterRosters.observe(accountId, response, requestStartedAt) }
            .onFailure { error ->
                log.warn("HOF character roster observation failed accountId={}", accountId, error)
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
