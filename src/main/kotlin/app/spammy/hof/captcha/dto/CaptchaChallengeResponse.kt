package app.spammy.hof.captcha.dto

/**
 * 앱의 전역 캡차 모달에 보여줄 현재 캡차 상태 응답 DTO다.
 */
data class CaptchaChallengeResponse(
    val id: Long,
    val accountId: Long,
    val status: String,
    val prompt: String,
    val imageUrl: String?,
    val sourceUrl: String,
    val createdAt: String,
    val answeredAt: String?,
)

/**
 * 사용자가 입력한 캡차 답안을 백엔드에 제출할 때 쓰는 요청 DTO다.
 */
data class SubmitCaptchaAnswerRequest(
    val answer: String,
)
