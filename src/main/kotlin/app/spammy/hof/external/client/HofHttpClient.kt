package app.spammy.hof.external.client

import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration

@Component
/**
 * HOF 원본 서버 HTML 요청을 실행하는 HTTP client다.
 *
 * HOF 응답은 EUC-KR일 수 있으므로 응답 content-type의 charset을 보고 문자열로 디코딩한다.
 */
class HofHttpClient private constructor(
    private val governor: HofRequestGovernor,
    private val client: HttpClient,
) : HofGateway {
    @Autowired
    constructor(governor: HofRequestGovernor) : this(governor, defaultClient())

    private val log = LoggerFactory.getLogger(HofHttpClient::class.java)

    /**
     * HOF 요청을 실제 HTTP 요청으로 변환해 실행하고 응답 body와 Set-Cookie를 반환한다.
     */
    override fun execute(
        accountId: Long,
        request: HofRequest,
        cookies: Map<String, String>,
    ): HofHttpResponse =
        governor.execute(accountId, request.origin) {
            executeHttp(accountId, request, cookies)
        }

    private fun executeHttp(
        accountId: Long,
        request: HofRequest,
        cookies: Map<String, String>,
    ): HofHttpResponse {
        val startedAt = System.nanoTime()
        log.info(
            "HOF OUT accountId={} method={} url={} formFields={} cookieNames={}",
            accountId,
            request.method,
            request.url,
            request.formFields.keys.sorted(),
            cookies.keys.sorted(),
        )
        val httpRequest = buildHttpRequest(request, cookies)
        val response = runCatching {
            client.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
        }.getOrElse { error ->
            log.error(
                "HOF ERROR accountId={} method={} url={} durationMs={} error={}",
                accountId,
                request.method,
                request.url,
                elapsedMs(startedAt),
                error.message,
                error,
            )
            throw error
        }
        val contentType = response.headers().firstValue("Content-Type").orElse(null)
        val setCookies = parseSetCookies(response.headers())

        log.info(
            "HOF IN accountId={} method={} url={} status={} finalUrl={} durationMs={} setCookieNames={} bytes={}",
            accountId,
            request.method,
            request.url,
            response.statusCode(),
            response.uri(),
            elapsedMs(startedAt),
            setCookies.keys.sorted(),
            response.body().size,
        )

        return HofHttpResponse(
            statusCode = response.statusCode(),
            finalUrl = response.uri().toString(),
            body = decodeBody(response.body(), contentType),
            setCookies = setCookies,
        )
    }

    /**
     * 내부 요청 모델을 Java HttpRequest로 변환한다.
     */
    private fun buildHttpRequest(request: HofRequest, cookies: Map<String, String>): HttpRequest {
        val requestUrl = when (request.method) {
            HofHttpMethod.GET -> appendGetQuery(request.url, request.formFields)
            HofHttpMethod.POST -> request.url
        }
        val builder = HttpRequest.newBuilder(URI.create(requestUrl))
            .timeout(Duration.ofSeconds(90))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.5,en;q=0.3")

        if (cookies.isNotEmpty()) {
            builder.header("Cookie", buildCookieHeader(cookies))
        }

        return when (request.method) {
            HofHttpMethod.GET -> builder.GET().build()
            HofHttpMethod.POST -> builder
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(request.formFields)))
                .build()
        }
    }

    /**
     * GET 요청일 때 formFields를 query string으로 붙인다.
     */
    private fun appendGetQuery(url: String, fields: Map<String, String>): String {
        if (fields.isEmpty()) {
            return url
        }

        val fragmentIndex = url.indexOf('#')
        val baseUrl = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val separator = when {
            !baseUrl.contains("?") -> "?"
            baseUrl.endsWith("?") || baseUrl.endsWith("&") -> ""
            else -> "&"
        }

        return baseUrl + separator + encodeForm(fields) + fragment
    }

    /**
     * form field Map을 x-www-form-urlencoded body/query 문자열로 변환한다.
     */
    private fun encodeForm(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { (name, value) ->
            "${urlEncode(name)}=${urlEncode(value)}"
        }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun buildCookieHeader(cookies: Map<String, String>): String =
        cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

    /**
     * 서버가 내려준 Set-Cookie header에서 쿠키 이름과 값만 추출한다.
     */
    private fun parseSetCookies(headers: HttpHeaders): Map<String, String> =
        headers.map()
            .asSequence()
            .filter { (name, _) -> name.equals("set-cookie", ignoreCase = true) }
            .flatMap { (_, values) -> values.asSequence() }
            .mapNotNull(::parseSetCookie)
            .toMap()

    /**
     * Set-Cookie 한 줄에서 첫 번째 name=value pair만 추출한다.
     */
    private fun parseSetCookie(header: String): Pair<String, String>? {
        val pair = header.substringBefore(";").trim()
        val name = pair.substringBefore("=", missingDelimiterValue = "").trim()
        val value = pair.substringAfter("=", missingDelimiterValue = "").trim()

        return if (name.isBlank()) {
            null
        } else {
            name to value
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        private val EUC_KR: Charset = Charset.forName("EUC-KR")
        private val CHARSET_PATTERN = Regex("""charset\s*=\s*"?([^;\s"]+)""", RegexOption.IGNORE_CASE)

        private fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        /**
         * 응답 bytes를 content-type charset 또는 기본 EUC-KR로 디코딩한다.
         */
        fun decodeBody(bytes: ByteArray, contentType: String?): String {
            val charsetName = contentType
                ?.let { CHARSET_PATTERN.find(it)?.groupValues?.get(1) }
                ?: EUC_KR.name()
            val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(EUC_KR)

            return bytes.toString(charset)
        }

        private fun elapsedMs(startedAt: Long): Long =
            (System.nanoTime() - startedAt) / 1_000_000
    }
}
