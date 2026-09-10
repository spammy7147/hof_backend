package app.spammy.hof.push.kafka

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.outbox.AutomationConsumedEventService
import app.spammy.hof.automation.outbox.AutomationOutboxQueryRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.repository.CaptchaChallengeRepository
import app.spammy.hof.captcha.repository.CaptchaQueryRepository
import app.spammy.hof.push.dto.RegisterAndroidPushTargetRequest
import app.spammy.hof.push.repository.DevicePushTargetQueryRepository
import app.spammy.hof.push.service.AndroidPushService
import app.spammy.hof.push.service.DevicePushTargetService
import app.spammy.hof.push.service.FirebaseAndroidMessageSender
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.api.client.json.gson.GsonFactory
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.support.Acknowledgment
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.databind.JsonNode

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(QueryDslConfig::class, AccountQueryRepository::class, DevicePushTargetQueryRepository::class, CaptchaQueryRepository::class,
    DevicePushTargetService::class, AutomationOutboxQueryRepository::class,
    AutomationConsumedEventService::class, PushDeliveryIntegrationTest.Config::class)
open class PushDeliveryIntegrationTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var targets: DevicePushTargetService
    @Autowired private lateinit var queries: DevicePushTargetQueryRepository
    @Autowired private lateinit var consumed: AutomationConsumedEventService
    @Autowired private lateinit var challenges: CaptchaChallengeRepository
    @Autowired private lateinit var challengeQuery: CaptchaQueryRepository
    private val sender = Mockito.mock(FirebaseAndroidMessageSender::class.java)
    private val mapper = jacksonObjectMapper()

    @Test
    fun `한 기기 성공 뒤 다른 기기가 실패해도 새 consumer는 실패 기기만 재전송한다`() {
        val account = accounts.save(HofAccountEntity(loginId = "push-${UUID.randomUUID()}",
            encryptedPassword = "fixture", createdAt = NOW))
        // 동일 시각이면 최근 등록된 A가 먼저 선택된다.
        targets.register(account.id, RegisterAndroidPushTargetRequest("install-b", "token-b"))
        targets.register(account.id, RegisterAndroidPushTargetRequest("install-a", "token-a"))
        val delivered = mutableListOf<String>()
        var bFailed = false
        Mockito.doAnswer { call ->
            val token = call.getArgument<String>(0)
            if (token == "token-b" && !bFailed) {
                bFailed = true
                throw messagingError(MessagingErrorCode.UNAVAILABLE)
            }
            delivered += token
            null
        }.`when`(sender).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyMap())
        val event = PushRequestEvent(UUID.randomUUID().toString(), account.id, "LOGIN_REQUIRED")
        val payload = mapper.writeValueAsString(event)
        val firstAck = Mockito.mock(Acknowledgment::class.java)

        assertFailsWith<FirebaseMessagingException> { newConsumer().consume(payload, firstAck) }
        assertEquals(listOf("token-a"), delivered)
        Mockito.verifyNoInteractions(firstAck)
        val secondAck = Mockito.mock(Acknowledgment::class.java)
        newConsumer().consume(payload, secondAck)

        assertEquals(listOf("token-a", "token-b"), delivered)
        Mockito.verify(secondAck).acknowledge()
        newConsumer().consume(payload, Mockito.mock(Acknowledgment::class.java))
        assertEquals(listOf("token-a", "token-b"), delivered)
    }

    @ParameterizedTest
    @EnumSource(value = MessagingErrorCode::class, names = ["UNREGISTERED", "INVALID_ARGUMENT"])
    fun `영구 오류 토큰은 실제 등록 목록에서 비활성화하고 다른 기기는 전달한다`(code: MessagingErrorCode) {
        val account = accounts.save(HofAccountEntity(loginId = "push-${UUID.randomUUID()}",
            encryptedPassword = "fixture", createdAt = NOW))
        targets.register(account.id, RegisterAndroidPushTargetRequest("valid", "valid-token"))
        targets.register(account.id, RegisterAndroidPushTargetRequest("expired", "expired-token"))
        val delivered = mutableListOf<String>()
        Mockito.doAnswer { call ->
            val token = call.getArgument<String>(0)
            if (token == "expired-token") throw messagingError(code)
            delivered += token
            null
        }.`when`(sender).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyMap())
        val event = PushRequestEvent(UUID.randomUUID().toString(), account.id, "LOGIN_REQUIRED")
        val acknowledgment = Mockito.mock(Acknowledgment::class.java)

        newConsumer().consume(mapper.writeValueAsString(event), acknowledgment)

        assertEquals(listOf("valid-token"), delivered)
        assertEquals(listOf("valid"), targets.findAll(account.id).map { it.installationId })
        Mockito.verify(acknowledgment).acknowledge()
    }

    @Test
    fun `부분 전송 뒤 해결된 캡차는 재소비에서 남은 기기에도 다시 알리지 않는다`() {
        val account = accounts.save(HofAccountEntity(loginId = "push-${UUID.randomUUID()}",
            encryptedPassword = "fixture", createdAt = NOW))
        targets.register(account.id, RegisterAndroidPushTargetRequest("install-b", "token-b"))
        targets.register(account.id, RegisterAndroidPushTargetRequest("install-a", "token-a"))
        val challenge = challenges.save(CaptchaChallengeEntity(account = account, status = "READY", prompt = "fixture",
            imageUrl = null, sourceUrl = "https://example.test/captcha", answer = null, createdAt = NOW, answeredAt = null))
        val delivered = mutableListOf<String>()
        var bFailed = false
        Mockito.doAnswer { call ->
            val token = call.getArgument<String>(0)
            if (token == "token-b" && !bFailed) {
                bFailed = true
                throw messagingError(MessagingErrorCode.UNAVAILABLE)
            }
            delivered += token
            null
        }.`when`(sender).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyMap())
        val event = PushRequestEvent(UUID.randomUUID().toString(), account.id, "CAPTCHA_REQUIRED", challenge.id)
        val payload = mapper.writeValueAsString(event)
        assertFailsWith<FirebaseMessagingException> { newConsumer().consume(payload, Mockito.mock(Acknowledgment::class.java)) }
        assertEquals(listOf("token-a"), delivered)
        challenge.status = "ANSWERED"
        challenge.answeredAt = NOW
        challenges.save(challenge)
        val acknowledgment = Mockito.mock(Acknowledgment::class.java)

        newConsumer().consume(payload, acknowledgment)

        assertEquals(listOf("token-a"), delivered)
        Mockito.verify(acknowledgment).acknowledge()
    }

    @Test
    fun `전송 중 갱신된 token은 이전 token의 영구 오류로 잃지 않고 재소비에서 전달한다`() {
        val account = accounts.save(HofAccountEntity(loginId = "push-${UUID.randomUUID()}",
            encryptedPassword = "fixture", createdAt = NOW))
        targets.register(account.id, RegisterAndroidPushTargetRequest("installation", "old-token"))
        val delivered = mutableListOf<String>()
        Mockito.doAnswer { call ->
            val token = call.getArgument<String>(0)
            if (token == "old-token") {
                targets.register(account.id, RegisterAndroidPushTargetRequest("installation", "new-token"))
                throw messagingError(MessagingErrorCode.UNREGISTERED)
            }
            delivered += token
            null
        }.`when`(sender).send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.anyMap())
        val payload = mapper.writeValueAsString(PushRequestEvent(UUID.randomUUID().toString(), account.id, "LOGIN_REQUIRED"))
        val firstAck = Mockito.mock(Acknowledgment::class.java)

        assertFailsWith<FirebaseMessagingException> { newConsumer().consume(payload, firstAck) }

        Mockito.verifyNoInteractions(firstAck)
        assertEquals(listOf("installation"), targets.findAll(account.id).map { it.installationId })
        newConsumer().consume(payload, Mockito.mock(Acknowledgment::class.java))
        assertEquals(listOf("new-token"), delivered)
    }

    @Test
    fun `실제 Firebase 메시지는 재시도의 사건 식별과 알림 tag를 유지하고 새 사건은 구분한다`() {
        val account = accounts.save(HofAccountEntity(loginId = "push-${UUID.randomUUID()}",
            encryptedPassword = "fixture", createdAt = NOW))
        targets.register(account.id, RegisterAndroidPushTargetRequest("install-b", "token-b"))
        targets.register(account.id, RegisterAndroidPushTargetRequest("install-a", "token-a"))
        val firebase = Mockito.mock(FirebaseMessaging::class.java)
        val actualSender = FirebaseAndroidMessageSender(firebase)
        val messages = mutableListOf<JsonNode>()
        var bFailed = false
        Mockito.doAnswer { call ->
            val message = mapper.readTree(GsonFactory.getDefaultInstance().toString(call.getArgument<Message>(0)))
            messages.add(message)
            if (message.path("token").asText() == "token-b" && !bFailed) {
                bFailed = true
                throw messagingError(MessagingErrorCode.UNAVAILABLE)
            }
            "projects/fixture/messages/${messages.size}"
        }.`when`(firebase).send(Mockito.any(Message::class.java))
        val event = PushRequestEvent(UUID.randomUUID().toString(), account.id, "LOGIN_REQUIRED")
        val payload = mapper.writeValueAsString(event)

        assertFailsWith<FirebaseMessagingException> { newConsumer(actualSender).consume(payload, Mockito.mock(Acknowledgment::class.java)) }
        newConsumer(actualSender).consume(payload, Mockito.mock(Acknowledgment::class.java))

        assertEquals(listOf("token-a", "token-b", "token-b"), messages.map { it.path("token").asText() })
        assertEquals(List(3) { event.eventId }, messages.map { it.path("data").path("eventId").asText() })
        val tags = messages.map { it.path("android").path("notification").path("tag").asText() }
        assertTrue(tags.first().isNotBlank())
        assertEquals(1, tags.distinct().size)
        assertTrue(messages.all { it.path("android").path("notification").path("channel_id").asText() == "automation-alerts" })
        val next = event.copy(eventId = UUID.randomUUID().toString())
        newConsumer(actualSender).consume(mapper.writeValueAsString(next), Mockito.mock(Acknowledgment::class.java))
        assertEquals(5, messages.size)
        assertEquals(List(2) { next.eventId }, messages.takeLast(2).map { it.path("data").path("eventId").asText() })
        assertNotEquals(tags.first(), messages.last().path("android").path("notification").path("tag").asText())
    }

    @ParameterizedTest
    @ValueSource(strings = ["ANSWERED", "MISSING", "OTHER_ACCOUNT"])
    fun `최초 소비에서도 이미 끝났거나 소유하지 않은 캡차 알림은 보내지 않는다`(kind: String) {
        val account = accounts.save(HofAccountEntity(loginId = "push-${UUID.randomUUID()}",
            encryptedPassword = "fixture", createdAt = NOW))
        val owner = if (kind == "OTHER_ACCOUNT") accounts.save(HofAccountEntity(loginId = "peer-${UUID.randomUUID()}",
            encryptedPassword = "fixture", createdAt = NOW)) else account
        targets.register(account.id, RegisterAndroidPushTargetRequest("installation", "fixture-token"))
        val challenge = challenges.save(CaptchaChallengeEntity(account = owner,
            status = if (kind == "ANSWERED") "ANSWERED" else "READY", prompt = "fixture", imageUrl = null,
            sourceUrl = "https://example.test/captcha", answer = null, createdAt = NOW, answeredAt = null))
        if (kind == "MISSING") challenges.delete(challenge)
        val event = PushRequestEvent(UUID.randomUUID().toString(), account.id, "CAPTCHA_REQUIRED", challenge.id)
        val acknowledgment = Mockito.mock(Acknowledgment::class.java)

        newConsumer().consume(mapper.writeValueAsString(event), acknowledgment)

        Mockito.verifyNoInteractions(sender)
        Mockito.verify(acknowledgment).acknowledge()
    }

    private fun newConsumer(messageSender: FirebaseAndroidMessageSender = sender) =
        PushRequestConsumer(mapper, consumed, AndroidPushService(messageSender, queries, targets, consumed, challengeQuery))

    private fun messagingError(code: MessagingErrorCode): FirebaseMessagingException =
        Mockito.mock(FirebaseMessagingException::class.java).also {
            Mockito.`when`(it.messagingErrorCode).thenReturn(code)
        }

    @TestConfiguration
    class Config {
        @Bean fun clock(): TimeProvider = object : TimeProvider { override fun now() = NOW }
    }

    companion object {
        private val NOW = Instant.parse("2026-09-10T00:00:00Z")
    }
}
