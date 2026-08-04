package app.spammy.hof.external.ocr

import app.spammy.hof.captcha.config.CaptchaAutoSolveProperties
import app.spammy.hof.captcha.service.CaptchaImageRecognizer
import app.spammy.hof.captcha.service.CaptchaImageResponse
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class HttpCaptchaImageRecognizer(
    private val properties: CaptchaAutoSolveProperties,
    private val objectMapper: ObjectMapper,
) : CaptchaImageRecognizer {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.timeout)
        .build()

    override fun recognize(image: CaptchaImageResponse): String? {
        check(properties.enabled) { "CAPTCHA automatic solving is disabled" }

        val boundary = "----HofCaptcha${UUID.randomUUID()}"
        val request = HttpRequest.newBuilder(ocrEndpoint())
            .timeout(properties.timeout)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header(OCR_TOKEN_HEADER, properties.token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(image, boundary)))
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("OCR API request was interrupted", error)
        }

        if (response.statusCode() == UNPROCESSABLE_CONTENT) return null
        if (response.statusCode() != SUCCESS) {
            throw IllegalStateException("OCR API returned status=${response.statusCode()}")
        }

        val payload = objectMapper.readValue(response.body(), CaptchaOcrResponse::class.java)
        return normalizeOcrResponse(payload.text, properties.allowedCharacters)
    }

    private fun ocrEndpoint(): URI =
        URI.create("${properties.baseUrl.trim().trimEnd('/')}/ocr")

    private fun multipartBody(image: CaptchaImageResponse, boundary: String): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(
            "Content-Disposition: form-data; name=\"image\"; filename=\"captcha.${extension(image.contentType)}\"\r\n"
                .toByteArray(StandardCharsets.UTF_8),
        )
        output.write("Content-Type: ${image.contentType}\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(image.bytes)
        output.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
        return output.toByteArray()
    }

    private fun extension(contentType: String): String = when (contentType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> "png"
    }

    private data class CaptchaOcrResponse(val text: String = "")

    private companion object {
        const val OCR_TOKEN_HEADER = "X-OCR-Token"
        const val SUCCESS = 200
        const val UNPROCESSABLE_CONTENT = 422
    }
}

internal fun normalizeOcrResponse(output: String, allowedCharacters: String): String? {
    val allowed = allowedCharacters.toSet()
    return output.asSequence()
        .filter(allowed::contains)
        .joinToString("")
        .ifBlank { null }
}
