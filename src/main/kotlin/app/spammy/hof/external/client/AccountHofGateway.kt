package app.spammy.hof.external.client

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
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(AccountHofGateway::class.java)

    fun execute(
        accountId: Long,
        request: HofRequest,
        cookies: Map<String, String> = emptyMap(),
    ): HofHttpResponse {
        val requestStartedAt = timeProvider.now()
        val response = gateway.execute(accountId, request, cookies)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        observe(accountId, response, requestStartedAt)
                    }
                },
            )
        } else {
            observe(accountId, response, requestStartedAt)
        }
        return response
    }

    fun observe(accountId: Long, response: HofHttpResponse, requestStartedAt: Instant) {
        runCatching { snapshots.observe(accountId, response.body, requestStartedAt) }
            .onFailure { error ->
                log.warn("HOF status observation failed accountId={}", accountId, error)
            }
    }
}
