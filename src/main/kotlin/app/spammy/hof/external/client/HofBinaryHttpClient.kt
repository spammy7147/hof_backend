package app.spammy.hof.external.client

import app.spammy.hof.account.service.HofCookieHeaderBuilder
import app.spammy.hof.external.model.HofBinaryResponse
import app.spammy.hof.external.model.HofRequestOrigin
import org.slf4j.LoggerFactory
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
class HofBinaryHttpClient private constructor(
    private val cookieHeaderBuilder: HofCookieHeaderBuilder,
    private val governor: HofRequestGovernor,
    private val client: HttpClient,
) : HofBinaryGateway {
    @Autowired
    constructor(
        cookieHeaderBuilder: HofCookieHeaderBuilder,
        governor: HofRequestGovernor,
    ) : this(
        cookieHeaderBuilder = cookieHeaderBuilder,
        governor = governor,
        client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build(),
    )

    private val log = LoggerFactory.getLogger(HofBinaryHttpClient::class.java)

    /**
     * 이미지 URL을 호출하고 status, final URL, content-type, body bytes를 반환한다.
     */
    override fun get(
        accountId: Long,
        origin: HofRequestOrigin,
        url: String,
        cookies: Map<String, String>,
    ): HofBinaryResponse = governor.executeBinary(accountId, origin) {
        executeHttp(accountId, origin, url, cookies)
    }

    private fun executeHttp(
        accountId: Long,
        origin: HofRequestOrigin,
        url: String,
        cookies: Map<String, String>,
    ): HofBinaryResponse {
        val startedAt = System.nanoTime()
        log.info("HOF BINARY OUT accountId={} origin={} url={}", accountId, origin, url)
        val response = runCatching {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .GET()
            if (cookies.isNotEmpty()) {
                builder.header("Cookie", cookieHeaderBuilder.build(cookies))
            }
            client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        }.getOrElse { error ->
            log.error(
                "HOF BINARY ERROR accountId={} origin={} url={} durationMs={} error={}",
                accountId,
                origin,
                url,
                elapsedMs(startedAt),
                error.message,
                error,
            )
            throw error
        }

        log.info(
            "HOF BINARY IN accountId={} origin={} url={} status={} finalUrl={} durationMs={} bytes={}",
            accountId,
            origin,
            url,
            response.statusCode(),
            response.uri(),
            elapsedMs(startedAt),
            response.body().size,
        )

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

        private fun elapsedMs(startedAt: Long): Long =
            (System.nanoTime() - startedAt) / 1_000_000
    }
}
