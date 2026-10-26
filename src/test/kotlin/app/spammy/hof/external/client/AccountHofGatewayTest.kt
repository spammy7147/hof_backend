package app.spammy.hof.external.client

import app.spammy.hof.character.service.CharacterRosterObservationService
import app.spammy.hof.character.service.CharacterRosterObservationTransaction
import app.spammy.hof.external.parser.CharacterRosterParser
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.service.AccountHofMutationFence
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import app.spammy.hof.character.service.SessionPatternLoadTracker
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource("hunt,false", "menu=buy,false", "hunt,true", "menu=buy,true")
    fun `맵과 상점 응답은 실제 roster 관측에서도 변경 잠금 해제 전에 반환한다`(query: String, deferred: Boolean) {
        val fence = AccountHofMutationFence()
        val transactions = Mockito.mock(CharacterRosterObservationTransaction::class.java)
        val roster = CharacterRosterObservationService(LoginStateParser(), CharacterRosterParser(), fence, transactions)
        val actual = AccountHofGateway(raw, AccountHofResponseObserver(snapshots, roster, passMaintenance), TimeProvider { REQUEST_STARTED_AT }, SessionPatternLoadTracker())
        raw.response = RESPONSE.copy(finalUrl = "${REQUEST.url}?$query")
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val holder = pool.submit { fence.execute(ACCOUNT_ID) { held.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            check(held.await(2, TimeUnit.SECONDS))
            val read = pool.submit<HofHttpResponse> {
                if (deferred) TransactionSynchronizationManager.initSynchronization()
                try {
                    actual.execute(ACCOUNT_ID, REQUEST.copy(url = raw.response.finalUrl), COOKIES).also {
                        if (deferred) TransactionSynchronizationManager.getSynchronizations().single()
                            .afterCompletion(TransactionSynchronization.STATUS_COMMITTED)
                    }
                } finally {
                    if (deferred) TransactionSynchronizationManager.clearSynchronization()
                }
            }
            try {
                assertSame(raw.response, read.get(1, TimeUnit.SECONDS))
                Mockito.verifyNoInteractions(transactions)
                Mockito.verify(snapshots).observe(ACCOUNT_ID, raw.response.body, REQUEST_STARTED_AT)
                Mockito.verify(passMaintenance).observe(ACCOUNT_ID, raw.response.body, REQUEST_STARTED_AT, REQUEST_STARTED_AT)
            } finally {
                release.countDown()
                holder.get(2, TimeUnit.SECONDS)
                read.get(2, TimeUnit.SECONDS)
            }
        }
        assertEquals(1L, fence.observe(ACCOUNT_ID) {}.revision, "무관한 조회는 변경 revision을 증가시키지 않는다")
    }
    private val snapshots = Mockito.mock(HofStatusSnapshotService::class.java)
    private val characterRosters = Mockito.mock(CharacterRosterObservationService::class.java)
    private val passMaintenance = Mockito.mock(CaptchaPassMaintenanceService::class.java)
    private val raw = RecordingGateway()
    private val gateway = AccountHofGateway(
        raw,
        AccountHofResponseObserver(snapshots, characterRosters, passMaintenance),
        TimeProvider { REQUEST_STARTED_AT },
        SessionPatternLoadTracker(),
    )

    @Test
    fun `쿠키 갱신은 같은 명령과 계정에서만 이어지고 예외 뒤에는 남지 않는다`() {
        raw.response = RESPONSE.copy(setCookies = mapOf("PHPSESSID" to "rotated"))
        assertFailsWith<IllegalStateException> {
            AccountHofGateway.withCookieChain(ACCOUNT_ID) {
                gateway.execute(ACCOUNT_ID, REQUEST, COOKIES)
                gateway.execute(ACCOUNT_ID + 1, REQUEST, mapOf("PHPSESSID" to "other-account"))
                assertEquals(mapOf("PHPSESSID" to "other-account"), raw.cookies)
                gateway.execute(ACCOUNT_ID, REQUEST, COOKIES)
                assertEquals(COOKIES + raw.response.setCookies, raw.cookies)
                throw IllegalStateException("sequence stopped")
            }
        }
        gateway.execute(ACCOUNT_ID, REQUEST, COOKIES)
        assertEquals(COOKIES, raw.cookies)
    }

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
        val timedGateway = AccountHofGateway(raw, AccountHofResponseObserver(snapshots, characterRosters, passMaintenance), TimeProvider { times.removeFirst() }, SessionPatternLoadTracker())
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
        val timedGateway = AccountHofGateway(raw, AccountHofResponseObserver(snapshots, characterRosters, passMaintenance), TimeProvider { times.removeFirst() }, SessionPatternLoadTracker())
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
            SessionPatternLoadTracker(),
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
        var response: HofHttpResponse = RESPONSE

        override fun execute(
            accountId: Long,
            request: HofRequest,
            cookies: Map<String, String>,
        ): HofHttpResponse {
            accountIds += accountId
            this.cookies = cookies
            failure?.let { throw it }
            return response
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
