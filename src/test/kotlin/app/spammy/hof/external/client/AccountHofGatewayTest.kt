package app.spammy.hof.external.client

import app.spammy.hof.character.service.CharacterRosterObservationService
import app.spammy.hof.captcha.service.CaptchaPassMaintenanceService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.status.service.HofStatusSnapshotService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.mockito.Mockito
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class AccountHofGatewayTest {
    private val snapshots = Mockito.mock(HofStatusSnapshotService::class.java)
    private val characterRosters = Mockito.mock(CharacterRosterObservationService::class.java)
    private val passMaintenance = Mockito.mock(CaptchaPassMaintenanceService::class.java)
    private val raw = RecordingGateway()
    private val gateway = AccountHofGateway(
        raw,
        AccountHofResponseObserver(snapshots, characterRosters, passMaintenance),
        TimeProvider { REQUEST_STARTED_AT },
    )

    @Test
    fun `returns the raw response and records it with request start time`() {
        val actual = gateway.execute(ACCOUNT_ID, REQUEST, COOKIES)

        assertSame(RESPONSE, actual)
        val order = Mockito.inOrder(snapshots, passMaintenance, characterRosters)
        order.verify(snapshots).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT)
        order.verify(passMaintenance).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT, REQUEST_STARTED_AT)
        order.verify(characterRosters).observe(ACCOUNT_ID, RESPONSE, REQUEST_STARTED_AT)
        assertEquals(listOf(ACCOUNT_ID), raw.accountIds)
        kotlin.test.assertEquals(COOKIES, raw.cookies)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["status", "pass", "roster"])
    fun `각 관측의 실패는 원래 응답과 나머지 관측을 막지 않는다`(failed: String) {
        val error = IllegalStateException("observation unavailable")
        when (failed) {
            "status" -> Mockito.doThrow(error).`when`(snapshots).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT)
            "pass" -> Mockito.doThrow(error).`when`(passMaintenance).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT, REQUEST_STARTED_AT)
            "roster" -> Mockito.doThrow(error).`when`(characterRosters).observe(ACCOUNT_ID, RESPONSE, REQUEST_STARTED_AT)
        }

        assertSame(RESPONSE, gateway.execute(ACCOUNT_ID, REQUEST, COOKIES))

        val order = Mockito.inOrder(snapshots, passMaintenance, characterRosters)
        order.verify(snapshots).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT)
        order.verify(passMaintenance).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT, REQUEST_STARTED_AT)
        order.verify(characterRosters).observe(ACCOUNT_ID, RESPONSE, REQUEST_STARTED_AT)
    }

    @Test
    fun `semantic command followup keeps status observation but defers generic roster mutation`() {
        val times = ArrayDeque(listOf(REQUEST_STARTED_AT, RESPONSE_OBSERVED_AT))
        val timedGateway = AccountHofGateway(raw, AccountHofResponseObserver(snapshots, characterRosters, passMaintenance), TimeProvider { times.removeFirst() })
        val actual = timedGateway.executeWithoutCharacterRosterObservation(ACCOUNT_ID, REQUEST, COOKIES)

        assertSame(RESPONSE, actual.response)
        assertEquals(REQUEST_STARTED_AT, actual.requestStartedAt)
        assertEquals(RESPONSE_OBSERVED_AT, actual.responseObservedAt)
        val order = Mockito.inOrder(snapshots, passMaintenance)
        order.verify(snapshots).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT)
        order.verify(passMaintenance).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT, RESPONSE_OBSERVED_AT)
        Mockito.verifyNoInteractions(characterRosters)
    }

    @Test
    fun `deferred roster request failure preserves the request start time for conservative projection`() {
        raw.failure = IllegalStateException("response lost")

        val times = ArrayDeque(listOf(REQUEST_STARTED_AT, RESPONSE_OBSERVED_AT))
        val timedGateway = AccountHofGateway(raw, AccountHofResponseObserver(snapshots, characterRosters, passMaintenance), TimeProvider { times.removeFirst() })
        val error = assertFailsWith<DeferredCharacterRosterRequestException> {
            timedGateway.executeWithoutCharacterRosterObservation(ACCOUNT_ID, REQUEST, COOKIES)
        }

        assertEquals(REQUEST_STARTED_AT, error.requestStartedAt)
        assertEquals(RESPONSE_OBSERVED_AT, error.failureObservedAt)
        assertSame(raw.failure, error.cause)
        Mockito.verifyNoInteractions(snapshots, characterRosters, passMaintenance)
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = [TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_ROLLED_BACK])
    fun `트랜잭션 commit과 rollback 모두 완료 후 순서대로 관측한다`(completion: Int) {
        TransactionSynchronizationManager.initSynchronization()
        try {
            assertSame(RESPONSE, gateway.execute(ACCOUNT_ID, REQUEST, COOKIES))
            Mockito.verifyNoInteractions(snapshots, passMaintenance, characterRosters)

            TransactionSynchronizationManager.getSynchronizations().single()
                .afterCompletion(completion)

            val order = Mockito.inOrder(snapshots, passMaintenance, characterRosters)
            order.verify(snapshots).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT)
            order.verify(passMaintenance).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT, REQUEST_STARTED_AT)
            order.verify(characterRosters).observe(ACCOUNT_ID, RESPONSE, REQUEST_STARTED_AT)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = [TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_ROLLED_BACK])
    fun `transaction callback keeps the response arrival time instead of the later completion time`(completion: Int) {
        val times = ArrayDeque(listOf(REQUEST_STARTED_AT, RESPONSE_OBSERVED_AT))
        val timedGateway = AccountHofGateway(
            raw,
            AccountHofResponseObserver(snapshots, characterRosters, passMaintenance),
            TimeProvider { times.removeFirst() },
        )
        TransactionSynchronizationManager.initSynchronization()
        try {
            timedGateway.execute(ACCOUNT_ID, REQUEST, COOKIES)
            TransactionSynchronizationManager.getSynchronizations().single()
                .afterCompletion(completion)

            Mockito.verify(passMaintenance).observe(
                ACCOUNT_ID,
                RESPONSE.body,
                REQUEST_STARTED_AT,
                RESPONSE_OBSERVED_AT,
            )
            assertEquals(0, times.size)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    private class RecordingGateway : HofGateway {
        val accountIds = mutableListOf<Long>()
        var cookies: Map<String, String> = emptyMap()
        var failure: RuntimeException? = null

        override fun execute(
            accountId: Long,
            request: HofRequest,
            cookies: Map<String, String>,
        ): HofHttpResponse {
            accountIds += accountId
            this.cookies = cookies
            failure?.let { throw it }
            return RESPONSE
        }
    }

    private companion object {
        const val ACCOUNT_ID = 17L
        val REQUEST_STARTED_AT: Instant = Instant.parse("2026-07-24T10:00:00Z")
        val RESPONSE_OBSERVED_AT: Instant = REQUEST_STARTED_AT.plusSeconds(2)
        val REQUEST = HofRequest(HofHttpMethod.GET, "https://hof.zerosic.com/index.php")
        val COOKIES = mapOf("PHPSESSID" to "session")
        val RESPONSE = HofHttpResponse(200, REQUEST.url, "<html>response</html>", emptyMap())
    }
}
