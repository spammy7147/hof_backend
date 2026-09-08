package app.spammy.hof.external.client

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.config.HofRequestProperties
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofFormField
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofRequestOrigin
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.http.HttpRequest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HofHttpClientTest {
    @Test
    @org.junit.jupiter.api.Timeout(20)
    fun `a three second gap reuses the connection and idle cleanup retires it`() {
        assertEquals("4", System.getProperty("jdk.httpclient.keepalive.timeout"))
        val connections = CopyOnWriteArrayList<InetSocketAddress>()
        val methods = CopyOnWriteArrayList<String>()
        val retired = CountDownLatch(1)
        val activeSocket = AtomicReference<Socket?>()
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val executor = Executors.newSingleThreadExecutor()
        val serving = executor.submit {
            while (methods.size < 3) {
                server.accept().use { socket ->
                    activeSocket.set(socket)
                    socket.soTimeout = 10_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (methods.size < 3) {
                        val requestLine = reader.readLine()
                        if (requestLine == null) {
                            if (methods.size == 2) retired.countDown()
                            break
                        }
                        // 이 fixture의 세 요청은 모두 본문이 없다.
                        while (!requireNotNull(reader.readLine()).isEmpty()) { }
                        connections += socket.remoteSocketAddress as InetSocketAddress
                        methods += requestLine.substringBefore(' ')
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray(Charsets.US_ASCII))
                            flush()
                        }
                    }
                }
            }
        }

        try {
            val client = client()
            val url = "http://127.0.0.1:${server.localPort}/test"
            client.execute(ACCOUNT_ID, HofRequest(HofHttpMethod.GET, url))
            Thread.sleep(3_000)
            client.execute(ACCOUNT_ID, HofRequest(HofHttpMethod.POST, url))
            assertEquals(connections[0], connections[1], "3초 간격의 요청은 연결을 재사용한다")

            // JDK 21의 비동기 selector 정리는 만료 시각보다 늦게 실행될 수 있다.
            // 4초 정책 + 기본 selector 대기 3초에 여유를 두고, 실제 EOF를 기다린다.
            assertTrue(retired.await(8, TimeUnit.SECONDS), "유휴 연결은 추가 요청 없이 종료되어야 한다")
            client.execute(ACCOUNT_ID, HofRequest(HofHttpMethod.POST, url))
            serving.get(2, TimeUnit.SECONDS)

            assertEquals(listOf("GET", "POST", "POST"), methods)
            assertNotEquals(connections[1], connections[2], "유휴 연결 정리 후 POST는 새 연결을 사용한다")
        } finally {
            server.close()
            activeSocket.get()?.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `a response taking longer than four seconds is not interrupted by idle cleanup`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/test") { exchange ->
            Thread.sleep(4_500)
            exchange.sendText("slow response")
        }
        server.start()

        try {
            val response = client().execute(
                ACCOUNT_ID,
                HofRequest(HofHttpMethod.POST, "http://localhost:${server.address.port}/test"),
            )

            assertEquals(200, response.statusCode)
            assertEquals("slow response", response.body)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `same origin redirect is followed and redirect cookies are retained`() {
        val cookies = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "NO=42; Path=/")
            exchange.responseHeaders.add("Location", "/finish")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/finish") { exchange ->
            cookies += exchange.requestHeaders.getFirst("Cookie")
            exchange.sendText("done")
        }
        server.start()

        try {
            val response = client().execute(
                ACCOUNT_ID,
                HofRequest(HofHttpMethod.GET, "http://localhost:${server.address.port}/start"),
                mapOf("PHPSESSID" to "session"),
            )

            assertEquals(200, response.statusCode)
            assertEquals(mapOf("NO" to "42"), response.setCookies)
            assertEquals("PHPSESSID=session; NO=42", cookies.single())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `get form query is attached only to initial hop across 307 redirect`() {
        val queries = mutableListOf<Pair<String, String?>>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/start") { exchange ->
            queries += "start" to exchange.requestURI.rawQuery
            exchange.responseHeaders.add("Location", "/finish")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.createContext("/finish") { exchange ->
            queries += "finish" to exchange.requestURI.rawQuery
            exchange.sendText("done")
        }
        server.start()

        try {
            client().execute(
                ACCOUNT_ID,
                HofRequest(
                    HofHttpMethod.GET,
                    "http://localhost:${server.address.port}/start",
                    formFields = mapOf("x" to "1"),
                ),
            )

            assertEquals(listOf("start" to "x=1", "finish" to null), queries)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `post encoding preserves repeated form names and Korean recruitment name using HOF EUC-KR`() {
        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/submit") { exchange ->
            bodies += exchange.requestBody.bufferedReader().readText()
            exchange.sendText("done")
        }
        server.start()

        try {
            client().execute(
                ACCOUNT_ID,
                HofRequest(
                    method = HofHttpMethod.POST,
                    url = "http://localhost:${server.address.port}/submit",
                    formEntries = listOf(
                        HofFormField("token", "first"),
                        HofFormField("token", "second"),
                        HofFormField("NewName", "새동료"),
                    ),
                ),
            )

            assertEquals("token=first&token=second&NewName=%BB%F5%B5%BF%B7%E1", bodies.single())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `cross origin redirect is rejected before session cookie can leave origin`() {
        val externalRequests = AtomicInteger(0)
        val external = HttpServer.create(InetSocketAddress(0), 0).also { server ->
            server.createContext("/leak") { exchange ->
                externalRequests.incrementAndGet()
                exchange.sendText("leaked")
            }
            server.start()
        }
        val origin = HttpServer.create(InetSocketAddress(0), 0).also { server ->
            server.createContext("/start") { exchange ->
                exchange.responseHeaders.add("Location", "http://localhost:${external.address.port}/leak")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.start()
        }

        try {
            val error = assertFailsWith<ApiException> {
                client().execute(
                    ACCOUNT_ID,
                    HofRequest(HofHttpMethod.GET, "http://localhost:${origin.address.port}/start"),
                    mapOf("PHPSESSID" to "secret"),
                )
            }
            assertEquals(ErrorCode.HOF_REQUEST_FAILED, error.errorCode)
            assertEquals(0, externalRequests.get())
        } finally {
            origin.stop(0)
            external.stop(0)
        }
    }

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

            assertEquals(now.plusSeconds(1), error.retryAt)
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
        val requestExecutor = Executors.newFixedThreadPool(2)
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
        val governor = governor()
        val queuedRequests = AtomicInteger()
        val secondQueued = CountDownLatch(1)
        governor.queueObserver = HofRequestQueueObserver { accountId, _ ->
            if (accountId == ACCOUNT_ID && queuedRequests.incrementAndGet() == 2) secondQueued.countDown()
        }
        val client = HofHttpClient(governor)
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
            assertTrue(secondQueued.await(2, TimeUnit.SECONDS))
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

    private fun client(): HofHttpClient = HofHttpClient(governor())

    private fun governor(): HofRequestGovernor = HofRequestGovernor(
        properties = HofRequestProperties(
            interactiveMinimumInterval = Duration.ZERO,
            automationMinimumInterval = Duration.ZERO,
        ),
        timeProvider = TimeProvider { Instant.now() },
        waiter = HofRequestWaiter { },
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

    private companion object {
        const val ACCOUNT_ID = 17L
        const val FIRST_ACCOUNT_ID = 23L
        const val SECOND_ACCOUNT_ID = 29L
    }
}
