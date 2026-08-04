package app.spammy.hof.captcha.service

fun interface CaptchaImageRecognizer {
    fun recognize(image: CaptchaImageResponse): String?
}
