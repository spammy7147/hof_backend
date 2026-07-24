package app.spammy.hof.external.client

import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.status.service.HofStatusSnapshotService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertSame
import org.mockito.Mockito
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class AccountHofGatewayTest {
    private val snapshots = Mockito.mock(HofStatusSnapshotService::class.java)
    private val raw = RecordingGateway()
    private val gateway = AccountHofGateway(raw, snapshots, TimeProvider { REQUEST_STARTED_AT })

    @Test
    fun `returns the raw response and records it with request start time`() {
        val actual = gateway.execute(ACCOUNT_ID, REQUEST, COOKIES)

        assertSame(RESPONSE, actual)
        Mockito.verify(snapshots).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT)
        kotlin.test.assertEquals(COOKIES, raw.cookies)
    }

    @Test
    fun `observation failure does not fail the original request`() {
        Mockito.doThrow(IllegalStateException("db unavailable"))
            .`when`(snapshots).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT)

        assertSame(RESPONSE, gateway.execute(ACCOUNT_ID, REQUEST, COOKIES))
    }

    @Test
    fun `active transaction defers observation until transaction completion`() {
        TransactionSynchronizationManager.initSynchronization()
        try {
            assertSame(RESPONSE, gateway.execute(ACCOUNT_ID, REQUEST, COOKIES))
            Mockito.verifyNoInteractions(snapshots)

            TransactionSynchronizationManager.getSynchronizations().single()
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)

            Mockito.verify(snapshots).observe(ACCOUNT_ID, RESPONSE.body, REQUEST_STARTED_AT)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    private class RecordingGateway : HofGateway {
        var cookies: Map<String, String> = emptyMap()

        override fun execute(request: HofRequest, cookies: Map<String, String>): HofHttpResponse {
            this.cookies = cookies
            return RESPONSE
        }
    }

    private companion object {
        const val ACCOUNT_ID = 17L
        val REQUEST_STARTED_AT: Instant = Instant.parse("2026-07-24T10:00:00Z")
        val REQUEST = HofRequest(HofHttpMethod.GET, "http://sic.zerosic.com/ZeroHOF/index.php")
        val COOKIES = mapOf("PHPSESSID" to "session")
        val RESPONSE = HofHttpResponse(200, REQUEST.url, "<html>response</html>", emptyMap())
    }
}
