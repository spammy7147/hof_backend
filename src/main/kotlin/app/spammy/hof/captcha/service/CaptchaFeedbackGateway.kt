package app.spammy.hof.captcha.service

enum class CaptchaFeedbackSource {
    AUTOMATIC,
    MANUAL,
}

data class CaptchaFeedback(
    val image: CaptchaImageResponse,
    val predictedText: String?,
    val submittedText: String,
    val correctText: String,
    val accepted: Boolean,
    val source: CaptchaFeedbackSource,
    val engineVersion: String,
)

fun interface CaptchaFeedbackGateway {
    fun submit(feedback: CaptchaFeedback)
}
