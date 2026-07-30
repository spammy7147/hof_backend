package app.spammy.hof.external.client

import app.spammy.hof.account.service.HofCookieHeaderBuilder
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HofBinaryHttpClientTest {
    @Test
    fun getSendsCookieHeaderAndReturnsBinaryResponseMetadata() {
        val capturedCookieHeaders = mutableListOf<List<String>?>()
        val body = byteArrayOf(1, 2, 3)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/ZeroHOF/pass_image.php") { exchange ->
            capturedCookieHeaders += exchange.requestHeaders["Cookie"]
            exchange.responseHeaders.add("Content-Type", "image/png; charset=UTF-8")
            exchange.sendBinary(statusCode = 202, body = body)
        }
        server.start()

        try {
            val port = server.address.port
            val url = "http://localhost:$port/ZeroHOF/pass_image.php?code=abc"
            val client = HofBinaryHttpClient(HofCookieHeaderBuilder(), governor())

            val response = client.get(
                accountId = ACCOUNT_ID,
                origin = HofRequestOrigin.INTERACTIVE,
                url = url,
                cookies = linkedMapOf(
                    "PHPSESSID" to "abc",
                    "NO" to "1",
                ),
            )

            assertEquals(202, response.statusCode)
            assertEquals(url, response.finalUrl)
            assertEquals("image/png; charset=UTF-8", response.contentType)
            assertContentEquals(body, response.body)
            assertEquals(listOf("PHPSESSID=abc; NO=1"), capturedCookieHeaders.single())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun getDoesNotSendCookieHeaderWhenCookiesAreEmpty() {
        val capturedCookieHeaders = mutableListOf<List<String>?>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/ZeroHOF/pass_image.php") { exchange ->
            capturedCookieHeaders += exchange.requestHeaders["Cookie"]
            exchange.sendBinary(statusCode = 200, body = byteArrayOf(4, 5, 6))
        }
        server.start()

        try {
            val port = server.address.port
            val client = HofBinaryHttpClient(HofCookieHeaderBuilder(), governor())

            client.get(
                accountId = ACCOUNT_ID,
                origin = HofRequestOrigin.INTERACTIVE,
                url = "http://localhost:$port/ZeroHOF/pass_image.php",
                cookies = emptyMap(),
            )

            assertNull(capturedCookieHeaders.single())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `automation binary 503 preserves deferred retry metadata`() {
        val now = Instant.parse("2026-07-30T01:02:00Z")
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/binary") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        val governor = HofRequestGovernor(
            properties = HofRequestProperties(),
            timeProvider = TimeProvider { now },
            waiter = HofRequestWaiter { },
        )
        val client = HofBinaryHttpClient(HofCookieHeaderBuilder(), governor)

        try {
            val error = assertFailsWith<HofAutomationDeferredException> {
                client.get(
                    accountId = ACCOUNT_ID,
                    origin = HofRequestOrigin.AUTOMATION,
                    url = "http://localhost:${server.address.port}/binary",
                )
            }

            assertEquals(now.plusSeconds(30), error.retryAt)
            assertEquals(1, error.consecutiveFailures)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `same-account HTML and binary requests share one execution slot`() {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val htmlEntered = CountDownLatch(1)
        val binaryEntered = CountDownLatch(1)
        val releaseHtml = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val serverExecutor = Executors.newCachedThreadPool()
        val workerNumber = AtomicInteger(0)
        val secondWorker = AtomicReference<Thread>()
        val requestExecutor = Executors.newFixedThreadPool(2) { task ->
            val number = workerNumber.incrementAndGet()
            Thread(task, "hof-binary-http-client-test-$number").also { worker ->
                if (number == 2) secondWorker.set(worker)
            }
        }
        server.executor = serverExecutor
        server.createContext("/html") { exchange ->
            val current = active.incrementAndGet()
            maximumActive.accumulateAndGet(current, ::maxOf)
            htmlEntered.countDown()
            try {
                check(releaseHtml.await(2, TimeUnit.SECONDS)) { "timed out waiting to release HTML request" }
                exchange.sendBinary(200, "OK".toByteArray())
            } finally {
                active.decrementAndGet()
            }
        }
        server.createContext("/binary") { exchange ->
            val current = active.incrementAndGet()
            maximumActive.accumulateAndGet(current, ::maxOf)
            binaryEntered.countDown()
            try {
                exchange.sendBinary(200, byteArrayOf(1, 2, 3))
            } finally {
                active.decrementAndGet()
            }
        }
        server.start()
        val governor = governor()
        val htmlClient = HofHttpClient(governor)
        val binaryClient = HofBinaryHttpClient(HofCookieHeaderBuilder(), governor)
        val baseUrl = "http://localhost:${server.address.port}"

        try {
            val html = requestExecutor.submit {
                htmlClient.execute(ACCOUNT_ID, HofRequest(HofHttpMethod.GET, "$baseUrl/html"))
            }
            assertTrue(htmlEntered.await(2, TimeUnit.SECONDS))
            val binary = requestExecutor.submit {
                binaryClient.get(
                    ACCOUNT_ID,
                    HofRequestOrigin.INTERACTIVE,
                    "$baseUrl/binary",
                )
            }

            awaitGovernorQueue(checkNotNull(secondWorker.get()))
            assertEquals(1, maximumActive.get())
            releaseHtml.countDown()
            html.get(2, TimeUnit.SECONDS)
            binary.get(2, TimeUnit.SECONDS)
            assertTrue(binaryEntered.await(2, TimeUnit.SECONDS))
            assertEquals(1, maximumActive.get())
        } finally {
            releaseHtml.countDown()
            server.stop(0)
            requestExecutor.shutdownNow()
            serverExecutor.shutdownNow()
        }
    }

    @Test
    fun `different-account HTML and binary requests use separate execution slots`() {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val bothEntered = CountDownLatch(2)
        val releaseBoth = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val serverExecutor = Executors.newCachedThreadPool()
        val requestExecutor = Executors.newFixedThreadPool(2)
        server.executor = serverExecutor
        server.createContext("/") { exchange ->
            val current = active.incrementAndGet()
            maximumActive.accumulateAndGet(current, ::maxOf)
            bothEntered.countDown()
            try {
                check(releaseBoth.await(2, TimeUnit.SECONDS)) { "timed out waiting to release requests" }
                exchange.sendBinary(200, byteArrayOf(1, 2, 3))
            } finally {
                active.decrementAndGet()
            }
        }
        server.start()
        val governor = governor()
        val htmlClient = HofHttpClient(governor)
        val binaryClient = HofBinaryHttpClient(HofCookieHeaderBuilder(), governor)
        val baseUrl = "http://localhost:${server.address.port}"

        try {
            val html = requestExecutor.submit {
                htmlClient.execute(FIRST_ACCOUNT_ID, HofRequest(HofHttpMethod.GET, "$baseUrl/html"))
            }
            val binary = requestExecutor.submit {
                binaryClient.get(
                    SECOND_ACCOUNT_ID,
                    HofRequestOrigin.INTERACTIVE,
                    "$baseUrl/binary",
                )
            }

            assertTrue(bothEntered.await(2, TimeUnit.SECONDS))
            assertEquals(2, maximumActive.get())
            releaseBoth.countDown()
            html.get(2, TimeUnit.SECONDS)
            binary.get(2, TimeUnit.SECONDS)
        } finally {
            releaseBoth.countDown()
            server.stop(0)
            requestExecutor.shutdownNow()
            serverExecutor.shutdownNow()
        }
    }

    private fun governor(): HofRequestGovernor = HofRequestGovernor(
        properties = HofRequestProperties(minimumInterval = Duration.ZERO),
        timeProvider = TimeProvider { Instant.now() },
        waiter = HofRequestWaiter { },
    )

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
            "Binary request worker did not enter the HOF governor queue: " +
                "state=${worker.state}, stack=${worker.stackTrace.joinToString()}",
        )
    }

    private fun HttpExchange.sendBinary(statusCode: Int, body: ByteArray) {
        sendResponseHeaders(statusCode, body.size.toLong())
        responseBody.use { output -> output.write(body) }
    }

    private companion object {
        const val ACCOUNT_ID = 17L
        const val FIRST_ACCOUNT_ID = 23L
        const val SECOND_ACCOUNT_ID = 29L
    }
}
