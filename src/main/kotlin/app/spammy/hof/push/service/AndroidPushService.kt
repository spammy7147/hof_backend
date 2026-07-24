package app.spammy.hof.push.service

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("prod")
class AndroidPushService(
    private val firebaseMessaging: FirebaseMessaging,
    private val queryRepository: app.spammy.hof.push.repository.DevicePushTargetQueryRepository,
    private val targetService: DevicePushTargetService,
) {
    fun sendCaptchaRequired(accountId: Long, challengeId: Long) {
        send(
            accountId = accountId,
            title = "HOF 인증이 필요합니다",
            body = "인증을 완료하면 자동전투가 이어집니다.",
            data = mapOf("type" to "CAPTCHA_REQUIRED", "challengeId" to challengeId.toString()),
        )
    }

    fun sendLoginRequired(accountId: Long) {
        send(
            accountId = accountId,
            title = "HOF 로그인 정보를 확인해 주세요",
            body = "로그인 정보를 갱신하면 자동전투가 이어집니다.",
            data = mapOf("type" to "LOGIN_REQUIRED"),
        )
    }

    private fun send(
        accountId: Long,
        title: String,
        body: String,
        data: Map<String, String>,
    ) {
        queryRepository.findActiveByAccountId(accountId).forEach { target ->
            val message = Message.builder()
                .setToken(target.targetValue)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .putAllData(data)
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setNotification(AndroidNotification.builder().setChannelId(CHANNEL_ID).build())
                        .build(),
                )
                .build()
            try {
                firebaseMessaging.send(message)
            } catch (error: FirebaseMessagingException) {
                if (error.messagingErrorCode in PERMANENT_TOKEN_ERRORS) targetService.deactivate(target) else throw error
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "automation-alerts"
        val PERMANENT_TOKEN_ERRORS = setOf(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT)
    }
}
