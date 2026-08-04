package app.spammy.hof.external.ocr

import app.spammy.hof.captcha.config.CaptchaAutoSolveProperties
import app.spammy.hof.captcha.service.CaptchaImageResponse
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tools.jackson.module.kotlin.jacksonObjectMapper

class HttpCaptchaImageRecognizerTest {
    @Test
    fun `posts the captcha as authenticated multipart and normalizes the JSON response`() {
        val receivedToken = AtomicReference<String>()
        val receivedContentType = AtomicReference<String>()
        val receivedBody = AtomicReference<ByteArray>()
        val server = server { exchange ->
            receivedToken.set(exchange.requestHeaders.getFirst("X-OCR-Token"))
            receivedContentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            receivedBody.set(exchange.requestBody.readAllBytes())
            val response = "{\"text\":\" A-B_12 \\n\",\"engineVersion\":\"2.1.1\"}"
                .toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        try {
            val recognizer = HttpCaptchaImageRecognizer(properties(server), jacksonObjectMapper())

            val result = recognizer.recognize(CaptchaImageResponse("image/png", byteArrayOf(1, 2, 3)))

            assertEquals("AB12", result?.text)
            assertEquals("2.1.1", result?.engineVersion)
            assertEquals(TOKEN, receivedToken.get())
            assertTrue(receivedContentType.get().startsWith("multipart/form-data; boundary="))
            val multipart = receivedBody.get().toString(StandardCharsets.ISO_8859_1)
            assertTrue(multipart.contains("name=\"image\"; filename=\"captcha.png\""))
            assertTrue(multipart.contains("Content-Type: image/png"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `treats OCR 422 as an unreadable image`() {
        val server = server { exchange ->
            exchange.requestBody.close()
            val response = "{\"detail\":\"No text recognized\"}".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(422, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }

        try {
            val recognizer = HttpCaptchaImageRecognizer(properties(server), jacksonObjectMapper())

            assertNull(recognizer.recognize(CaptchaImageResponse("image/png", byteArrayOf(1))))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `normalizes API punctuation to allowed captcha characters`() {
        assertEquals(
            "aB12",
            normalizeOcrResponse(" a-B 1_2\n", "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"),
        )
        assertNull(normalizeOcrResponse(" --_ \n", "ABC123"))
    }

    private fun properties(server: HttpServer) = CaptchaAutoSolveProperties(
        enabled = true,
        baseUrl = "http://127.0.0.1:${server.address.port}",
        token = TOKEN,
    )

    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ocr", handler)
        server.start()
        assertNotNull(server.address)
        return server
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
