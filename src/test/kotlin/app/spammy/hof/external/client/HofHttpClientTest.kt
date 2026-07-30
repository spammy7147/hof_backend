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
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HofHttpClientTest {
    @Test
    fun `HOF requests allow up to 90 seconds for a response`() {
        val buildHttpRequest = HofHttpClient::class.java.getDeclaredMethod(
            "buildHttpRequest",
            HofRequest::class.java,
            Map::class.java,
        ).apply { isAccessible = true }

        val request = buildHttpRequest.invoke(
            client(),
            HofRequest(HofHttpMethod.GET, "https://hof.example/test"),
            emptyMap<String, String>(),
        ) as HttpRequest

        assertEquals(Duration.ofSeconds(90), request.timeout().orElseThrow())
    }

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
                ACCOUNT_ID,
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
                    ACCOUNT_ID,
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
                    ACCOUNT_ID,
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
    fun `interactive and automation HTTP requests for the same account share one execution slot`() {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val serverExecutor = Executors.newCachedThreadPool()
        val workerNumber = AtomicInteger(0)
        val secondWorker = AtomicReference<Thread>()
        val requestExecutor = Executors.newFixedThreadPool(2) { task ->
            val number = workerNumber.incrementAndGet()
            Thread(task, "hof-http-client-test-$number").also { worker ->
                if (number == 2) secondWorker.set(worker)
            }
        }
        server.executor = serverExecutor
        server.createContext("/test") { exchange ->
            val current = active.incrementAndGet()
            maximumActive.accumulateAndGet(current, ::maxOf)
            if (firstEntered.count == 1L) {
                firstEntered.countDown()
                check(releaseFirst.await(2, TimeUnit.SECONDS)) { "timed out waiting to release first request" }
            } else {
                secondEntered.countDown()
            }
            active.decrementAndGet()
            exchange.sendText("OK")
        }
        server.start()
        val client = client()
        val url = "http://localhost:${server.address.port}/test"

        try {
            val interactive = requestExecutor.submit {
                client.execute(
                    ACCOUNT_ID,
                    HofRequest(HofHttpMethod.GET, url, origin = HofRequestOrigin.INTERACTIVE),
                )
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val automation = requestExecutor.submit {
                client.execute(
                    ACCOUNT_ID,
                    HofRequest(HofHttpMethod.GET, url, origin = HofRequestOrigin.AUTOMATION),
                )
            }
            awaitGovernorQueue(checkNotNull(secondWorker.get()))
            assertEquals(1, maximumActive.get())
            releaseFirst.countDown()
            interactive.get(2, TimeUnit.SECONDS)
            automation.get(2, TimeUnit.SECONDS)
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS))
            assertEquals(1, maximumActive.get())
        } finally {
            releaseFirst.countDown()
            server.stop(0)
            requestExecutor.shutdownNow()
            serverExecutor.shutdownNow()
        }
    }

    @Test
    fun `HTTP requests for different accounts use separate execution slots`() {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val bothEntered = CountDownLatch(2)
        val releaseBoth = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val serverExecutor = Executors.newCachedThreadPool()
        val requestExecutor = Executors.newFixedThreadPool(2)
        server.executor = serverExecutor
        server.createContext("/test") { exchange ->
            val current = active.incrementAndGet()
            maximumActive.accumulateAndGet(current, ::maxOf)
            bothEntered.countDown()
            check(releaseBoth.await(2, TimeUnit.SECONDS)) { "timed out waiting to release requests" }
            active.decrementAndGet()
            exchange.sendText("OK")
        }
        server.start()
        val client = client()
        val url = "http://localhost:${server.address.port}/test"

        try {
            val first = requestExecutor.submit {
                client.execute(FIRST_ACCOUNT_ID, HofRequest(HofHttpMethod.GET, url))
            }
            val second = requestExecutor.submit {
                client.execute(SECOND_ACCOUNT_ID, HofRequest(HofHttpMethod.GET, url))
            }

            assertTrue(bothEntered.await(2, TimeUnit.SECONDS))
            assertEquals(2, maximumActive.get())
            releaseBoth.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
        } finally {
            releaseBoth.countDown()
            server.stop(0)
            requestExecutor.shutdownNow()
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

    private fun awaitGovernorQueue(worker: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val waitingInGovernor = worker.state == Thread.State.WAITING &&
                worker.stackTrace.any { frame ->
                    frame.className == HofRequestGovernor::class.java.name &&
                        frame.methodName == "acquireExecutionSlot"
                }
            if (waitingInGovernor) return
            Thread.yield()
        }

        throw AssertionError(
            "Second request worker did not enter the HOF governor queue: " +
                "state=${worker.state}, stack=${worker.stackTrace.joinToString()}",
        )
    }

    private companion object {
        const val ACCOUNT_ID = 17L
        const val FIRST_ACCOUNT_ID = 23L
        const val SECOND_ACCOUNT_ID = 29L
    }
}
