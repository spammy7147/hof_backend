package app.spammy.hof.external.client

import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

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
            val client = HofHttpClient()

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

    private fun HttpExchange.sendText(body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { output -> output.write(bytes) }
    }
}
