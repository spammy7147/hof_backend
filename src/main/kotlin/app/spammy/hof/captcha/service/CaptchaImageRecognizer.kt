package app.spammy.hof.captcha.service

data class CaptchaRecognition(
    val text: String,
    val engineVersion: String,
)

fun interface CaptchaImageRecognizer {
    fun recognize(image: CaptchaImageResponse): CaptchaRecognition?
}
