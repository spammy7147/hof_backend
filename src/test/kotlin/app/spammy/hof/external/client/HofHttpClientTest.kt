package app.spammy.hof.external.client

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.config.HofRequestProperties
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofRequestOrigin
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HofHttpClientTest {
    @Test
    fun decodesEucKrBody() {
        val bytes = "소셜".toByteArray(charset("EUC-KR"))

        val decoded = HofHttpClient.decodeBody(bytes, "text/html; charset=EUC-KR")

        assertEquals("소셜", decoded)
    }

    @Test
    fun executeAppendsGetFormFieldsToQueryString() {
        val capturedQueries = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/ZeroHOF/pass_check.php") { exchange ->
            capturedQueries += exchange.requestURI.rawQuery
            exchange.sendText("OK")
        }
        server.start()

        try {
            val port = server.address.port
            val client = client()

            client.execute(
                HofRequest(
                    method = HofHttpMethod.GET,
                    url = "http://localhost:$port/ZeroHOF/pass_check.php?existing=1#fragment",
                    formFields = linkedMapOf(
                        "pass code" to "12 34",
                        "mode" to "battle",
                    ),
                ),
            )

            assertEquals("existing=1&pass+code=12+34&mode=battle", capturedQueries.single())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `interactive 503 becomes a friendly service unavailable API error`() {
        val server = serverReturning(503)

        try {
            val error = assertFailsWith<ApiException> {
                client().execute(
                    HofRequest(
                        method = HofHttpMethod.GET,
                        url = "http://localhost:${server.address.port}/test",
                    ),
                )
            }

            assertEquals(ErrorCode.HOF_TEMPORARILY_UNAVAILABLE, error.errorCode)
            assertEquals(
                "HOF 서버 연결이 일시적으로 원활하지 않습니다. 잠시 후 다시 시도해 주세요.",
                error.message,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `automation 503 is converted to a global deferred signal`() {
        val server = serverReturning(503)
        val now = Instant.parse("2026-07-23T00:00:00Z")
        val governor = HofRequestGovernor(
            properties = HofRequestProperties(),
            timeProvider = TimeProvider { now },
            waiter = HofRequestWaiter { _: Duration -> },
        )

        try {
            val error = assertFailsWith<HofAutomationDeferredException> {
                HofHttpClient(governor = governor).execute(
                    HofRequest(
                        method = HofHttpMethod.GET,
                        url = "http://localhost:${server.address.port}/test",
                        origin = HofRequestOrigin.AUTOMATION,
                    ),
                )
            }

            assertEquals(now.plusSeconds(30), error.retryAt)
            assertEquals(1, error.consecutiveFailures)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `interactive and automation HTTP requests share one execution slot`() {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val serverExecutor = Executors.newCachedThreadPool()
        server.executor = serverExecutor
        server.createContext("/test") { exchange ->
            val current = active.incrementAndGet()
            maximumActive.accumulateAndGet(current, ::maxOf)
            if (firstEntered.count == 1L) {
                firstEntered.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
            active.decrementAndGet()
            exchange.sendText("OK")
        }
        server.start()
        val client = client()
        val url = "http://localhost:${server.address.port}/test"

        try {
            val interactive = thread {
                client.execute(HofRequest(HofHttpMethod.GET, url, origin = HofRequestOrigin.INTERACTIVE))
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val automation = thread {
                client.execute(HofRequest(HofHttpMethod.GET, url, origin = HofRequestOrigin.AUTOMATION))
            }
            Thread.sleep(50)
            assertEquals(1, maximumActive.get())
            releaseFirst.countDown()
            interactive.join(2_000)
            automation.join(2_000)
            assertFalse(interactive.isAlive)
            assertFalse(automation.isAlive)
            assertEquals(1, maximumActive.get())
        } finally {
            releaseFirst.countDown()
            server.stop(0)
            serverExecutor.shutdownNow()
        }
    }

    private fun client(): HofHttpClient = HofHttpClient(
        governor = HofRequestGovernor(
            properties = HofRequestProperties(minimumInterval = Duration.ZERO),
            timeProvider = TimeProvider { Instant.now() },
            waiter = HofRequestWaiter { },
        ),
    )

    private fun serverReturning(status: Int): HttpServer =
        HttpServer.create(InetSocketAddress(0), 0).also { server ->
            server.createContext("/test") { exchange ->
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            server.start()
        }

    private fun HttpExchange.sendText(body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { output -> output.write(bytes) }
    }
}
