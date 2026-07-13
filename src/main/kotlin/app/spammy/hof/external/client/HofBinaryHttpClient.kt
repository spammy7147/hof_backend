package app.spammy.hof.external.client

import app.spammy.hof.account.service.HofCookieHeaderBuilder
import app.spammy.hof.external.model.HofBinaryResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
/**
 * Java HttpClient로 HOF 바이너리 리소스를 호출하는 구현체다.
 */
class HofBinaryHttpClient(
    private val cookieHeaderBuilder: HofCookieHeaderBuilder,
    private val client: HttpClient,
) : HofBinaryGateway {
    @Autowired
    constructor(cookieHeaderBuilder: HofCookieHeaderBuilder) : this(
        cookieHeaderBuilder = cookieHeaderBuilder,
        client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build(),
    )

    /**
     * 이미지 URL을 호출하고 status, final URL, content-type, body bytes를 반환한다.
     */
    override fun get(url: String, cookies: Map<String, String>): HofBinaryResponse {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .GET()

        if (cookies.isNotEmpty()) {
            builder.header("Cookie", cookieHeaderBuilder.build(cookies))
        }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())

        return HofBinaryResponse(
            statusCode = response.statusCode(),
            finalUrl = response.uri().toString(),
            contentType = response.headers().firstValue("Content-Type").orElse(null),
            body = response.body(),
        )
    }

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    }
}
