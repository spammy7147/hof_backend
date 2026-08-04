package app.spammy.hof.external.ocr

import app.spammy.hof.captcha.config.CaptchaAutoSolveProperties
import app.spammy.hof.captcha.service.CaptchaFeedback
import app.spammy.hof.captcha.service.CaptchaFeedbackGateway
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class HttpCaptchaFeedbackGateway(
    private val properties: CaptchaAutoSolveProperties,
) : CaptchaFeedbackGateway {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.timeout)
        .build()

    override fun submit(feedback: CaptchaFeedback) {
        check(properties.enabled) { "CAPTCHA automatic solving is disabled" }

        val boundary = "----HofCaptchaFeedback${UUID.randomUUID()}"
        val request = HttpRequest.newBuilder(feedbackEndpoint())
            .timeout(properties.timeout)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header(OCR_TOKEN_HEADER, properties.token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(feedback, boundary)))
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("OCR feedback request was interrupted", error)
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("OCR feedback API returned status=${response.statusCode()}")
        }
    }

    private fun feedbackEndpoint(): URI =
        URI.create("${properties.baseUrl.trim().trimEnd('/')}/feedback")

    private fun multipartBody(feedback: CaptchaFeedback, boundary: String): ByteArray {
        val output = ByteArrayOutputStream()
        output.writeTextPart(boundary, "submitted_text", feedback.submittedText)
        output.writeTextPart(boundary, "correct_text", feedback.correctText)
        output.writeTextPart(boundary, "accepted", feedback.accepted.toString())
        output.writeTextPart(boundary, "source", feedback.source.name)
        output.writeTextPart(boundary, "engine_version", feedback.engineVersion)
        feedback.predictedText?.let { output.writeTextPart(boundary, "predicted_text", it) }

        output.write("--$boundary\r\n".utf8())
        output.write(
            "Content-Disposition: form-data; name=\"image\"; filename=\"captcha.${extension(feedback.image.contentType)}\"\r\n"
                .utf8(),
        )
        output.write("Content-Type: ${feedback.image.contentType}\r\n\r\n".utf8())
        output.write(feedback.image.bytes)
        output.write("\r\n--$boundary--\r\n".utf8())
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeTextPart(boundary: String, name: String, value: String) {
        write("--$boundary\r\n".utf8())
        write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".utf8())
        write(value.utf8())
        write("\r\n".utf8())
    }

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

    private fun extension(contentType: String): String = when (contentType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> "png"
    }

    private companion object {
        const val OCR_TOKEN_HEADER = "X-OCR-Token"
    }
}
