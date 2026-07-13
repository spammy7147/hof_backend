package app.spammy.hof.external.client

import app.spammy.hof.account.service.HofCookieHeaderBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
            val client = HofBinaryHttpClient(HofCookieHeaderBuilder())

            val response = client.get(
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
            val client = HofBinaryHttpClient(HofCookieHeaderBuilder())

            client.get(url = "http://localhost:$port/ZeroHOF/pass_image.php", cookies = emptyMap())

            assertNull(capturedCookieHeaders.single())
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.sendBinary(statusCode: Int, body: ByteArray) {
        sendResponseHeaders(statusCode, body.size.toLong())
        responseBody.use { output -> output.write(body) }
    }
}
