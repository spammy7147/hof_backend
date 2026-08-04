package app.spammy.hof.external.ocr

import app.spammy.hof.captcha.config.CaptchaAutoSolveProperties
import app.spammy.hof.captcha.service.CaptchaFeedback
import app.spammy.hof.captcha.service.CaptchaFeedbackSource
import app.spammy.hof.captcha.service.CaptchaImageResponse
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpCaptchaFeedbackGatewayTest {
    @Test
    fun `posts authenticated feedback as multipart`() {
        val token = AtomicReference<String>()
        val body = AtomicReference<String>()
        val server = server(200) { exchangeBody, receivedToken ->
            token.set(receivedToken)
            body.set(exchangeBody.toString(StandardCharsets.ISO_8859_1))
        }

        try {
            HttpCaptchaFeedbackGateway(properties(server)).submit(feedback())

            assertEquals(TOKEN, token.get())
            val multipart = body.get()
            assertTrue(multipart.contains("name=\"submitted_text\"\r\n\r\nu34Wc"))
            assertTrue(multipart.contains("name=\"correct_text\"\r\n\r\nu34We"))
            assertTrue(multipart.contains("name=\"accepted\"\r\n\r\nfalse"))
            assertTrue(multipart.contains("name=\"source\"\r\n\r\nAUTOMATIC"))
            assertTrue(multipart.contains("name=\"engine_version\"\r\n\r\n2.1.1"))
            assertTrue(multipart.contains("name=\"predicted_text\"\r\n\r\nu34Wc"))
            assertTrue(multipart.contains("name=\"image\"; filename=\"captcha.png\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects a non-success feedback response`() {
        val server = server(422) { _, _ -> }
        try {
            assertFailsWith<IllegalStateException> {
                HttpCaptchaFeedbackGateway(properties(server)).submit(feedback())
            }
        } finally {
            server.stop(0)
        }
    }

    private fun feedback() = CaptchaFeedback(
        image = CaptchaImageResponse("image/png", byteArrayOf(1, 2, 3)),
        predictedText = "u34Wc",
        submittedText = "u34Wc",
        correctText = "u34We",
        accepted = false,
        source = CaptchaFeedbackSource.AUTOMATIC,
        engineVersion = "2.1.1",
    )

    private fun properties(server: HttpServer) = CaptchaAutoSolveProperties(
        enabled = true,
        baseUrl = "http://127.0.0.1:${server.address.port}",
        token = TOKEN,
    )

    private fun server(
        status: Int,
        capture: (ByteArray, String?) -> Unit,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/feedback") { exchange ->
            capture(exchange.requestBody.readAllBytes(), exchange.requestHeaders.getFirst("X-OCR-Token"))
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        return server
    }

    private companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
