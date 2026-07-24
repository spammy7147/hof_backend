package app.spammy.hof.push.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.push.entity.DevicePushTargetEntity
import app.spammy.hof.push.repository.DevicePushTargetQueryRepository
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class AndroidPushServiceTest {
    private val sender = Mockito.mock(FirebaseAndroidMessageSender::class.java)
    private val queries = Mockito.mock(DevicePushTargetQueryRepository::class.java)
    private val targets = Mockito.mock(DevicePushTargetService::class.java)
    private val service = AndroidPushService(sender, queries, targets)

    @Test
    fun `captcha notification is sent to every active target`() {
        Mockito.`when`(queries.findActiveByAccountId(7L))
            .thenReturn(listOf(target(1L, "token-a"), target(2L, "token-b")))

        service.sendCaptchaRequired(7L, 91L)

        val data = mapOf("type" to "CAPTCHA_REQUIRED", "challengeId" to "91")
        Mockito.verify(sender).send("token-a", TITLE, BODY, data)
        Mockito.verify(sender).send("token-b", TITLE, BODY, data)
    }

    @Test
    fun `no active target completes without sending`() {
        Mockito.`when`(queries.findActiveByAccountId(7L)).thenReturn(emptyList())

        service.sendCaptchaRequired(7L, 91L)

        Mockito.verifyNoInteractions(sender, targets)
    }

    @Test
    fun `permanent token error deactivates only that target and continues`() {
        val expired = target(1L, "expired")
        val valid = target(2L, "valid")
        Mockito.`when`(queries.findActiveByAccountId(7L)).thenReturn(listOf(expired, valid))
        Mockito.doThrow(messagingError(MessagingErrorCode.UNREGISTERED))
            .`when`(sender).send("expired", TITLE, BODY, captchaData())

        service.sendCaptchaRequired(7L, 91L)

        Mockito.verify(targets).deactivate(expired)
        Mockito.verify(sender).send("valid", TITLE, BODY, captchaData())
    }

    @Test
    fun `transient error escapes only after remaining targets are attempted`() {
        Mockito.`when`(queries.findActiveByAccountId(7L))
            .thenReturn(listOf(target(1L, "first"), target(2L, "second")))
        Mockito.doThrow(messagingError(MessagingErrorCode.UNAVAILABLE))
            .`when`(sender).send("first", TITLE, BODY, captchaData())

        assertFailsWith<FirebaseMessagingException> { service.sendCaptchaRequired(7L, 91L) }

        Mockito.verify(sender).send("second", TITLE, BODY, captchaData())
        Mockito.verifyNoInteractions(targets)
    }

    private fun messagingError(code: MessagingErrorCode): FirebaseMessagingException =
        Mockito.mock(FirebaseMessagingException::class.java).also {
            Mockito.`when`(it.messagingErrorCode).thenReturn(code)
        }

    private fun captchaData() = mapOf("type" to "CAPTCHA_REQUIRED", "challengeId" to "91")

    private fun target(id: Long, token: String) = DevicePushTargetEntity(
        id = id,
        account = HofAccountEntity(7L, "login", "encrypted", NOW),
        installationId = "install-$id",
        targetValue = token,
        active = true,
        lastSeenAt = NOW,
        createdAt = NOW,
    )

    private companion object {
        const val TITLE = "HOF 인증이 필요합니다"
        const val BODY = "인증을 완료하면 자동전투가 이어집니다."
        val NOW: Instant = Instant.parse("2026-07-24T00:00:00Z")
    }
}
