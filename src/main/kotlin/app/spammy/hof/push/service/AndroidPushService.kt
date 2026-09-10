package app.spammy.hof.push.service

import app.spammy.hof.automation.outbox.AutomationConsumedEventService
import app.spammy.hof.captcha.repository.CaptchaQueryRepository
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.HexFormat

@Service
@Profile("prod")
class AndroidPushService(
    private val sender: FirebaseAndroidMessageSender,
    private val queryRepository: app.spammy.hof.push.repository.DevicePushTargetQueryRepository,
    private val targetService: DevicePushTargetService,
    private val consumed: AutomationConsumedEventService,
    private val challenges: CaptchaQueryRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun sendCaptchaRequired(accountId: Long, challengeId: Long, eventId: String) {
        if (challenges.findActiveByAccountId(accountId).none { it.id == challengeId }) return
        send(
            eventId = eventId,
            accountId = accountId,
            title = "HOF 인증이 필요합니다",
            body = "인증을 완료하면 자동전투가 이어집니다.",
            data = mapOf("type" to "CAPTCHA_REQUIRED", "challengeId" to challengeId.toString()),
        )
    }

    fun sendLoginRequired(accountId: Long, eventId: String) {
        send(
            eventId = eventId,
            accountId = accountId,
            title = "HOF 로그인 정보를 확인해 주세요",
            body = "로그인 정보를 갱신하면 자동전투가 이어집니다.",
            data = mapOf("type" to "LOGIN_REQUIRED"),
        )
    }

    private fun send(
        eventId: String,
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
            val deliveryId = deliveryId(eventId, accountId, target.id)
            if (consumed.wasConsumed(deliveryId)) return@forEach
            try {
                sender.send(target.targetValue, title, body, data + ("eventId" to eventId))
                // 각 기기의 전송 완료는 뒤 기기의 실패와 독립적으로 commit한다.
                consumed.record(deliveryId)
                sent += 1
            } catch (error: FirebaseMessagingException) {
                if (error.messagingErrorCode in PERMANENT_TOKEN_ERRORS && targetService.deactivateRejectedToken(target)) {
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

    private fun deliveryId(eventId: String, accountId: Long, targetId: Long): String =
        "push-target:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest("$accountId:$eventId:$targetId".toByteArray(Charsets.UTF_8)))

    private companion object {
        val PERMANENT_TOKEN_ERRORS = setOf(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT)
    }
}
