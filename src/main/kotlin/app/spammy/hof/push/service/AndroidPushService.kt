package app.spammy.hof.push.service

import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Service
@Profile("prod")
class AndroidPushService(
    private val sender: FirebaseAndroidMessageSender,
    private val queryRepository: app.spammy.hof.push.repository.DevicePushTargetQueryRepository,
    private val targetService: DevicePushTargetService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
        val targets = queryRepository.findActiveByAccountId(accountId)
        var sent = 0
        var deactivated = 0
        var transientFailures = 0
        var firstTransientFailure: FirebaseMessagingException? = null
        targets.forEach { target ->
            try {
                sender.send(target.targetValue, title, body, data)
                sent += 1
            } catch (error: FirebaseMessagingException) {
                if (error.messagingErrorCode in PERMANENT_TOKEN_ERRORS) {
                    targetService.deactivate(target)
                    deactivated += 1
                } else {
                    transientFailures += 1
                    if (firstTransientFailure == null) firstTransientFailure = error
                }
            }
        }
        log.info(
            "Android push fan-out accountId={} activeTargets={} sent={} deactivated={} transientFailures={}",
            accountId,
            targets.size,
            sent,
            deactivated,
            transientFailures,
        )
        firstTransientFailure?.let { throw it }
    }

    private companion object {
        val PERMANENT_TOKEN_ERRORS = setOf(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT)
    }
}
